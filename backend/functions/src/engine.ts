// Incident state machine (SPEC §6). Pure: it mutates an in-memory Ctx that
// db.ts loads and commits inside one Firestore transaction, so every rule
// here runs the same way whether a device event, a PIN, a contact or a timer
// caused it.
import { randomBytes } from "node:crypto";

export const REOPEN_WINDOW_MS = 10 * 60_000; // CANCELLED -> DURESS_ACTIVE window
export const OFFLINE_MS = 90_000; // I9
export const CALL_REPEAT_MS = 120_000;
export const CALL_REPEATS = 3; // after the first call
export const WRONG_PIN_LIMIT = 3; // D1
export const OPERATOR_ONLY_AFTER_MS = 24 * 3600_000; // D2 fallback
export const REMOVAL_DELAY_S = { min: 30, max: 120, def: 60 }; // D3
// D5: Twilio spend cap per incident. The first loud / DURESS message to every
// contact always goes; the cap only suppresses repeats and informational SMS.
export const BUDGET_USD = 10;
export const COST_USD: Record<Channel, number> = { push: 0, sms: 0.08, voice: 0.25, authority: 0 };
export const ZERO_ID = "00000000000000000000";

export type State = "NEW" | "OPEN_QUIET" | "OPEN_LOUD" | "CANCELLED" | "RESOLVED_BY_CONTACT" | "DURESS_ACTIVE" | "RESOLVED";
export type IType = "TONGUE" | "REMOVAL" | "DURESS";
export type Kind = "heads_up" | "loud" | "duress" | "cancelled" | "offline" | "resolved" | "link_lost" | "invite";
export type Channel = "push" | "sms" | "voice" | "authority";
export type PinResult = "real" | "duress" | "wrong";

export interface Incident {
  id: string;
  wearer: string;
  wearer_name: string; // for the contact's "type their first name" step (I10)
  type: IType;
  state: State;
  open: boolean; // OPEN_* or DURESS_ACTIVE (sweep query)
  opened_at: number;
  updated_at: number;
  closed_at: number | null;
  loud_at: number | null;
  next_call_at: number | null;
  call_round: number;
  cancelled_at: number | null;
  duress_at: number | null;
  offline_at: number | null; // when the wearer's phone went silent (I9)
  contacts: string[]; // contact uids allowed to read (firestore.rules)
  acked: string[];
  responders: Record<string, { name: string; at: number }>;
  duress_confirmed_by: { uid: string; name: string; at: number } | null;
  removal_delay_s: number;
  spend_usd: number;
  summary: string; // latest alert text, shown by the contact app (the wearer's client never reads incidents)
}

export interface Contact { uid: string; phone: string; name: string }

// users/{uid}/private/state: server-only, because incident pointers would
// tell the wearer's client whether a PIN was real or duress (I1).
export interface WearerState {
  last_seen: number;
  open: string[];
  last_cancelled: string | null;
  disarmed: boolean;
  wrong_pins: number;
  armed: boolean;
  wear: string;
  ble_connected: boolean;
  ble_down_since: number | null;
  link_watch: boolean;
  link_lost_sent: boolean;
}

export const newWearerState = (): WearerState => ({
  last_seen: 0, open: [], last_cancelled: null, disarmed: false, wrong_pins: 0, armed: false,
  wear: "UNKNOWN", ble_connected: false, ble_down_since: null, link_watch: false, link_lost_sent: false,
});

export interface Msg {
  id: string;
  incident: string | null;
  kind: Kind;
  channel: Channel;
  attempt: number;
  to_uid: string | null;
  to_phone: string | null;
  priority: "critical" | "high" | "normal";
  text: string;
  cost_usd: number;
}

export interface Audit { incident: string; at: number; kind: string; [k: string]: unknown }

export class Ctx {
  dirty = new Set<string>();
  audit: Audit[] = [];
  outbox: Msg[] = [];
  timers: { incident: string; at: number }[] = [];
  constructor(
    public now: number,
    public wearer: string,
    public name: string,
    public tz: string,
    public removalDelayS: number,
    public st: WearerState,
    public contacts: Contact[],
    public incidents: Record<string, Incident>,
    public linkBase: string,
  ) {}
  log(inc: Incident, kind: string, extra: Record<string, unknown> = {}) {
    this.audit.push({ ...extra, incident: inc.id, at: this.now, kind });
  }
}

