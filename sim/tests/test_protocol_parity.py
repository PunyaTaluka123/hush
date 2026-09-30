"""GATT UUIDs and payload sizes must agree between firmware, simulator and app."""
import os
import re
import uuid as U

import device

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
NAMES = ["svc", "event", "ack", "config", "cal", "status", "live", "haptic"]
SIM = dict(zip(NAMES, [device.SVC, device.EVENT, device.ACK, device.CONFIG, device.CALIBRATE, device.STATUS, device.LIVE, device.HAPTIC]))


def firmware_uuids() -> dict:
    src = open(os.path.join(ROOT, "firmware/src/ble.c")).read()
    a, b, c, d, e = re.search(r"#define HUSH_UUID\(n\) BT_UUID_128_ENCODE\((0x\w+) \+ \(n\), (0x\w+), (0x\w+), (0x\w+), (0x\w+)\)", src).groups()
    out = {}
    for name, n in re.findall(r"bt_uuid_128 (\w+)_uuid = BT_UUID_INIT_128\(HUSH_UUID\((\d)\)\)", src):
        out[name] = str(U.UUID(f"{int(a, 16) + int(n):08x}-{int(b, 16):04x}-{int(c, 16):04x}-{int(d, 16):04x}-{int(e, 16):012x}"))
    return out


def test_firmware_and_sim_uuids_match():
    assert firmware_uuids() == SIM


def test_payload_sizes_match_firmware_header():
    h = open(os.path.join(ROOT, "firmware/lib/hush.h")).read()
    size = lambda n: int(re.search(rf"#define HUSH_{n}_LEN (\d+)", h).group(1))
    assert (size("EVENT"), size("CONFIG"), size("STATUS")) == (11, len(device.config_bytes()), 10)


def test_android_uuids_match():
    src = open(os.path.join(ROOT, "app-android/app/src/main/java/com/hush/app/Protocol.kt")).read()
    fmt = re.search(r'UUID\.fromString\("(485700%02x-[0-9a-f-]+)"\.format\(n\)\)', src).group(1)
    got = {name.lower(): fmt.replace("%02x", f"{int(n):02x}") for name, n in re.findall(r"val (\w+) = uuid\((\d)\)", src)}
    got["cal"] = got.pop("calibrate")
    assert got == SIM
