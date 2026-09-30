// Outbox delivery (I3: every message to a contact leaves from the server).
// The engine writes outbox/{id} inside its transaction; this trigger sends it
// once and logs the result, and delivery receipts are added to the incident's
// audit log as they arrive (SPEC §13).
import { createHmac, timingSafeEqual } from "node:crypto";
import { getMessaging, Message } from "firebase-admin/messaging";
import { logger } from "firebase-functions";
import { defineSecret, defineString } from "firebase-functions/params";
import { db, EMULATOR, expireIn } from "./db";
import type { Msg } from "./engine";

export const TWILIO_SID = defineSecret("TWILIO_ACCOUNT_SID");
export const TWILIO_TOKEN = defineSecret("TWILIO_AUTH_TOKEN");
export const TWILIO_FROM = defineString("TWILIO_FROM", { default: "" });
export const PUBLIC_URL = defineString("PUBLIC_FUNCTIONS_URL", { default: "" }); // for Twilio status callbacks
// Until Apple grants the Critical Alerts entitlement (SPEC §9), send time-sensitive instead.
export const IOS_CRITICAL = defineString("IOS_CRITICAL_ALERTS", { default: "false" });

// SPEC §10: reporting to authorities is out of scope for v1.
export interface AuthorityChannel { report(m: Msg): Promise<string> }
// TODO(owner): Singapore Police emergency SMS 70999, only after a protocol is agreed with SPF.
export const authority: AuthorityChannel = { report: async () => "disabled" };

export async function deliver(id: string, m: Msg & { status: string }) {
  if (m.status !== "pending") return;
  let status = "sent", provider_id: string | null = null, error: string | null = null;
  try {
    if (m.channel === "authority") provider_id = await authority.report(m);
    else if (EMULATOR) provider_id = `fake-${m.channel}`;
    else if (m.channel === "push") ({ status, provider_id } = await push(m));
    else provider_id = await twilio(id, m);
  } catch (e) {
    status = "failed";
    error = String(e);
    logger.error("delivery failed", { id, channel: m.channel, err: error });
  }
  try {
    await db.doc(`outbox/${id}`).update({ status, provider_id, error, sent_at: Date.now() });
  } catch (e) {
    if ((e as { code?: number }).code === 5) return; // doc deleted meanwhile (emulator resets); nothing to record
    throw e;
  }
  if (m.incident) await logReceipt(m.incident, id, m, { status, provider_id, error });
}

export async function logReceipt(incident: string, id: string, m: Pick<Msg, "channel" | "to_uid" | "kind">, extra: Record<string, unknown>) {
  await db.collection(`incidents/${incident}/events`).add({
    ...extra, kind: "notify", msg: id, channel: m.channel, msg_kind: m.kind, to: m.to_uid, at: Date.now(),
    expire_at: expireIn(Date.now(), 365),
  });
}

// ---------------------------------------------------------------- push
async function push(m: Msg): Promise<{ status: string; provider_id: string | null }> {
  const uid = m.to_uid!;
  const tokens = await db.collection(`push_tokens/${uid}/tokens`).get();
  if (tokens.empty) return { status: "no_app", provider_id: null }; // SMS / voice still go
  const critical = m.priority === "critical";
  const iosCritical = IOS_CRITICAL.value() === "true";
  const wearer = m.kind === "link_lost"; // never a sound on the wearer's phone (I4)
  const msgs: Message[] = tokens.docs.map((t) => ({
    token: t.id,
    // Data-only on Android: the contact app owns the alarm (hush_emergency channel, USAGE_ALARM, full screen).
    data: { msg: m.id, kind: m.kind, incident: m.incident ?? "", text: m.text, priority: m.priority },
    android: { priority: m.priority === "normal" ? "normal" : "high", ttl: 3600_000 },
    apns: {
      headers: { "apns-priority": m.priority === "normal" ? "5" : "10", "apns-push-type": "alert" },
      payload: {
        aps: {
          alert: { title: critical ? "Hush emergency" : "Hush", body: m.text },
          ...(wearer ? {} : { sound: critical && iosCritical ? { critical: true, name: "default", volume: 1 } : "default" }),
          "interruption-level": critical ? (iosCritical ? "critical" : "time-sensitive") : "active",
        },
      },
    },
  }));
  const res = await getMessaging().sendEach(msgs);
  await Promise.all(
    res.responses.map((r, i) =>
      r.error?.code === "messaging/registration-token-not-registered" ? tokens.docs[i].ref.delete() : null),
  );
  const ok = res.responses.find((r) => r.success);
  return { status: ok ? "sent" : "failed", provider_id: ok?.messageId ?? null };
}

// ---------------------------------------------------------------- Twilio (REST, no SDK)
async function twilio(id: string, m: Msg): Promise<string> {
  const sid = TWILIO_SID.value();
  const cb = PUBLIC_URL.value() ? `${PUBLIC_URL.value()}/twilioStatus?m=${encodeURIComponent(id)}` : "";
  const body = new URLSearchParams({ To: m.to_phone!, From: TWILIO_FROM.value() });
  if (cb) body.set("StatusCallback", cb);
  if (m.channel === "sms") body.set("Body", m.text);
  else body.set("Twiml", `<Response><Say loop="2">${escapeXml(m.text)}</Say></Response>`);
  const r = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/${m.channel === "sms" ? "Messages" : "Calls"}.json`, {
    method: "POST",
    headers: { Authorization: "Basic " + Buffer.from(`${sid}:${TWILIO_TOKEN.value()}`).toString("base64") },
    body,
  });
  const j = (await r.json()) as { sid?: string; message?: string };
  if (!r.ok || !j.sid) throw new Error(`twilio ${r.status}: ${j.message ?? ""}`);
  return j.sid;
}

const escapeXml = (s: string) => s.replace(/[<>&'"]/g, (ch) => `&#${ch.charCodeAt(0)};`);

// Twilio request signature: base64(HMAC-SHA1(token, url + sorted key/value pairs)).
export function twilioSignatureOk(url: string, params: Record<string, string>, sig: string, token: string): boolean {
  const data = url + Object.keys(params).sort().map((k) => k + params[k]).join("");
  const want = createHmac("sha1", token).update(data).digest();
  const got = Buffer.from(sig ?? "", "base64");
  return got.length === want.length && timingSafeEqual(got, want);
}
