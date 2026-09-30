// Firestore side of the engine: load a wearer's world, run pure engine code,
// commit everything (incidents, audit, outbox, state) in one transaction.
import { initializeApp } from "firebase-admin/app";
import { FieldValue, Firestore, Timestamp, Transaction, getFirestore } from "firebase-admin/firestore";
import { getFunctions } from "firebase-admin/functions";
import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { Contact, Ctx, Incident, REMOVAL_DELAY_S, newWearerState, WearerState } from "./engine";

initializeApp();
export const db: Firestore = getFirestore();
export const EMULATOR = process.env.FUNCTIONS_EMULATOR === "true";
export const REGION = "asia-southeast1";
const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-hush";
export const LINK_BASE = process.env.HUSH_LINK_BASE ?? `https://${PROJECT}.web.app/i/`;
const DAY = 24 * 3600_000;

// Real time in production. In the emulator, scenario tests set _test/clock.
export async function now(): Promise<number> {
  if (!EMULATOR) return Date.now();
  const s = await db.doc("_test/clock").get();
  return s.exists ? (s.get("now") as number) : Date.now();
}

export const expireIn = (from: number, days: number) => Timestamp.fromMillis(from + days * DAY);
export const stateRef = (uid: string) => db.doc(`users/${uid}/private/state`);
export const pinsRef = (uid: string) => db.doc(`users/${uid}/private/pins`);
export const incRef = (id: string) => db.doc(`incidents/${id}`);

export interface Loaded { c: Ctx; tx: Transaction }

// Runs fn inside a transaction with the wearer's world loaded. fn may do its
// own tx reads first; all engine writes are committed after it returns.
// With `idem`, a repeated idempotency key returns the first response (I8).
export async function withWearer<T>(
  uid: string,
  opts: { now: number; idem?: string; extra?: string[] },
  fn: (l: Loaded) => Promise<T> | T,
): Promise<T> {
  const { c, res } = await db.runTransaction(async (tx) => {
    const idemRef = opts.idem ? db.doc(`idem/${uid}_${opts.idem}`) : null;
    if (idemRef) {
      const s = await tx.get(idemRef);
      if (s.exists) return { c: null, res: s.get("res") as T };
    }
    const [user, stSnap, links] = await Promise.all([
      tx.get(db.doc(`users/${uid}`)),
      tx.get(stateRef(uid)),
      tx.get(db.collection(`contacts/${uid}/links`).where("state", "==", "active")),
    ]);
    const st: WearerState = { ...newWearerState(), ...(stSnap.data() as Partial<WearerState>) };
    const ids = [...new Set([...st.open, st.last_cancelled, ...(opts.extra ?? [])].filter((x): x is string => !!x))];
    const incs = ids.length ? await tx.getAll(...ids.map(incRef)) : [];
    const incidents: Record<string, Incident> = {};
    for (const s of incs) if (s.exists) incidents[s.id] = s.data() as Incident;
    const contacts: Contact[] = links.docs
      .map((d) => ({ uid: d.get("contact_uid"), phone: d.id, name: d.get("name") }))
      .filter((x) => x.uid);
    const settings = user.get("settings") ?? {};
    const c = new Ctx(
      opts.now, uid, user.get("name") ?? "Your contact", settings.tz ?? "Asia/Singapore",
      settings.removal_delay_s ?? REMOVAL_DELAY_S.def, st, contacts, incidents, LINK_BASE,
    );
    const res = await fn({ c, tx });
    commit(tx, c);
    if (idemRef) tx.set(idemRef, { res: res ?? null, expire_at: expireIn(c.now, 7) });
    return { c, res };
  });
  if (c) await scheduleTimers(c.timers);
  return res;
}

function commit(tx: Transaction, c: Ctx) {
  for (const id of c.dirty) {
    const inc = c.incidents[id];
    const closedFor = inc.closed_at !== null && !inc.open;
    tx.set(incRef(id), {
      ...inc,
      purge_after: closedFor ? inc.closed_at! + 30 * DAY : null, // locations (SPEC §13)
      expire_at: closedFor ? expireIn(inc.closed_at!, 365) : null, // audit kept 1 year
    });
  }
  tx.set(stateRef(c.wearer), c.st);
  for (const a of c.audit) tx.set(db.collection(`incidents/${a.incident}/events`).doc(), { ...a, expire_at: expireIn(c.now, 365) });
  for (const m of c.outbox) tx.set(db.doc(`outbox/${m.id}`), { ...m, status: "pending", created_at: c.now, expire_at: expireIn(c.now, 365) });
}

// Cloud Tasks gives each timer its exact due time; the 1-minute sweep is the
// backstop. In the emulator the scenario clock drives the sweep instead.
async function scheduleTimers(timers: { incident: string; at: number }[]) {
  if (EMULATOR || !timers.length) return;
  const q = getFunctions().taskQueue(`locations/${REGION}/functions/timerFire`);
  await Promise.all(
    timers.map((t) =>
      q.enqueue({ incident: t.incident }, { scheduleTime: new Date(t.at + 250), id: `${t.incident}-${t.at}` }).catch((e) => {
        if (e?.code !== "functions/task-already-exists") logger.error("enqueue timer failed", { incident: t.incident, err: String(e) });
      }),
    ),
  );
}

// Loads an incident's wearer world for contact / timer / operator actions.
export async function withIncident<T>(
  incidentId: string,
  opts: { now: number; idem?: string; uid?: string },
  fn: (l: Loaded, inc: Incident) => Promise<T> | T,
): Promise<T> {
  const snap = await incRef(incidentId).get();
  if (!snap.exists) throw new HttpsError("not-found", "no such incident");
  const wearer = snap.get("wearer") as string;
  return withWearer(wearer, { now: opts.now, idem: opts.idem && opts.uid ? `${opts.uid}_${opts.idem}` : undefined, extra: [incidentId] },
    (l) => fn(l, l.c.incidents[incidentId]));
}

export const serverTime = FieldValue.serverTimestamp;
