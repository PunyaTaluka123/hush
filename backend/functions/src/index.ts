// Hush backend (SPEC §11). Every callable validates input, requires an
// idempotency_key, and changes incidents only through engine.applyTransition
// inside db.withWearer's transaction.
import { randomBytes, timingSafeEqual } from "node:crypto";
import { setGlobalOptions } from "firebase-functions/v2";
import { CallableRequest, HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onTaskDispatched } from "firebase-functions/v2/tasks";
import { FieldValue } from "firebase-admin/firestore";
import { db, EMULATOR, expireIn, Loaded, now, pinsRef, REGION, withIncident, withWearer } from "./db";
import { Contact, contactAck as ack, contactResolve as resolve, deviceEvent, EV, Msg, operatorResolve as opResolve, pin, processDue, REMOVAL_DELAY_S, ZERO_ID } from "./engine";
import { ARGON, makePins, PinDoc, proofMsg, validPins, verify } from "./pins";
import { deliver, logReceipt, TWILIO_SID, TWILIO_TOKEN, twilioSignatureOk } from "./deliver";

setGlobalOptions({ region: REGION, maxInstances: 20 });
const hot = { minInstances: EMULATOR ? 0 : 1 }; // S1: no cold start on the alarm path

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-hush";
const JOIN_BASE = process.env.HUSH_JOIN_BASE ?? `https://${PROJECT}.web.app/join`;
const CONSENT_VERSION = "pdpa-2026-09";
const KEY = /^[A-Za-z0-9-]{8,64}$/;
const E164 = /^\+[1-9]\d{7,14}$/;
const ID20 = /^[A-Za-z0-9]{20}$/;
const PROOF = /^[A-Za-z0-9+/]{43}=$/; // 32 bytes

// ---------------------------------------------------------------- helpers
type Data = Record<string, unknown>;
function need(cond: unknown, what: string): asserts cond {
  if (!cond) throw new HttpsError("invalid-argument", what);
}
function caller(req: CallableRequest<unknown>): { uid: string; d: Data } {
  if (!req.auth) throw new HttpsError("unauthenticated", "sign in first");
  const d = (req.data ?? {}) as Data;
  need(typeof d.idempotency_key === "string" && KEY.test(d.idempotency_key), "idempotency_key");
  return { uid: req.auth.uid, d };
}
const b64 = (b: Buffer) => b.toString("base64");
const str = (v: unknown, max: number) => typeof v === "string" && v.trim().length > 0 && v.length <= max;

async function pinCheck(l: Loaded, uid: string, proof: unknown, key: string, purpose: "cancel" | "disarm" | "config", incident: string) {
  need(typeof proof === "string" && PROOF.test(proof), "pin_proof");
  const p = await l.tx.get(pinsRef(uid));
  if (!p.exists) return "wrong" as const;
  return verify(p.data() as PinDoc, Buffer.from(proof, "base64"), proofMsg(key, purpose, incident));
}

interface Point { t: number; lat: number; lng: number; acc: number }
function points(v: unknown): Point[] {
  need(Array.isArray(v) && v.length <= 100, "points");
  return (v as Data[]).map((p) => {
    need(typeof p.t === "number" && typeof p.lat === "number" && typeof p.lng === "number" && typeof p.acc === "number" &&
      Math.abs(p.lat) <= 90 && Math.abs(p.lng) <= 180 && p.acc >= 0, "point");
    return { t: Math.round(p.t as number), lat: p.lat as number, lng: p.lng as number, acc: p.acc as number };
  });
}

// I6: stored only against incidents that are open (or DURESS_ACTIVE); otherwise dropped.
function storeLocations(l: Loaded, pts: Point[]) {
  for (const id of l.c.st.open) {
    if (!l.c.incidents[id]?.open) continue;
    for (const p of pts) l.tx.set(db.doc(`incidents/${id}/locations/${p.t}`), p);
  }
}