export const isOpen = (i: Incident) => i.state === "OPEN_QUIET" || i.state === "OPEN_LOUD";
const live = (i: Incident) => isOpen(i) || i.state === "DURESS_ACTIVE";

export function newId(): string {
  const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  return [...randomBytes(20)].map((b) => a[b % 62]).join("");
}

// ------------------------------------------------------------ transitions
const ALLOWED: Record<State, State[]> = {
  NEW: ["OPEN_QUIET", "OPEN_LOUD", "DURESS_ACTIVE"],
  OPEN_QUIET: ["OPEN_LOUD", "CANCELLED", "DURESS_ACTIVE", "RESOLVED_BY_CONTACT"],
  OPEN_LOUD: ["CANCELLED", "DURESS_ACTIVE", "RESOLVED_BY_CONTACT"],
  CANCELLED: ["DURESS_ACTIVE"],
  DURESS_ACTIVE: ["RESOLVED"],
  RESOLVED_BY_CONTACT: [],
  RESOLVED: [],
};

// The only place an incident changes state. Appends to the audit log and
// queues the contact messages for the new state.
export function applyTransition(c: Ctx, inc: Incident, to: State, reason: string) {
  const from = inc.state;
  if (!ALLOWED[from].includes(to)) throw new Error(`illegal transition ${from} -> ${to}`);
  inc.state = to;
  inc.updated_at = c.now;
  c.dirty.add(inc.id);
  c.log(inc, "transition", { from, to, reason });
  const st = c.st;
  switch (to) {
    case "OPEN_QUIET":
      alert(c, inc, "heads_up");
      break;
    case "OPEN_LOUD":
      inc.loud_at = null;
      alert(c, inc, "loud");
      startCalls(c, inc);
      break;
    case "DURESS_ACTIVE":
      inc.type = "DURESS";
      inc.duress_at = c.now;
      inc.loud_at = null;
      inc.closed_at = null;
      if (!st.open.includes(inc.id)) st.open.push(inc.id);
      if (st.last_cancelled === inc.id) st.last_cancelled = null;
      alert(c, inc, "duress");
      startCalls(c, inc);
      break;
    case "CANCELLED":
      inc.cancelled_at = c.now;
      st.last_cancelled = inc.id;
      close(c, inc);
      alert(c, inc, "cancelled");
      break;
    case "RESOLVED_BY_CONTACT":
    case "RESOLVED":
      close(c, inc);
      alert(c, inc, "resolved");
      break;
  }
  inc.open = live(inc);
  if (inc.open) nextTimer(c, inc);
}

function close(c: Ctx, inc: Incident) {
  inc.closed_at = c.now;
  inc.loud_at = inc.next_call_at = null;
  c.st.open = c.st.open.filter((id) => id !== inc.id);
}

function create(c: Ctx, type: IType, to: State, reason: string): Incident {
  const inc: Incident = {
    id: newId(), wearer: c.wearer, wearer_name: c.name, type, state: "NEW", open: true,
    opened_at: c.now, updated_at: c.now, closed_at: null, loud_at: null, next_call_at: null, call_round: 0,
    cancelled_at: null, duress_at: null, offline_at: null, contacts: c.contacts.map((x) => x.uid),
    acked: [], responders: {}, duress_confirmed_by: null, removal_delay_s: c.removalDelayS, spend_usd: 0, summary: "",
  };
  c.incidents[inc.id] = inc;
  c.st.open.push(inc.id);
  c.st.wrong_pins = 0;
  if (type === "REMOVAL") inc.loud_at = c.now + c.removalDelayS * 1000;
  applyTransition(c, inc, to, reason);
  return inc;
}

// ------------------------------------------------------------ inputs
export const EV = { TRIGGER_HOLD: 1, TRIGGER_DOUBLE_LONG: 2, STUCK_HIGH: 3, REMOVED_WHILE_ARMED: 4, WORN: 5, DOCKED: 6, BATTERY_LOW: 7, SENSOR_FAULT: 8, BOOT: 9 };

