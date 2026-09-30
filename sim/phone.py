"""Wearer-phone stand-in: the protocol behaviour app-android implements, minus
the UI. Replaces v0.1's bridge/hush_bridge.py, with one big change: the phone
no longer alerts anyone (I3). It forwards device events to the server, ACKs the
device only after the server accepted them (SPEC §5.4), and sends PIN proofs.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
import uuid

from argon2.low_level import Type, hash_secret_raw

import backend as B
from device import Device, Event

ZERO_ID = "0" * 20


def argon2id(pin: str, salt: bytes, p: dict) -> bytes:
    return hash_secret_raw(pin.encode(), salt, time_cost=p["iterations"], memory_cost=p["memorySize"],
                           parallelism=p["parallelism"], hash_len=p["hashLength"], type=Type.ID)


class Phone:
    def __init__(self, user: B.User, dev: Device, device_id: str):
        self.user, self.dev, self.device_id = user, dev, device_id
        self.ble = True          # BLE link to the retainer
        self.online = True       # network (False = airplane mode / killed app)
        self.now = int(time.time() * 1000)
        self.inbox: dict[int, Event] = {}   # indicated by the device, not yet accepted by the server
        self.server_acked = 0
        self.to_ack = 0          # accepted by the server, ACK not yet written to the device
        self.incident = ZERO_ID
        self.queue: list[tuple[str, bytes]] = []  # requests waiting for the network (PIN proofs)
        self.sent: list[tuple[str, bytes]] = []   # every request body sent (I1 comparisons)
        self.responses: list[tuple[str, int, dict]] = []
        self.history: list[str] = []              # local incident history, as the UI shows it
        self.last_report: dict | None = None
        self.pin = None  # {salt, nonce, argon, hash_key, pair}

    # ---------------------------------------------------------------- transport
    def _call(self, name: str, data: dict) -> dict:
        body = json.dumps({"data": data}, separators=(",", ":")).encode()
        self.sent.append((name, body))
        status, r = B.call_raw(self.user, name, body)
        self.responses.append((name, status, r))
        if "error" in r:
            raise B.CallError(r["error"].get("status", str(status)), r["error"].get("message", ""))
        return r["result"]

    def flush(self):
        while self.online and self.queue:
            name, body = self.queue.pop(0)
            self.sent.append((name, body))
            status, r = B.call_raw(self.user, name, body)
            self.responses.append((name, status, r))

    # ---------------------------------------------------------------- device link
    def pump(self) -> dict | None:
        """One app cycle: collect indications, report them, ACK and haptic back."""
        if self.ble:
            while (ev := self.dev.indicate()) is not None:
                if ev.seq > self.server_acked:
                    self.inbox.setdefault(ev.seq, ev)
                else:
                    self.to_ack = max(self.to_ack, ev.seq)  # already accepted; just re-ACK
        if not self.online:
            return None
        self.flush()
        r = None
        if self.inbox:
            r = self.report(sorted(self.inbox.values(), key=lambda e: e.seq))
            self.inbox = {s: e for s, e in self.inbox.items() if s > r["acked_seq"]}
            self.server_acked = max(self.server_acked, r["acked_seq"])
            self.to_ack = max(self.to_ack, r["acked_seq"])
            if r["incident_id"] != ZERO_ID:
                self.incident = r["incident_id"]
            self.last_report = r
        if self.ble and self.to_ack:
            self.dev.write_ack(self.to_ack)
            if r and r["haptic"]:
                self.dev.write_haptic(r["haptic"])
        return r

    def report(self, evs: list[Event]) -> dict:
        return self._call("reportDeviceEvents", {
            "idempotency_key": str(uuid.uuid4()), "device_id": self.device_id,
            "events": [{"device_seq": e.seq, "type": e.type, "data": e.data, "observed_at": self.now} for e in evs]})

    def heartbeat(self, location: dict | None = None):
        s = self.dev.status()
        data = {"idempotency_key": str(uuid.uuid4()), "device_battery_mv": s["battery_mv"], "phone_battery_pct": 80,
                "wear_state": s["wear_state"], "armed": s["armed"], "ble_connected": self.ble}
        if location:
            data["location"] = location
        if self.online:
            self._call("heartbeat", data)

    def push_location(self, lat=1.3483, lng=103.6831, acc=12.0):
        if self.online:
            self._call("pushLocation", {"idempotency_key": str(uuid.uuid4()),
                                        "points": [{"t": self.now, "lat": lat, "lng": lng, "acc": acc}]})

    # ---------------------------------------------------------------- PINs (SPEC §7)
    def _hk(self, pin: str) -> bytes:
        return hmac.new(self.pin["hash_key"], pin.encode(), hashlib.sha256).digest()

    def set_pins(self, real: str, duress: str, current: str | None = None) -> dict:
        data = {"idempotency_key": str(uuid.uuid4()), "pin": real, "pin2": duress}
        if current:
            data["current_proof"] = self._proof(current, data["idempotency_key"], "config", ZERO_ID)
        r = self._call("setPins", data)
        if r["ok"]:
            self.pin = {k: base64.b64decode(r[k]) for k in ("salt", "nonce", "hash_key")}
            self.pin["argon"] = r["argon"]
            # unordered, unlabelled: the phone cannot tell which one is duress (I7)
            self.pin["pair"] = sorted([self._hk(real), self._hk(duress)])
        return r

    def pin_ok(self, pin: str) -> bool:
        return self._hk(pin) in self.pin["pair"]

    def _proof(self, pin: str, key: str, purpose: str, incident: str) -> str:
        a = argon2id(pin, self.pin["salt"], self.pin["argon"])
        msg = f"|{key}|{purpose}|{incident}".encode()
        return base64.b64encode(hmac.new(self.pin["nonce"], a + msg, hashlib.sha256).digest()).decode()

    def proof_for(self, pin: str, key: str) -> str:
        return self._proof(pin, key, "config", ZERO_ID)

    def enter_pin(self, pin: str, purpose: str = "cancel") -> str:
        """What the PIN pad does. Returns the screen text. The UI never waits for
        or depends on the server; every attempt is sent (the server counts wrong ones)."""
        key = str(uuid.uuid4())
        body = json.dumps({"data": {"idempotency_key": key, "incident_id": self.incident, "purpose": purpose,
                                    "proof": self._proof(pin, key, purpose, self.incident)}},
                          separators=(",", ":")).encode()
        self.queue.append(("submitPin", body))
        self.flush()
        if not self.pin_ok(pin):
            return "Incorrect PIN. Try again."
        if purpose == "cancel":
            self.history.append("Cancelled " + time.strftime("%H:%M", time.gmtime(self.now / 1000 + 8 * 3600)))
            self.incident = ZERO_ID
            return "Alarm cancelled"
        return "Disarmed"