// ---------------------------------------------------------------- wearer
export const registerDevice = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.device_id === "string" && /^[A-Za-z0-9:_-]{4,40}$/.test(d.device_id), "device_id");
  const ref = db.doc(`devices/${d.device_id}`);
  return db.runTransaction(async (tx) => {
    const [s, i] = await Promise.all([tx.get(ref), tx.get(db.doc(`idem/${uid}_${d.idempotency_key}`))]);
    if (i.exists) return i.get("res");
    if (s.exists && s.get("owner") !== uid) throw new HttpsError("already-exists", "device is registered to another account");
    // A new bond means the device may have been erased and restarted seq at 1:
    // the generation keeps event de-duplication keys unique (I8).
    const res = { generation: ((s.get("generation") as number) ?? 0) + 1 };
    tx.set(ref, { owner: uid, fw: String(d.fw_version ?? ""), bonded_phone: String(d.bonded_phone_id ?? ""), generation: res.generation, registered_at: Date.now() }, { merge: true });
    tx.set(i.ref, { res, expire_at: expireIn(Date.now(), 7) });
    return res;
  });
});

export const reportDeviceEvents = onCall(hot, async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.device_id === "string" && Array.isArray(d.events) && d.events.length > 0 && d.events.length <= 64, "events");
  const bySeq = new Map<number, Data>();
  for (const e of d.events as Data[]) {
    need(Number.isInteger(e.device_seq) && (e.device_seq as number) > 0 && Number.isInteger(e.type) && (e.type as number) >= 1 &&
      (e.type as number) <= 9 && Number.isInteger(e.data ?? 0), "event");
    bySeq.set(e.device_seq as number, e);
  }
  const evs = [...bySeq.values()].sort((a, b) => (a.device_seq as number) - (b.device_seq as number));
  return withWearer(uid, { now: await now() }, async ({ c, tx }) => {
    const dev = await tx.get(db.doc(`devices/${d.device_id}`));
    if (!dev.exists || dev.get("owner") !== uid) throw new HttpsError("permission-denied", "device not registered to you");
    const refs = evs.map((e) => db.doc(`devevents/${d.device_id}_${dev.get("generation") ?? 1}_${e.device_seq}`));
    const seen = await tx.getAll(...refs);
    c.st.last_seen = c.now;
    let incident = ZERO_ID, haptic = 0, acked = 0;
    evs.forEach((e, i) => {
      const type = e.type as number;
      acked = Math.max(acked, e.device_seq as number);
      if (seen[i].exists) { // BLE resend or network retry: same answer, no new effects (I8)
        incident = seen[i].get("incident") ?? incident;
        haptic = seen[i].get("haptic") || haptic;
        return;
      }
      const inc = deviceEvent(c, type);
      const trigger = type === EV.TRIGGER_HOLD || type === EV.TRIGGER_DOUBLE_LONG;
      const h = trigger && inc ? (c.contacts.length ? 2 : 3) : 0; // HAPTIC(2) delivered / (3) nobody to alert
      if (inc) { incident = inc.id; c.log(inc, "device_event", { type, seq: e.device_seq, data: e.data ?? 0 }); }
      haptic = h || haptic;
      tx.set(refs[i], {
        uid, device: d.device_id, seq: e.device_seq, type, data: e.data ?? 0, observed_at: e.observed_at ?? null,
        received_at: c.now, incident: inc?.id ?? null, haptic: h, expire_at: expireIn(c.now, 365),
      });
    });
    tx.update(dev.ref, { last_seq: Math.max(acked, (dev.get("last_seq") as number) ?? 0) });
    return { acked_seq: acked, incident_id: incident, haptic };
  });
});

const WEAR = ["UNKNOWN", "DOCKED", "WORN", "OFF_BODY"];

export const heartbeat = onCall(async (req) => {
  const { uid, d } = caller(req);
  const pts = d.location ? points([d.location]) : [];
  await withWearer(uid, { now: await now() }, ({ c, tx }) => {
    const st = c.st;
    st.last_seen = c.now;
    st.armed = d.armed === true;
    if (typeof d.wear_state === "number" && WEAR[d.wear_state]) st.wear = WEAR[d.wear_state];
    if (d.ble_connected === true) {
      Object.assign(st, { ble_connected: true, ble_down_since: null, link_lost_sent: false });
    } else if (st.ble_connected || st.ble_down_since === null) {
      Object.assign(st, { ble_connected: false, ble_down_since: c.now });
    }
    st.link_watch = !st.ble_connected && st.armed && st.wear === "WORN";
    storeLocations({ c, tx }, pts);
    tx.set(db.doc(`users/${uid}/private/device`), {
      device_battery_mv: Number(d.device_battery_mv ?? 0), phone_battery_pct: Number(d.phone_battery_pct ?? 0), at: c.now,
    });
  });
  return { ok: true };
});