// One device event (already de-duplicated by device seq). Returns the incident it touched.
export function deviceEvent(c: Ctx, type: number): Incident | null {
  const open = c.st.open.map((id) => c.incidents[id]).filter((i) => i && isOpen(i));
  switch (type) {
    case EV.TRIGGER_HOLD:
    case EV.TRIGGER_DOUBLE_LONG: {
      const removal = open.find((i) => i.type === "REMOVAL");
      if (removal) {
        removal.type = "TONGUE";
        c.dirty.add(removal.id);
        if (removal.state === "OPEN_QUIET") applyTransition(c, removal, "OPEN_LOUD", "trigger");
        else c.log(removal, "trigger_again");
        return removal;
      }
      const tongue = open.find((i) => i.type === "TONGUE");
      if (tongue) { c.log(tongue, "trigger_again"); return tongue; }
      return create(c, "TONGUE", "OPEN_LOUD", "trigger");
    }
    case EV.REMOVED_WHILE_ARMED:
      if (c.st.disarmed) return null; // wearer disarmed with a PIN (e.g. to eat)
      if (open.length) { c.log(open[0], "removed_again"); return open[0]; }
      return create(c, "REMOVAL", "OPEN_QUIET", "removed");
    case EV.WORN:
      c.st.wear = "WORN";
      c.st.disarmed = false;
      return null;
    case EV.DOCKED:
      c.st.wear = "DOCKED";
      c.st.armed = false;
      c.st.disarmed = false;
      return null;
    default:
      return null;
  }
}

// Target of a PIN: the newest OPEN_* incident, else one cancelled < 10 min ago.
function pinTarget(c: Ctx, incidentId: string): Incident | null {
  const byId = c.incidents[incidentId];
  if (byId && byId.wearer === c.wearer && (isOpen(byId) || byId.state === "CANCELLED")) return byId;
  const open = c.st.open.map((id) => c.incidents[id]).filter((i) => i && isOpen(i));
  if (open.length) return open.sort((a, b) => b.opened_at - a.opened_at)[0];
  const last = c.st.last_cancelled ? c.incidents[c.st.last_cancelled] : null;
  return last && last.state === "CANCELLED" ? last : null;
}

// Every PIN prompt: cancel (incident screen), disarm, or a PIN-protected setting.
// Returns whether the requested action may proceed. The wearer's phone is never
// told the result (submitPin always answers {ok:true}).
export function pin(c: Ctx, result: PinResult, purpose: "cancel" | "disarm" | "config", incidentId: string): boolean {
  const inc = pinTarget(c, incidentId);
  if (inc) c.log(inc, "pin", { result, purpose }); // audit is server-only (firestore.rules)
  if (result === "duress") {
    c.st.wrong_pins = 0;
    const reopenable = inc && (isOpen(inc) || (inc.state === "CANCELLED" && c.now - (inc.cancelled_at ?? 0) <= REOPEN_WINDOW_MS));
    if (reopenable) applyTransition(c, inc, "DURESS_ACTIVE", `pin:${purpose}`);
    else create(c, "DURESS", "DURESS_ACTIVE", `pin:${purpose}`);
    if (purpose === "disarm") c.st.disarmed = true; // looks exactly like a real disarm
    return true;
  }
  if (result === "real") {
    c.st.wrong_pins = 0;
    if (purpose === "cancel" && inc && isOpen(inc)) applyTransition(c, inc, "CANCELLED", "pin");
    if (purpose === "disarm") c.st.disarmed = true;
    return true;
  }
  if (inc && isOpen(inc)) {
    c.st.wrong_pins++;
    c.log(inc, "pin_wrong", { n: c.st.wrong_pins });
    if (c.st.wrong_pins >= WRONG_PIN_LIMIT) {
      c.st.wrong_pins = 0;
      applyTransition(c, inc, "DURESS_ACTIVE", "wrong_pins"); // D1: silent
    }
  }
  return false;
}

