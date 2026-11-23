import { createSign } from "node:crypto";
import { readFileSync } from "node:fs";

/** Subscription products sold in Google Play. */
export const PRODUCT_IDS = ["steady_plus_monthly", "steady_plus_yearly"] as const;
export type ProductId = (typeof PRODUCT_IDS)[number];

export type PurchaseVerification =
  | { valid: true; /** When the current billing period ends, if known. */ expiresAt: Date | null }
  | { valid: false; reason: string };

/**
 * Checks a Play purchase token with the store. Implementations must never log
 * the token. Throwing means "store unreachable" (→ 502); `valid: false` means
 * the store said no (→ 400 purchase_invalid).
 */
export interface PurchaseVerifier {
  readonly name: string;
  verify(productId: ProductId, purchaseToken: string): Promise<PurchaseVerification>;
}

/**
 * Development/test verifier: accepts any token starting with "test-" and
 * rejects everything else. Never selected when NODE_ENV=production.
 */
export class DevPurchaseVerifier implements PurchaseVerifier {
  readonly name = "dev";
  constructor(private readonly now: () => Date = () => new Date()) {}

  verify(_productId: ProductId, purchaseToken: string): Promise<PurchaseVerification> {
    if (!purchaseToken.startsWith("test-")) {
      return Promise.resolve({ valid: false, reason: "Dev verifier only accepts test- tokens" });
    }
    return Promise.resolve({ valid: true, expiresAt: new Date(this.now().getTime() + 30 * 86_400_000) });
  }
}

export interface GooglePlayConfig {
  /** Android application id, e.g. "app.steady". Env: GOOGLE_PLAY_PACKAGE_NAME. */
  packageName: string;
  /**
   * Service-account key JSON (the file downloaded from Google Cloud, with
   * `client_email` and `private_key`). Env: GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
   * (inline JSON) or GOOGLE_PLAY_SERVICE_ACCOUNT_FILE (path). The account needs
   * "View financial data" + "Manage orders and subscriptions" in Play Console.
   */
  serviceAccount: { client_email: string; private_key: string };
  fetch?: typeof fetch;
}

/**
 * Google Play verifier (skeleton, not exercised by tests).
 *
 * Flow:
 *  1. Mint an OAuth access token with a service-account JWT (RS256, scope
 *     https://www.googleapis.com/auth/androidpublisher) at
 *     https://oauth2.googleapis.com/token.
 *  2. GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/
 *     {packageName}/purchases/subscriptionsv2/tokens/{purchaseToken}
 *     (androidpublisher `purchases.subscriptionsv2.get`).
 *  3. Valid when `subscriptionState` is SUBSCRIPTION_STATE_ACTIVE or
 *     SUBSCRIPTION_STATE_IN_GRACE_PERIOD and a `lineItems[]` entry has the
 *     requested `productId`; its `expiryTime` becomes `expiresAt`.
 *
 * Still to do before launch: acknowledge new purchases
 * (`purchases.subscriptions.acknowledge`, or let the app acknowledge), and
 * handle Real-time Developer Notifications (Pub/Sub) to downgrade on expiry,
 * revoke or refund; honour `linkedPurchaseToken` on upgrades.
 */
export class GooglePlayVerifier implements PurchaseVerifier {
  readonly name = "google_play";
  private cached: { token: string; expiresAtMs: number } | null = null;
  private readonly fetchImpl: typeof fetch;

  constructor(private readonly config: GooglePlayConfig) {
    this.fetchImpl = config.fetch ?? fetch;
  }

  private async accessToken(): Promise<string> {
    const nowS = Math.floor(Date.now() / 1000);
    if (this.cached && this.cached.expiresAtMs - 60_000 > Date.now()) return this.cached.token;
    const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
    const unsigned = `${b64({ alg: "RS256", typ: "JWT" })}.${b64({
      iss: this.config.serviceAccount.client_email,
      scope: "https://www.googleapis.com/auth/androidpublisher",
      aud: "https://oauth2.googleapis.com/token",
      iat: nowS,
      exp: nowS + 3600,
    })}`;
    const signature = createSign("RSA-SHA256").update(unsigned).sign(this.config.serviceAccount.private_key);
    const res = await this.fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${unsigned}.${signature.toString("base64url")}`,
      }),
    });
    if (!res.ok) throw new Error(`Google OAuth token request failed (${res.status})`);
    const body = (await res.json()) as { access_token: string; expires_in: number };
    this.cached = { token: body.access_token, expiresAtMs: Date.now() + body.expires_in * 1000 };
    return body.access_token;
  }

  async verify(productId: ProductId, purchaseToken: string): Promise<PurchaseVerification> {
    const url =
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
      `${encodeURIComponent(this.config.packageName)}/purchases/subscriptionsv2/tokens/` +
      encodeURIComponent(purchaseToken);
    const res = await this.fetchImpl(url, {
      headers: { authorization: `Bearer ${await this.accessToken()}` },
    });
    if (res.status === 404 || res.status === 400 || res.status === 410) {
      return { valid: false, reason: "Unknown purchase token" };
    }
    if (!res.ok) throw new Error(`Play subscriptionsv2.get failed (${res.status})`);
    const sub = (await res.json()) as {
      subscriptionState?: string;
      lineItems?: { productId?: string; expiryTime?: string }[];
    };
    const active =
      sub.subscriptionState === "SUBSCRIPTION_STATE_ACTIVE" ||
      sub.subscriptionState === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
    const item = sub.lineItems?.find((l) => l.productId === productId);
    if (!active || !item) return { valid: false, reason: "Subscription is not active for this product" };
    return { valid: true, expiresAt: item.expiryTime ? new Date(item.expiryTime) : null };
  }
}

/**
 * Picks the verifier for this process:
 *  - Play credentials configured → GooglePlayVerifier;
 *  - otherwise, outside production → DevPurchaseVerifier ("test-" tokens);
 *  - production without credentials → null (POST /v1/billing/verify answers 503).
 */
export function verifierFromEnv(env: NodeJS.ProcessEnv = process.env): PurchaseVerifier | null {
  const packageName = env.GOOGLE_PLAY_PACKAGE_NAME;
  let json = env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!json && env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE) {
    json = readFileSync(env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE, "utf8");
  }
  if (packageName && json) {
    const sa = JSON.parse(json) as { client_email?: string; private_key?: string };
    if (!sa.client_email || !sa.private_key) {
      throw new Error("Play service account JSON needs client_email and private_key");
    }
    return new GooglePlayVerifier({
      packageName,
      serviceAccount: { client_email: sa.client_email, private_key: sa.private_key },
    });
  }
  if (env.NODE_ENV !== "production") return new DevPurchaseVerifier();
  return null;
}