export const pushLocation = onCall(async (req) => {
  const { uid, d } = caller(req);
  const pts = points(d.points);
  await withWearer(uid, { now: await now() }, (l) => {
    l.c.st.last_seen = l.c.now;
    storeLocations(l, pts);
  });
  return { ok: true }; // same answer whether stored or dropped (I1)
});

// Fixed-size request (I1): idempotency_key 36, incident_id 20, purpose 6, proof 44.
export const submitPin = onCall(hot, async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.idempotency_key === "string" && d.idempotency_key.length === 36, "idempotency_key");
  need(typeof d.incident_id === "string" && ID20.test(d.incident_id), "incident_id");
  need(d.purpose === "cancel" || d.purpose === "disarm", "purpose");
  const key = d.idempotency_key, incident = d.incident_id, purpose = d.purpose;
  await withWearer(uid, { now: await now(), idem: key, extra: incident === ZERO_ID ? [] : [incident] }, async (l) => {
    l.c.st.last_seen = l.c.now;
    pin(l.c, await pinCheck(l, uid, d.proof, key, purpose, incident), purpose, incident);
    return { ok: true };
  });
  return { ok: true }; // always, whatever the PIN was
});

export const pinParams = onCall(async (req) => {
  caller(req);
  const p = await pinsRef(req.auth!.uid).get();
  if (!p.exists) return { set: false };
  return { set: true, salt: b64(p.get("salt")), nonce: b64(p.get("nonce")), argon: ARGON };
});

// First setup, or a change (needs the current PIN; a duress PIN here escalates
// silently and the change still goes through, exactly like a real one).
export const setPins = onCall(async (req) => {
  const { uid, d } = caller(req);
  // Neutral field names: nothing on the phone or on the wire says which PIN is which kind (SPEC §7.3).
  need(validPins(d.pin, d.pin2), "PINs must be 4-6 digits, different, and the same length");
  const made = await makePins(d.pin as string, d.pin2 as string);
  const key = d.idempotency_key as string;
  return withWearer(uid, { now: await now(), idem: key }, async (l) => {
    const existing = await l.tx.get(pinsRef(uid));
    if (existing.exists && !pin(l.c, await pinCheck(l, uid, d.current_proof, key, "config", ZERO_ID), "config", ZERO_ID)) {
      return { ok: false, error: "incorrect_pin" };
    }
    const hashKey = randomBytes(32);
    l.tx.set(pinsRef(uid), { ...made, hash_key: hashKey, set_at: l.c.now });
    return { ok: true, salt: b64(made.salt), nonce: b64(made.nonce), hash_key: b64(hashKey), argon: ARGON };
  });
});

// Changing who gets alerted or how long the removal delay is needs the PIN:
// the attacker may hold the unlocked phone (SPEC §2).
export const updateSettings = onCall(async (req) => {
  const { uid, d } = caller(req);
  const delay = d.removal_delay_s;
  need(Number.isInteger(delay) && (delay as number) >= REMOVAL_DELAY_S.min && (delay as number) <= REMOVAL_DELAY_S.max, "removal_delay_s 30..120");
  return withWearer(uid, { now: await now(), idem: d.idempotency_key as string }, async (l) => {
    if (!pin(l.c, await pinCheck(l, uid, d.pin_proof, d.idempotency_key as string, "config", ZERO_ID), "config", ZERO_ID)) return { ok: false, error: "incorrect_pin" };
    l.tx.set(db.doc(`users/${uid}`), { settings: { removal_delay_s: delay } }, { merge: true });
    return { ok: true };
  });
});