export function contactAck(c: Ctx, inc: Incident, who: Contact) {
  if (!inc.acked.includes(who.uid)) inc.acked.push(who.uid);
  inc.responders[who.uid] = { name: who.name, at: c.now };
  c.dirty.add(inc.id);
  c.log(inc, "contact_ack", { uid: who.uid });
}

const firstName = (n: string) => n.trim().split(/\s+/)[0].toLowerCase();

// I10: never one tap. Requires the voice/in-person confirmation and the
// wearer's first name typed. DURESS also needs the operator callback (D2).
export function contactResolve(c: Ctx, inc: Incident, who: Contact, spokeByVoice: boolean, typedName: string):
  "refused" | "resolved" | "awaiting_operator" | "noop" {
  if (spokeByVoice !== true || firstName(typedName ?? "") !== firstName(c.name)) {
    c.log(inc, "resolve_refused", { uid: who.uid });
    return "refused";
  }
  if (isOpen(inc)) {
    inc.responders[who.uid] = { name: who.name, at: c.now };
    applyTransition(c, inc, "RESOLVED_BY_CONTACT", `contact:${who.uid}`);
    return "resolved";
  }
  if (inc.state === "DURESS_ACTIVE") {
    inc.duress_confirmed_by = { uid: who.uid, name: who.name, at: c.now };
    c.dirty.add(inc.id);
    c.log(inc, "duress_contact_confirmed", { uid: who.uid });
    return "awaiting_operator";
  }
  return "noop";
}

export function operatorResolve(c: Ctx, inc: Incident, operator: string, note: string, callbackDone: boolean): boolean {
  if (inc.state !== "DURESS_ACTIVE" || !note?.trim()) return false;
  const ok = (inc.duress_confirmed_by && callbackDone === true) || c.now - (inc.duress_at ?? c.now) >= OPERATOR_ONLY_AFTER_MS;
  if (!ok) return false;
  c.log(inc, "operator_note", { operator, note: note.trim(), callback: callbackDone === true });
  applyTransition(c, inc, "RESOLVED", `operator:${operator}`);
  return true;
}

// Timers: removal delay, I9 offline check, repeat calls. Idempotent; called by
// Cloud Tasks at each due time and by the 1-minute sweep as a backstop.
export function processDue(c: Ctx, inc: Incident) {
  if (!inc.open) return;
  if (inc.state === "OPEN_QUIET" && inc.loud_at !== null && c.now >= inc.loud_at) applyTransition(c, inc, "OPEN_LOUD", "timer");
  if (inc.offline_at === null && c.now - c.st.last_seen >= OFFLINE_MS) {
    inc.offline_at = c.st.last_seen;
    c.dirty.add(inc.id);
    c.log(inc, "phone_offline", { last_seen: c.st.last_seen });
    if (inc.state === "OPEN_QUIET") applyTransition(c, inc, "OPEN_LOUD", "phone_offline"); // loud text carries the note
    else alert(c, inc, "offline");
  }
  if (inc.next_call_at !== null && c.now >= inc.next_call_at && (inc.state === "OPEN_LOUD" || inc.state === "DURESS_ACTIVE")) {
    inc.call_round++;
    const kind = inc.state === "DURESS_ACTIVE" ? "duress" : "loud";
    for (const ct of c.contacts) if (!inc.acked.includes(ct.uid)) enqueue(c, inc, kind, "voice", ct, voiceText(c, inc, kind), inc.call_round + 1);
    inc.next_call_at = inc.call_round < CALL_REPEATS ? c.now + CALL_REPEAT_MS : null;
    c.dirty.add(inc.id);
  }
  nextTimer(c, inc);
}

function startCalls(c: Ctx, inc: Incident) {
  inc.call_round = 0;
  inc.next_call_at = c.now + CALL_REPEAT_MS;
}

function nextTimer(c: Ctx, inc: Incident) {
  const due = [inc.loud_at, inc.next_call_at, inc.offline_at === null ? c.st.last_seen + OFFLINE_MS : null]
    .filter((t): t is number => t !== null);
  if (due.length) c.timers.push({ incident: inc.id, at: Math.max(c.now, Math.min(...due)) });
}

