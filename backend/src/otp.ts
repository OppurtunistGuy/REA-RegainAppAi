/**
 * One-time-code delivery. Real email/SMS goes through an `OtpSender`; the console sender is for
 * development only and is never selected when NODE_ENV=production.
 */

export interface OtpSender {
  readonly name: string;
  /** Delivers `code` to `contact`. Must never log the code; log contacts only via maskContact(). */
  send(contact: string, code: string): Promise<void> | void;
}

/** Old-style function senders (tests) are still accepted by createApp. */
export type OtpSendFn = (contact: string, code: string) => void | Promise<void>;

export function isEmail(contact: string): boolean {
  return contact.includes("@");
}

/**
 * Masks a contact for logs: "riya@gmail.com" → "r***@g***.com", "+919800001234" → "••••1234".
 */
export function maskContact(contact: string): string {
  if (isEmail(contact)) {
    const at = contact.lastIndexOf("@");
    const local = contact.slice(0, at);
    const domain = contact.slice(at + 1);
    const dot = domain.lastIndexOf(".");
    const host = dot > 0 ? domain.slice(0, dot) : domain;
    const tld = dot > 0 ? domain.slice(dot) : "";
    return `${local.slice(0, 1) || "*"}***@${host.slice(0, 1) || "*"}***${tld}`;
  }
  const digits = contact.replace(/\D/g, "");
  return `••••${digits.slice(-4)}`;
}

/** Development sender: prints the code to the server console (contact masked). */
export class ConsoleOtpSender implements OtpSender {
  readonly name = "console";
  constructor(private readonly print: (line: string) => void = (l) => console.log(l)) {}
  send(contact: string, code: string): void {
    this.print(`[otp] code for ${maskContact(contact)}: ${code} (development only)`);
  }
}

export interface HttpOtpSenderConfig {
  /** Provider endpoint for email codes. Env: OTP_EMAIL_WEBHOOK_URL. */
  emailUrl?: string;
  /** Provider endpoint for SMS codes. Env: OTP_SMS_WEBHOOK_URL. */
  smsUrl?: string;
  /** Bearer token for the provider. Env: OTP_PROVIDER_TOKEN. */
  token?: string;
  fetch?: typeof fetch;
}

/**
 * Real delivery (skeleton). POSTs `{to, code, channel, ttlMinutes}` as JSON to a transactional
 * email or SMS endpoint, with `Authorization: Bearer <OTP_PROVIDER_TOKEN>`. Point the URLs at a
 * small adapter for the chosen provider (e.g. an email API's transactional send, an SMS API's
 * messages endpoint) that renders the template. Not exercised against a real provider in tests.
 */
export class HttpOtpSender implements OtpSender {
  readonly name = "http";
  private readonly fetchImpl: typeof fetch;
  constructor(private readonly config: HttpOtpSenderConfig) {
    this.fetchImpl = config.fetch ?? fetch;
  }

  async send(contact: string, code: string): Promise<void> {
    const channel = isEmail(contact) ? "email" : "sms";
    const url = channel === "email" ? this.config.emailUrl : this.config.smsUrl;
    if (!url) throw new Error(`No OTP ${channel} provider configured`);
    const res = await this.fetchImpl(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(this.config.token ? { authorization: `Bearer ${this.config.token}` } : {}),
      },
      body: JSON.stringify({ to: contact, code, channel, ttlMinutes: 10 }),
    });
    if (!res.ok) throw new Error(`OTP ${channel} provider answered ${res.status}`);
  }
}

/**
 * Picks the sender for this process: provider URLs configured → HttpOtpSender; otherwise the
 * console sender outside production; production without a provider → null (OTP start → 503).
 */
export function otpSenderFromEnv(env: NodeJS.ProcessEnv = process.env): OtpSender | null {
  const emailUrl = env.OTP_EMAIL_WEBHOOK_URL || undefined;
  const smsUrl = env.OTP_SMS_WEBHOOK_URL || undefined;
  if (emailUrl || smsUrl) {
    return new HttpOtpSender({ emailUrl, smsUrl, token: env.OTP_PROVIDER_TOKEN || undefined });
  }
  if (env.NODE_ENV !== "production") return new ConsoleOtpSender();
  return null;
}
