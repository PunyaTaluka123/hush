import os
import random
import sys
import time
import uuid

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import backend as B  # noqa: E402
from device import Device  # noqa: E402
from phone import Phone  # noqa: E402

T0 = 1_790_000_000_000  # fake backend clock start (ms)


def key() -> str:
    return str(uuid.uuid4())


class World:
    """A wearer (Punya) with 2 contacts, a calibrated retainer being worn, and the phone app."""

    def __init__(self, contacts=("Alex", "Sam"), removal_delay_s: int | None = None):
        B.reset()
        self.t = T0
        B.clock(self.t)
        n = random.randint(100000, 999999)
        self.wearer = B.sign_in(f"+6581{n}")
        assert B.put(f"users/{self.wearer.uid}", {"name": "Punya Test", "roles": {"wearer": True}, "phone": self.wearer.phone},
                     self.wearer.token) == 200
        self.contacts: list[B.User] = []
        for i, name in enumerate(contacts):
            c = B.sign_in(f"+6582{n}{i}"[:12])
            B.call(self.wearer, "inviteContact", {"idempotency_key": key(), "phone": c.phone, "name": name})
            _, link = B.get(f"contacts/{self.wearer.uid}/links/{c.phone}")
            B.call(c, "acceptInvite", {"idempotency_key": key(), "wearer": self.wearer.uid, "token": link["token"]})
            self.contacts.append(c)
        self.dev = Device()
        self.device_id = f"dev-{n}"
        B.call(self.wearer, "registerDevice", {"idempotency_key": key(), "device_id": self.device_id, "fw_version": "2.0.0"})
        self.phone = Phone(self.wearer, self.dev, self.device_id)
        self.phone.now = self.t
        self.phone.set_pins("4821", "4822")
        if removal_delay_s:
            k = key()
            assert B.call(self.wearer, "updateSettings", {"idempotency_key": k, "removal_delay_s": removal_delay_s,
                                                          "pin_proof": self.phone.proof_for("4821", k)})["ok"]
        self.dev.wear()
        self.dev.calibrate()
        self.phone.pump()
        self.phone.heartbeat()

    def advance(self, seconds: float):
        """Moves the backend's fake clock forward and runs its timer sweep."""
        self.t += int(seconds * 1000)
        B.clock(self.t)
        self.phone.now = self.t

    def incidents(self) -> list[dict]:
        _, docs = B.query("incidents", [("wearer", "==", self.wearer.uid)])
        return sorted(docs, key=lambda d: d["opened_at"])

    def incident(self) -> dict:
        (inc,) = self.incidents()
        return inc

    def outbox(self, incident: str | None = None, kind: str | None = None) -> list[dict]:
        msgs = B.query("outbox", [("incident", "==", incident)])[1] if incident else B.ls("outbox")
        return [m for m in msgs if kind is None or m["kind"] == kind]

    def kinds(self, incident: str) -> set[str]:
        return {m["kind"] for m in self.outbox(incident)}

    def wait_delivered(self, timeout=15.0) -> list[dict]:
        end = time.time() + timeout
        while time.time() < end:
            box = B.ls("outbox")
            if box and all(m["status"] != "pending" for m in box):
                return box
            time.sleep(0.1)
        raise AssertionError("outbox not delivered in time")


@pytest.fixture(scope="session")
def emu():
    if not B.up():
        pytest.skip("Firebase emulators not running: ./sim/run_e2e.sh")


@pytest.fixture
def world(emu):
    return World()