// ------------------------------------------------------------ messages (SPEC §8.2)
export const hhmm = (t: number, tz: string) =>
  new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit" }).format(t);

function offlineLine(c: Ctx, inc: Incident) {
  return inc.offline_at !== null ? ` ${c.name}'s phone went offline at ${hhmm(inc.offline_at, c.tz)}.` : "";
}

export function text(c: Ctx, inc: Incident, kind: Kind): string {
  const n = c.name, link = c.linkBase + inc.id;
  switch (kind) {
    case "heads_up":
      return `Heads-up: ${n}'s Hush was removed while armed. We'll alert you loudly in ${inc.removal_delay_s} s unless they cancel. You don't need to do anything yet.`;
    case "loud":
      return `EMERGENCY: ${n} triggered Hush. They may be unable to speak. Live location: ${link}. Call 999 if you can't confirm they're safe. Do NOT call or text their phone.` + offlineLine(c, inc);
    case "duress":
      return `DURESS: ${n} was forced to cancel their alarm. Call 999 now and give this location: ${link}. Do NOT contact ${n}'s phone.` + offlineLine(c, inc);
    case "cancelled":
      return `${n} cancelled the alarm at ${hhmm(c.now, c.tz)} with their PIN.`;
    case "offline":
      return offlineLine(c, inc).trim();
    case "resolved":
      return `${n} is safe: confirmed at ${hhmm(c.now, c.tz)}. No further action needed.`;
    default:
      return "";
  }
}

function voiceText(c: Ctx, inc: Incident, kind: Kind): string {
  const n = c.name;
  if (kind === "duress")
    return `Hush duress alert. ${n} was forced to cancel their alarm. Call 999 now. Their location is in the text message we sent you. Do not contact ${n}'s phone.`;
  return `Hush emergency. ${n} triggered their alarm and may be unable to speak. Their live location is in the text message we sent you. Call 999 if you cannot confirm they are safe. Do not call or text their phone.` + offlineLine(c, inc);
}

const CHANNELS: Partial<Record<Kind, Channel[]>> = {
  heads_up: ["push", "sms"],
  loud: ["push", "sms", "voice"],
  duress: ["push", "sms", "voice"],
  cancelled: ["push", "sms"],
  offline: ["push", "sms"],
  resolved: ["push", "sms"],
};

function alert(c: Ctx, inc: Incident, kind: Kind) {
  const t = text(c, inc, kind);
  inc.summary = kind === "offline" ? `${inc.summary} ${t}` : t;
  c.dirty.add(inc.id);
  for (const ct of c.contacts) {
    if (!inc.contacts.includes(ct.uid)) { inc.contacts.push(ct.uid); c.dirty.add(inc.id); }
    for (const ch of CHANNELS[kind] ?? []) enqueue(c, inc, kind, ch, ct, ch === "voice" ? voiceText(c, inc, kind) : t, 1);
  }
  if (kind === "loud" || kind === "duress") enqueue(c, inc, kind, "authority", null, t, 1);
}

function enqueue(c: Ctx, inc: Incident, kind: Kind, channel: Channel, to: Contact | null, body: string, attempt: number) {
  const cost = COST_USD[channel];
  const critical = (kind === "loud" || kind === "duress") && attempt === 1;
  if (cost && !critical && inc.spend_usd + cost > BUDGET_USD) {
    c.log(inc, "budget_skip", { msg_kind: kind, channel, to: to?.uid ?? null });
    return;
  }
  inc.spend_usd = Math.round((inc.spend_usd + cost) * 100) / 100;
  c.dirty.add(inc.id);
  const key = to ? to.phone.replace(/\D/g, "") : "authority";
  const priority = kind === "loud" || kind === "duress" ? "critical" : kind === "offline" ? "high" : "normal";
  c.outbox.push({
    id: `${inc.id}_${kind}_${attempt}_${channel}_${key}`, // deterministic: a replay cannot double-send (I8)
    incident: inc.id, kind, channel, attempt, to_uid: to?.uid ?? null, to_phone: to?.phone ?? null,
    priority, text: body, cost_usd: cost,
  });
}
