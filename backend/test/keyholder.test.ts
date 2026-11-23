import { describe, expect, it } from "vitest";
import { errorCode, harness, samplePlan, type Harness } from "./helpers.js";

async function paired(h: Harness) {
  const owner = await h.login("riya@example.com", "Riya");
  const kh = await h.login("asha@example.com", "Asha");
  const inv = await h.req("POST", "/v1/keyholder/invites", { token: owner.token });
  const { code } = (await inv.json()) as { code: string };
  const acc = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token });
  expect(acc.status).toBe(200);
  return { owner, kh };
}

async function newRequest(h: Harness, token: string, body: unknown = { surfaceId: "instagram.reels", reason: "Need to reply to a class group" }) {
  const res = await h.req("POST", "/v1/unlock-requests", { token, body });
  expect(res.status).toBe(201);
  return (await res.json()) as { id: string; status: string };
}

describe("key holder invites", () => {
  it("owner creates a 6-char invite expiring in 24h", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/keyholder/invites", { token: owner.token });
    expect(res.status).toBe(201);
    const body = (await res.json()) as { code: string; expiresAt: string };
    expect(body.code).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
    expect(Date.parse(body.expiresAt) - h.clock.ms).toBe(24 * 60 * 60_000);
  });

  it("invite create rejects unknown body fields", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/keyholder/invites", { token: owner.token, body: { note: "x" } });
    expect(res.status).toBe(400);
    expect((await h.req("POST", "/v1/keyholder/invites", { token: owner.token, body: {} })).status).toBe(201);
  });

  it("key holder accepts and gets owner id + name", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com", "Riya");
    const kh = await h.login("asha@example.com");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    const res = await h.req("POST", `/v1/keyholder/invites/${code.toLowerCase()}/accept`, { token: kh.token });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ownerUserId: owner.userId, ownerName: "Riya" });
  });

  it("without a name the owner shows as \"Your friend\", never part of the contact", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("+919800001234");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    const res = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token });
    expect(((await res.json()) as { ownerName: string }).ownerName).toBe("Your friend");
  });

  it("invite is single use", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    const other = await h.login("dev@example.com");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    expect((await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token })).status).toBe(200);
    const reuse = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: other.token });
    expect(reuse.status).toBe(410);
    expect(await errorCode(reuse)).toBe("invite_used");
  });

  it("invite expires after 24h", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    h.clock.advance(24 * 60 * 60_000);
    const res = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token });
    expect(res.status).toBe(410);
    expect(await errorCode(res)).toBe("invite_expired");
  });

  it("owner cannot accept their own invite", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    const res = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: owner.token });
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("self_invite");
  });

  it("unknown or malformed code → 404", async () => {
    const h = harness();
    const kh = await h.login("asha@example.com");
    expect((await h.req("POST", "/v1/keyholder/invites/ZZZZZZ/accept", { token: kh.token })).status).toBe(404);
    expect((await h.req("POST", "/v1/keyholder/invites/abc/accept", { token: kh.token })).status).toBe(404);
  });
});

