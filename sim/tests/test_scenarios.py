"""SPEC §12 scenarios and backend invariants, against the Firebase emulator
suite with a fake clock. Run: ./sim/run_e2e.sh"""
import time

import pytest

import backend as B
from conftest import World, key
from device import HAPTIC_DELIVERED, HAPTIC_ERROR, HAPTIC_REGISTERED

CONTACT_CHANNELS = {"push", "sms", "voice"}


def to_contacts(w, msgs):
    return {(m["channel"], m["to_uid"]) for m in msgs if m["channel"] != "authority"}


def all_channels(w, channels=CONTACT_CHANNELS):
    return {(ch, c.uid) for ch in channels for c in w.contacts}


def events(inc_id):
    return sorted(B.ls(f"incidents/{inc_id}/events"), key=lambda e: e["at"])


def test_s1_tongue_hold_loud_fast_and_haptic(world):
    w, lat = world, []
    for _ in range(20):
        w.dev.haptics.clear()
        w.dev.press(3300, 72, rest_after=200)
        assert w.dev.haptics == [HAPTIC_REGISTERED], "trigger registered pulse"
        t0 = time.time()
        r = w.phone.pump()
        inc = r["incident_id"]
        while True:
            msgs = [m for m in w.outbox(inc, "loud") if m["channel"] != "authority"]
            if msgs and all(m["status"] == "sent" for m in msgs):
                break
            assert time.time() - t0 < 30, (r, [(m["_id"], m["status"]) for m in w.outbox(inc)])
            time.sleep(0.05)
        lat.append(time.time() - t0)
        assert to_contacts(w, msgs) == all_channels(w) and all(m["priority"] == "critical" for m in msgs)
        assert r["haptic"] == 2
        w.dev.run(40)
        assert w.dev.haptics[-1] == HAPTIC_DELIVERED, "device got HAPTIC(2)"
        assert w.phone.enter_pin("4821") == "Alarm cancelled"
        w.dev.run(10000)  # detector refractory period
    p95 = sorted(lat)[18]
    print(f"S1 trigger -> loud alert delivered: p95 {p95:.2f} s (emulator)")
    assert p95 < 8


def test_s2_removed_docked_within_3s_no_incident(world):
    w = world
    w.dev.remove(2000)
    w.dev.dock(1500)
    w.phone.pump()
    w.advance(120)
    assert w.incidents() == []
    assert not [m for m in B.ls("outbox") if m.get("incident")]