export const inviteContact = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.phone === "string" && E164.test(d.phone), "phone (E.164)");
  need(str(d.name, 60), "name");
  return withWearer(uid, { now: await now(), idem: d.idempotency_key as string }, async ({ c, tx }) => {
    const links = await tx.get(db.collection(`contacts/${uid}/links`));
    const existing = links.docs.find((x) => x.id === d.phone);
    if (!existing && links.size >= 5) throw new HttpsError("resource-exhausted", "at most 5 contacts");
    const token = randomBytes(12).toString("base64url");
    tx.set(db.doc(`contacts/${uid}/links/${d.phone}`), {
      phone: d.phone, name: (d.name as string).trim(), token, invited_at: c.now,
      state: existing?.get("state") === "active" ? "active" : "invited",
      contact_uid: existing?.get("contact_uid") ?? null, priority: existing?.get("priority") ?? links.size + 1,
    });
    const msg: Msg = {
      id: `invite_${uid}_${(d.phone as string).slice(1)}_${token.slice(0, 8)}`, incident: null, kind: "invite", channel: "sms",
      attempt: 1, to_uid: null, to_phone: d.phone as string, priority: "normal", cost_usd: 0.08,
      text: `${c.name} asked you to be one of their emergency contacts on Hush. If they raise an alarm, Hush will alert you. Accept here: ${JOIN_BASE}?w=${uid}&t=${token}`,
    };
    c.outbox.push(msg);
    return { ok: true };
  });
});

export const removeContact = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.phone === "string" && E164.test(d.phone), "phone");
  return withWearer(uid, { now: await now(), idem: d.idempotency_key as string }, async (l) => {
    const link = await l.tx.get(db.doc(`contacts/${uid}/links/${d.phone}`));
    if (!pin(l.c, await pinCheck(l, uid, d.pin_proof, d.idempotency_key as string, "config", ZERO_ID), "config", ZERO_ID)) return { ok: false, error: "incorrect_pin" };
    const gone = link.get("contact_uid");
    for (const id of l.c.st.open) { // a removed contact loses access to open incidents too
      const inc = l.c.incidents[id];
      if (inc && gone) { inc.contacts = inc.contacts.filter((u) => u !== gone); l.c.dirty.add(id); }
    }
    l.c.contacts = l.c.contacts.filter((x) => x.phone !== d.phone);
    l.tx.delete(link.ref);
    return { ok: true };
  });
});

// ---------------------------------------------------------------- contacts
export const acceptInvite = onCall(async (req) => {
  const { uid, d } = caller(req);
  const phone = req.auth!.token.phone_number;
  need(phone, "sign in with your phone number");
  need(typeof d.wearer === "string" && typeof d.token === "string", "wearer, token");
  const ref = db.doc(`contacts/${d.wearer}/links/${phone}`);
  await db.runTransaction(async (tx) => {
    const s = await tx.get(ref);
    const tok = Buffer.from(String(s.get("token") ?? "")), got = Buffer.from(d.token as string);
    if (!s.exists || tok.length !== got.length || !timingSafeEqual(tok, got)) throw new HttpsError("not-found", "invitation not found");
    if (s.get("state") === "active" && s.get("contact_uid") === uid) return;
    // Accepting is the contact's consent to receive alerts (PDPA, SPEC §13).
    tx.update(ref, { state: "active", contact_uid: uid, accepted_at: Date.now(), consent: { version: CONSENT_VERSION, at: Date.now() } });
    tx.set(db.doc(`users/${uid}`), { roles: { contact: true }, phone }, { merge: true });
  });
  return { ok: true };
});

function asContact(l: Loaded, uid: string): Contact {
  const who = l.c.contacts.find((x) => x.uid === uid);
  if (!who) throw new HttpsError("permission-denied", "not an active contact for this wearer");
  return who;
}

export const contactAck = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.incident_id === "string" && ID20.test(d.incident_id), "incident_id");
  return withIncident(d.incident_id, { now: await now(), idem: d.idempotency_key as string, uid }, (l, inc) => {
    ack(l.c, inc, asContact(l, uid));
    return { ok: true };
  });
});

export const contactResolve = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.incident_id === "string" && ID20.test(d.incident_id), "incident_id");
  return withIncident(d.incident_id, { now: await now(), idem: d.idempotency_key as string, uid }, (l, inc) => ({
    result: resolve(l.c, inc, asContact(l, uid), d.spoke_by_voice === true, String(d.typed_name ?? "")),
  }));
});

// D2: a Hush operator closes DURESS after a contact's voice confirmation plus
// their own callback, or after 24 h with a note.
export const operatorResolve = onCall(async (req) => {
  const { uid, d } = caller(req);
  if (req.auth!.token.operator !== true) throw new HttpsError("permission-denied", "operators only");
  need(typeof d.incident_id === "string" && ID20.test(d.incident_id) && str(d.note, 2000), "incident_id, note");
  return withIncident(d.incident_id, { now: await now(), idem: d.idempotency_key as string, uid }, (l, inc) => ({
    ok: opResolve(l.c, inc, uid, d.note as string, d.callback_done === true),
  }));
});