describe("unlock requests", () => {
  it("requires a linked key holder", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/unlock-requests", { token: owner.token, body: { surfaceId: null, reason: "Need to message my teacher" } });
    expect(res.status).toBe(409);
    expect(await errorCode(res)).toBe("no_keyholder");
  });

  it("validates body strictly", async () => {
    const h = harness();
    const { owner } = await paired(h);
    for (const body of [
      { surfaceId: null, reason: "ok reason", screenText: "secret chat" },
      { reason: "missing surface" },
      { surfaceId: "tiktok", reason: "bad surface" },
      { surfaceId: null, reason: "" },
      { surfaceId: null, reason: "x".repeat(281) },
    ]) {
      const res = await h.req("POST", "/v1/unlock-requests", { token: owner.token, body });
      expect(res.status, JSON.stringify(body).slice(0, 60)).toBe(400);
    }
  });

  it("full flow: request → key holder lists → approve → owner sees approved with until", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const created = await newRequest(h, owner.token);
    expect(created.status).toBe("pending");

    const poll = await h.req("GET", `/v1/unlock-requests/${created.id}`, { token: owner.token });
    expect(await poll.json()).toEqual({ id: created.id, status: "pending" });

    const list = await h.req("GET", "/v1/keyholder/requests", { token: kh.token });
    expect(list.status).toBe(200);
    expect(await list.json()).toEqual([
      {
        id: created.id,
        ownerName: "Riya",
        reason: "Need to reply to a class group",
        surfaceId: "instagram.reels",
        createdAt: new Date(h.clock.ms).toISOString(),
      },
    ]);

    h.clock.advance(60_000);
    const dec = await h.req("POST", `/v1/unlock-requests/${created.id}/decision`, { token: kh.token, body: { approve: true } });
    expect(dec.status).toBe(200);
    const expectedUntil = new Date(h.clock.ms + 15 * 60_000).toISOString();
    expect(await dec.json()).toEqual({ id: created.id, status: "approved", until: expectedUntil });

    const after = await h.req("GET", `/v1/unlock-requests/${created.id}`, { token: owner.token });
    expect(await after.json()).toEqual({ id: created.id, status: "approved", until: expectedUntil });
    // Key holder can also read it.
    expect((await h.req("GET", `/v1/unlock-requests/${created.id}`, { token: kh.token })).status).toBe(200);
    // No longer pending.
    expect(await (await h.req("GET", "/v1/keyholder/requests", { token: kh.token })).json()).toEqual([]);
  });

  it("approve with explicit minutes; max 240", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const r = await newRequest(h, owner.token);
    const tooLong = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true, minutes: 241 } });
    expect(tooLong.status).toBe(400);
    const zero = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true, minutes: 0 } });
    expect(zero.status).toBe(400);
    const ok = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true, minutes: 240 } });
    expect(((await ok.json()) as { until: string }).until).toBe(new Date(h.clock.ms + 240 * 60_000).toISOString());
  });

  it("default minutes follow the owner's plan unlockPolicy.approvalMinutes", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const plan = { ...samplePlan(1), unlockPolicy: { approvalMinutes: 30 } };
    expect((await h.req("PUT", "/v1/plan", { token: owner.token, body: plan })).status).toBe(200);
    const r = await newRequest(h, owner.token);
    const ok = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true } });
    expect(((await ok.json()) as { until: string }).until).toBe(new Date(h.clock.ms + 30 * 60_000).toISOString());
  });

  it("deny → status denied with no until", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const r = await newRequest(h, owner.token, { surfaceId: null, reason: "Just want to scroll a bit" });
    const dec = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: false } });
    expect(await dec.json()).toEqual({ id: r.id, status: "denied" });
    const poll = await h.req("GET", `/v1/unlock-requests/${r.id}`, { token: owner.token });
    expect(await poll.json()).toEqual({ id: r.id, status: "denied" });
  });

  it("cannot decide twice", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const r = await newRequest(h, owner.token);
    await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: false } });
    const again = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true } });
    expect(again.status).toBe(409);
    expect(await errorCode(again)).toBe("already_decided");
  });

  it("only the linked key holder may decide", async () => {
    const h = harness();
    const { owner } = await paired(h);
    const stranger = await h.login("stranger@example.com");
    const r = await newRequest(h, owner.token);
    const byStranger = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: stranger.token, body: { approve: true } });
    expect(byStranger.status).toBe(403);
    expect(await errorCode(byStranger)).toBe("forbidden");
    const byOwner = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: owner.token, body: { approve: true } });
    expect(byOwner.status).toBe(403);
    // Still pending.
    const poll = await h.req("GET", `/v1/unlock-requests/${r.id}`, { token: owner.token });
    expect(((await poll.json()) as { status: string }).status).toBe("pending");
  });

  it("strangers cannot read requests or see them in their list", async () => {
    const h = harness();
    const { owner } = await paired(h);
    const stranger = await h.login("stranger@example.com");
    const r = await newRequest(h, owner.token);
    expect((await h.req("GET", `/v1/unlock-requests/${r.id}`, { token: stranger.token })).status).toBe(404);
    expect(await (await h.req("GET", "/v1/keyholder/requests", { token: stranger.token })).json()).toEqual([]);
  });

  it("unknown request id → 404 for read and decision", async () => {
    const h = harness();
    const { kh } = await paired(h);
    expect((await h.req("GET", "/v1/unlock-requests/nope", { token: kh.token })).status).toBe(404);
    expect((await h.req("POST", "/v1/unlock-requests/nope/decision", { token: kh.token, body: { approve: true } })).status).toBe(404);
  });

  it("decision body is strict", async () => {
    const h = harness();
    const { owner, kh } = await paired(h);
    const r = await newRequest(h, owner.token);
    const res = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: { approve: true, comment: "hi" } });
    expect(res.status).toBe(400);
    const missing = await h.req("POST", `/v1/unlock-requests/${r.id}/decision`, { token: kh.token, body: {} });
    expect(missing.status).toBe(400);
  });

  it("key holder for several owners sees all pending requests in order", async () => {
    const h = harness();
    const kh = await h.login("asha@example.com");
    const ids: string[] = [];
    for (const name of ["Riya", "Dev"]) {
      const o = await h.login(`${name.toLowerCase()}@example.com`, name);
      const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: o.token })).json()) as { code: string };
      await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token });
      ids.push((await newRequest(h, o.token)).id);
      h.clock.advance(1000);
    }
    const list = (await (await h.req("GET", "/v1/keyholder/requests", { token: kh.token })).json()) as { id: string; ownerName: string }[];
    expect(list.map((x) => x.id)).toEqual(ids);
    expect(list.map((x) => x.ownerName)).toEqual(["Riya", "Dev"]);
  });
});