def test_s3_removed_real_pin_at_20s(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    assert w.incident()["state"] == "OPEN_QUIET"
    heads = w.outbox(inc, "heads_up")
    assert to_contacts(w, heads) == all_channels(w, {"push", "sms"}) and {m["priority"] for m in heads} == {"normal"}
    assert "alert you loudly in 60 s" in heads[0]["text"]
    w.advance(20)
    assert w.phone.enter_pin("4821") == "Alarm cancelled"
    assert w.incident()["state"] == "CANCELLED"
    w.advance(60)
    w.advance(120)
    assert w.incident()["state"] == "CANCELLED"
    assert w.kinds(inc) == {"heads_up", "cancelled"}, "never loud"
    assert "cancelled the alarm at" in w.outbox(inc, "cancelled")[0]["text"]


def test_s4_removed_no_pin_loud_at_60s(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.advance(30)
    w.phone.heartbeat()
    w.advance(29.9)
    assert w.incident()["state"] == "OPEN_QUIET"
    w.advance(0.1)
    assert w.incident()["state"] == "OPEN_LOUD"
    loud = w.outbox(inc, "loud")
    assert to_contacts(w, loud) == all_channels(w)
    assert any(m["channel"] == "authority" for m in loud), "AuthorityChannel called (no-op in v1)"
    assert "EMERGENCY: Punya Test triggered Hush" in loud[0]["text"] and "offline" not in loud[0]["text"]
    w.wait_delivered()
    sent = {m["_id"] for m in w.outbox(inc)}
    logged = {e["msg"] for e in events(inc) if e["kind"] == "notify" and e["status"] == "sent"}
    assert sent == logged, "SPEC §13: every send is in the incident's audit log"


def wearer_view(pin: str):
    """Everything the wearer's phone (i.e. the attacker) can observe on the S3/S5 path."""
    w = World()
    w.dev.remove()
    w.phone.pump()
    w.advance(20)
    n = len(w.phone.sent)
    ui = w.phone.enter_pin(pin)
    for _ in range(4):  # post-cancel location streaming + heartbeat, identical on both paths
        w.advance(15)
        w.phone.push_location()
    w.phone.heartbeat(location={"t": w.t, "lat": 1.35, "lng": 103.68, "acc": 10.0})
    w.advance(120)
    wu = w.wearer
    inc_id = w.incidents()[0]["_id"]
    view = {
        "ui": ui,
        "history": w.phone.history,
        "request_sizes": [(name, len(body)) for name, body in w.phone.sent[n:]],
        "responses": [(name, st, r) for name, st, r in w.phone.responses[n:]],
        "pushes_to_wearer": [m for m in B.ls("outbox") if m.get("to_uid") == wu.uid],
        "profile": {k: v for k, v in B.get(f"users/{wu.uid}", wu.token)[1].items() if k not in ("_id", "phone")},
        "incident_read": B.get(f"incidents/{inc_id}", wu.token)[0],
        "private_read": [B.get(f"users/{wu.uid}/private/{d}", wu.token)[0] for d in ("state", "pins")],
        "haptics": w.dev.haptics,
    }
    return w, view


def test_s5_duress_pin_identical_to_real_for_the_wearer(emu):
    a_world, a = wearer_view("4821")
    a_state, a_kinds = a_world.incident()["state"], a_world.kinds(a_world.incident()["_id"])
    b_world, b = wearer_view("4822")
    b_inc = b_world.incident()
    assert (a_state, b_inc["state"]) == ("CANCELLED", "DURESS_ACTIVE")
    assert a == b, "I1: wearer-observable behaviour must be identical"
    assert a["incident_read"] == 403 and a["private_read"] == [403, 403] and a["pushes_to_wearer"] == []
    assert "duress" not in a_kinds
    duress = b_world.outbox(b_inc["_id"], "duress")
    assert to_contacts(b_world, duress) == all_channels(b_world) and {m["priority"] for m in duress} == {"critical"}
    assert duress[0]["text"].startswith("DURESS: Punya Test was forced to cancel")


def test_s6_real_then_duress_5_min_later_reopens(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.advance(20)
    w.phone.enter_pin("4821")
    w.advance(300)
    w.phone.enter_pin("4822", purpose="disarm")  # attacker forces another PIN entry
    assert [i["_id"] for i in w.incidents()] == [inc]
    assert w.incident()["state"] == "DURESS_ACTIVE"
    assert {"cancelled", "duress"} <= w.kinds(inc)


def test_s6b_duress_long_after_cancel_still_escalates(world):
    w = world
    w.dev.remove()
    first = w.phone.pump()["incident_id"]
    w.advance(20)
    w.phone.enter_pin("4821")
    w.advance(11 * 60)
    w.phone.enter_pin("4822", purpose="disarm")
    old, new = w.incidents()
    assert old["_id"] == first and old["state"] == "CANCELLED"
    assert new["state"] == "DURESS_ACTIVE" and "duress" in w.kinds(new["_id"])


def test_s7_airplane_mode_offline_note(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.phone.online = False
    w.advance(60)
    assert w.incident()["state"] == "OPEN_LOUD"
    w.advance(29)
    assert not w.outbox(inc, "offline")
    w.advance(1)
    off = w.outbox(inc, "offline")
    assert to_contacts(w, off) == all_channels(w, {"push", "sms"})
    assert "Punya Test's phone went offline at" in off[0]["text"]


def test_s7b_offline_escalates_before_removal_timer(emu):
    w = World(removal_delay_s=120)
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    assert "alert you loudly in 120 s" in w.outbox(inc, "heads_up")[0]["text"]
    w.phone.online = False
    w.advance(89)
    assert w.incident()["state"] == "OPEN_QUIET"
    w.advance(1)
    assert w.incident()["state"] == "OPEN_LOUD", "I9: loud within 90 s"
    assert "phone went offline at" in w.outbox(inc, "loud")[0]["text"]
    assert [e["reason"] for e in events(inc) if e["kind"] == "transition"][-1] == "phone_offline"


def test_s8_app_killed_contacts_still_escalate(world):
    w = world
    w.dev.press(3300, 72)
    inc = w.phone.pump()["incident_id"]
    w.phone.online = False  # killed: nothing more from the phone
    first, second = w.contacts

    def voice(attempt):
        return {m["to_uid"] for m in w.outbox(inc, "loud") if m["channel"] == "voice" and m["attempt"] == attempt}

    assert voice(1) == {first.uid, second.uid}
    w.advance(90)
    assert w.outbox(inc, "offline")
    w.advance(30)
    assert voice(2) == {first.uid, second.uid}, "repeat call at 2 min"
    B.call(first, "contactAck", {"idempotency_key": key(), "incident_id": inc})
    w.advance(120)
    assert voice(3) == {second.uid}, "acknowledged contact is not called again"
    w.advance(120)
    w.advance(120)
    assert voice(4) == {second.uid} and voice(5) == set(), "3 repeats, then stop"
    assert w.incident()["responders"][first.uid]["name"] == "Alex"


def test_s9_out_of_range_then_reconnect(world):
    w = world
    w.phone.ble = False
    w.dev.disconnected()
    w.dev.press(3300, 72)
    assert w.dev.adv() == (20, 0b11), "fast advertising, pending|armed"
    w.dev.run(35000)
    assert w.dev.adv()[0] == 100
    w.dev = w.phone.dev = w.dev.reboot()  # battery glitch while out of range: log is in flash
    w.dev.run(85000)
    assert w.dev.status()["pending"] >= 1
    w.phone.ble = True
    w.phone.pump()
    seqs = [e["device_seq"] for name, body in w.phone.sent if name == "reportDeviceEvents"
            for e in __import__("json").loads(body)["data"]["events"]]
    assert seqs == sorted(set(seqs)), "in order, each once"
    (inc,) = w.incidents()
    assert inc["type"] == "TONGUE" and w.dev.status()["pending"] == 0
    w.dev.run(40)
    assert w.dev.haptics[-1] == HAPTIC_DELIVERED


def test_s9b_ack_lost_after_server_accepted(world):
    w = world
    w.dev.press(3300, 72)
    evs = []
    while (e := w.dev.indicate()) is not None:
        evs.append(e)
    first = w.phone.report(evs)  # server accepted, then the link dropped before the ACK
    w.dev.disconnected()
    from phone import Phone
    w.phone = Phone(w.wearer, w.dev, w.device_id)  # app restarted, remembers nothing
    second = w.phone.pump()
    assert second["incident_id"] == first["incident_id"] and second["haptic"] == 2
    assert len(w.incidents()) == 1 and w.dev.status()["pending"] == 0
    assert len([m for m in w.outbox(first["incident_id"], "loud") if m["channel"] == "sms"]) == 2


def test_s10_same_event_resent_5_times(world):
    w = world
    w.dev.press(3300, 72)
    ev = w.dev.indicate()
    results = [w.phone.report([ev]) for _ in range(5)]
    assert len({r["incident_id"] for r in results}) == 1
    (inc,) = w.incidents()
    loud = w.outbox(inc["_id"], "loud")
    assert sorted(to_contacts(w, loud)) == sorted(all_channels(w)) and len(loud) == 7, "one of each, no duplicates"


def test_i8_submit_pin_replay_has_one_effect(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.phone.enter_pin("4821")
    body = w.phone.sent[-1][1]
    for _ in range(3):
        B.call_raw(w.wearer, "submitPin", body)
    assert len(w.outbox(inc, "cancelled")) == 4  # 2 contacts x push+sms, once


def test_s11_contact_cannot_silence_with_one_tap(world):
    w = world
    w.dev.press(3300, 72)
    inc = w.phone.pump()["incident_id"]
    alex = w.contacts[0]
    for spoke, name in [(False, "Punya"), (True, "Pun"), (True, "")]:
        r = B.call(alex, "contactResolve", {"idempotency_key": key(), "incident_id": inc, "spoke_by_voice": spoke, "typed_name": name})
        assert r["result"] == "refused"
    assert w.incident()["state"] == "OPEN_LOUD"
    stranger = B.sign_in("+6599990000")
    with pytest.raises(B.CallError):
        B.call(stranger, "contactResolve", {"idempotency_key": key(), "incident_id": inc, "spoke_by_voice": True, "typed_name": "Punya"})
    r = B.call(alex, "contactResolve", {"idempotency_key": key(), "incident_id": inc, "spoke_by_voice": True, "typed_name": " punya "})
    assert r["result"] == "resolved" and w.incident()["state"] == "RESOLVED_BY_CONTACT"
    assert "resolved" in w.kinds(inc)


def test_d2_duress_needs_contact_and_operator_callback(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.phone.enter_pin("4822")
    w.phone.enter_pin("4821")  # the wearer's phone can never resolve duress
    assert w.incident()["state"] == "DURESS_ACTIVE"
    op = B.set_claims(B.sign_in("+6598887777"), {"operator": True})
    resolve = lambda cb: B.call(op, "operatorResolve", {"idempotency_key": key(), "incident_id": inc, "note": "called back", "callback_done": cb})
    assert resolve(True)["ok"] is False, "no contact confirmation yet"
    r = B.call(w.contacts[1], "contactResolve", {"idempotency_key": key(), "incident_id": inc, "spoke_by_voice": True, "typed_name": "Punya"})
    assert r["result"] == "awaiting_operator" and w.incident()["state"] == "DURESS_ACTIVE"
    assert resolve(False)["ok"] is False, "operator callback required"
    with pytest.raises(B.CallError):
        B.call(w.contacts[0], "operatorResolve", {"idempotency_key": key(), "incident_id": inc, "note": "x", "callback_done": True})
    assert resolve(True)["ok"] is True and w.incident()["state"] == "RESOLVED"


def test_d2_duress_operator_alone_after_24h(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.phone.enter_pin("4822")
    op = B.set_claims(B.sign_in("+6598887776"), {"operator": True})
    resolve = lambda: B.call(op, "operatorResolve", {"idempotency_key": key(), "incident_id": inc, "note": "no answer; welfare check done", "callback_done": False})
    w.advance(23 * 3600)
    assert resolve()["ok"] is False
    w.advance(3600)
    assert resolve()["ok"] is True


def test_s12_three_wrong_pins_silent_duress(world):
    w = world
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.advance(10)
    n = len(w.phone.responses)
    for i, p in enumerate(["1111", "2580", "9999"]):
        assert w.phone.enter_pin(p) == "Incorrect PIN. Try again."
        assert w.incident()["state"] == ("OPEN_QUIET" if i < 2 else "DURESS_ACTIVE")
    assert {r[2]["result"]["ok"] for r in w.phone.responses[n:]} == {True}
    assert "duress" in w.kinds(inc)
    assert not [m for m in B.ls("outbox") if m.get("to_uid") == w.wearer.uid]


# ---------------------------------------------------------------- invariants and rules
def locations(inc_id):
    return B.ls(f"incidents/{inc_id}/locations")


def test_i6_location_only_during_open_incident(world):
    w = world
    w.phone.push_location()
    w.phone.heartbeat(location={"t": w.t, "lat": 1.3, "lng": 103.8, "acc": 5.0})
    assert w.incidents() == []
    w.dev.remove()
    inc = w.phone.pump()["incident_id"]
    w.advance(1)
    w.phone.push_location()
    assert len(locations(inc)) == 1
    w.phone.enter_pin("4821")
    w.advance(15)
    w.phone.push_location()
    assert len(locations(inc)) == 1, "dropped after a real cancel"
    w.phone.enter_pin("4822", purpose="disarm")
    w.advance(15)
    w.phone.push_location()
    assert len(locations(inc)) == 2, "kept while DURESS_ACTIVE"


def test_i7_pins_hashed_and_server_only(world):
    w = world
    _, p = B.get(f"users/{w.wearer.uid}/private/pins")
    assert set(p) >= {"salt", "real", "duress", "nonce"} and len(p["real"]) == len(p["duress"]) == 32
    assert "4821" not in repr(p) and "4822" not in repr(p)
    for who in [w.wearer, *w.contacts]:
        assert B.get(f"users/{w.wearer.uid}/private/pins", who.token)[0] == 403
    with pytest.raises(B.CallError):
        B.call(w.wearer, "setPins", {"idempotency_key": key(), "pin": "1234", "pin2": "12345"})
    r = w.phone.set_pins("5555", "6666", current="1111")
    assert r == {"ok": False, "error": "incorrect_pin"}
    assert w.phone.set_pins("5555", "6666", current="4821")["ok"] and w.phone.pin_ok("6666") and not w.phone.pin_ok("4821")


def test_pin_protected_settings(world):
    w = world
    k = key()
    r = B.call(w.wearer, "updateSettings", {"idempotency_key": k, "removal_delay_s": 120, "pin_proof": w.phone.proof_for("0000", k)})
    assert r == {"ok": False, "error": "incorrect_pin"}
    with pytest.raises(B.CallError):
        B.call(w.wearer, "updateSettings", {"idempotency_key": key(), "removal_delay_s": 20, "pin_proof": w.phone.proof_for("4821", k)})
    k = key()
    assert B.call(w.wearer, "updateSettings", {"idempotency_key": k, "removal_delay_s": 90, "pin_proof": w.phone.proof_for("4822", k)})["ok"]
    assert B.get(f"users/{w.wearer.uid}")[1]["settings"]["removal_delay_s"] == 90
    (inc,) = w.incidents()
    assert inc["state"] == "DURESS_ACTIVE", "duress PIN at a settings prompt escalates silently"


def test_rules_incident_visibility(world):
    w = world
    w.dev.press(3300, 72)
    inc = w.phone.pump()["incident_id"]
    w.advance(1)
    w.phone.push_location()
    alex, sam = w.contacts
    assert B.get(f"incidents/{inc}", w.wearer.token)[0] == 403, "wearer's client reads no incident"
    assert B.get(f"incidents/{inc}", alex.token)[0] == 200
    assert B.ls(f"incidents/{inc}/locations", alex.token)
    assert B.http("GET", f"{B.FS}/incidents/{inc}/events", token=alex.token)[0] == 403
    assert B.http("GET", f"{B.FS}/outbox", token=alex.token)[0] == 403
    st, found = B.query("incidents", [("contacts", "array-contains", alex.uid), ("open", "==", True)], alex.token)
    assert st == 200 and [d["_id"] for d in found] == [inc]
    assert B.query("incidents", [("contacts", "array-contains", alex.uid)], alex.token)[0] == 403
    stranger = B.sign_in("+6599991111")
    assert B.get(f"incidents/{inc}", stranger.token)[0] == 403
    k = key()
    assert B.call(w.wearer, "removeContact", {"idempotency_key": k, "phone": sam.phone, "pin_proof": w.phone.proof_for("4821", k)})["ok"]
    assert B.get(f"incidents/{inc}", sam.token)[0] == 403, "removed contact loses access"
    assert B.http("GET", f"{B.FS}/incidents/{inc}/locations", token=sam.token)[0] == 403


def test_link_lost_warns_wearer_only(world):
    w = world
    w.phone.ble = False
    w.phone.heartbeat()
    w.advance(60)
    w.phone.heartbeat()
    w.advance(31)
    w.advance(60)
    msgs = [m for m in B.ls("outbox") if m["kind"] == "link_lost"]
    assert len(msgs) == 1 and msgs[0]["to_uid"] == w.wearer.uid and msgs[0]["channel"] == "push"
    assert w.incidents() == []


def test_no_contacts_error_haptic(emu):
    w = World(contacts=())
    w.dev.press(3300, 72)
    assert w.phone.pump()["haptic"] == 3
    w.dev.run(40)
    assert w.dev.haptics[-1] == HAPTIC_ERROR


def test_d5_budget_cap_keeps_first_alerts(emu):
    w = World(contacts=("A", "B", "C", "D", "E"))
    w.dev.press(3300, 72)
    inc = w.phone.pump()["incident_id"]
    for _ in range(4):
        w.advance(120)
        w.phone.heartbeat()
    w.phone.enter_pin("4822")  # DURESS after a full round of loud calls
    for _ in range(4):
        w.advance(120)
        w.phone.heartbeat()
    doc = w.incident()
    assert doc["spend_usd"] <= 10.0
    firsts = [m for m in w.outbox(inc) if m["kind"] in ("loud", "duress") and m["attempt"] == 1 and m["channel"] != "authority"]
    assert len(firsts) == 2 * 5 * 3, "first loud and first DURESS always go out"
    assert any(e["kind"] == "budget_skip" for e in events(inc))