export const notificationReceipt = onCall(async (req) => {
  const { uid, d } = caller(req);
  need(typeof d.msg === "string" && d.msg.length <= 120, "msg");
  const ref = db.doc(`outbox/${d.msg}`);
  const s = await ref.get();
  if (!s.exists || s.get("to_uid") !== uid) throw new HttpsError("not-found", "no such message");
  await ref.update({ receipt: { status: "delivered", at: Date.now() } });
  if (s.get("incident")) await logReceipt(s.get("incident"), s.id, s.data() as Msg, { receipt: "delivered" });
  return { ok: true };
});

// ---------------------------------------------------------------- delivery
export const deliverOutbox = onDocumentCreated({ document: "outbox/{id}", secrets: [TWILIO_SID, TWILIO_TOKEN], ...hot }, async (e) => {
  if (e.data) await deliver(e.params.id, e.data.data() as Msg & { status: string });
});

export const twilioStatus = onRequest({ secrets: [TWILIO_TOKEN] }, async (req, res) => {
  const url = `https://${req.get("host")}${req.originalUrl}`;
  if (!EMULATOR && !twilioSignatureOk(url, req.body ?? {}, req.get("X-Twilio-Signature") ?? "", TWILIO_TOKEN.value())) {
    res.status(403).end();
    return;
  }
  const id = String(req.query.m ?? "");
  const s = id ? await db.doc(`outbox/${id}`).get() : null;
  if (s?.exists) {
    const status = String(req.body?.MessageStatus ?? req.body?.CallStatus ?? "unknown");
    await s.ref.update({ receipt: { status, at: Date.now() } });
    if (s.get("incident")) await logReceipt(s.get("incident"), id, s.data() as Msg, { receipt: status });
  }
  res.status(204).end();
});

// ---------------------------------------------------------------- timers
export const timerFire = onTaskDispatched({ retryConfig: { maxAttempts: 5, minBackoffSeconds: 5 } }, async (req) => {
  const id = String((req.data as Data)?.incident ?? "");
  if (id) await withIncident(id, { now: Date.now() }, ({ c }, inc) => processDue(c, inc));
});

// Backstop for timers plus the LINK_LOST check. Cloud Scheduler's minimum is
// 1 minute (SPEC says 30 s); exact due times come from Cloud Tasks.
async function sweep(t: number) {
  const open = await db.collection("incidents").where("open", "==", true).get();
  for (const d of open.docs) await withIncident(d.id, { now: t }, ({ c }, inc) => processDue(c, inc));
  const watch = await db.collectionGroup("private").where("link_watch", "==", true).get();
  for (const d of watch.docs) {
    const uid = d.ref.parent.parent!.id;
    await withWearer(uid, { now: t }, ({ c }) => {
      const st = c.st;
      if (!st.link_watch || st.link_lost_sent || st.ble_down_since === null || t - st.ble_down_since <= 90_000) return;
      st.link_lost_sent = true;
      // wearer only, data-only push (the app shows it with vibration, never sound: I4)
      c.outbox.push({
        id: `linklost_${uid}_${st.ble_down_since}`, incident: null, kind: "link_lost", channel: "push", attempt: 1,
        to_uid: uid, to_phone: null, priority: "high", cost_usd: 0,
        text: "Hush is not connected to your phone. Alarms can't reach your contacts until it reconnects.",
      });
    });
  }
}
export const sweepTimers = onSchedule({ schedule: "every 1 minutes", retryCount: 0 }, () => sweep(Date.now()));

// SPEC §13: locations deleted 30 days after the incident closes.
export const purgeLocations = onSchedule("every 24 hours", async () => {
  const due = await db.collection("incidents").where("purge_after", "<=", Date.now()).get();
  for (const d of due.docs) {
    await db.recursiveDelete(d.ref.collection("locations"));
    await d.ref.update({ purge_after: FieldValue.delete() });
  }
});

// ---------------------------------------------------------------- tests (emulator only)
export const testClock = onRequest(async (req, res) => {
  if (!EMULATOR) { res.status(404).end(); return; }
  const t = Number(req.query.now);
  await db.doc("_test/clock").set({ now: t });
  await sweep(t);
  res.json({ ok: true, now: t });
});

