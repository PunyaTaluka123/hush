"""
Calibrate a Hush device and record live tongue-pressure traces for tuning.

  python tools/tune.py calibrate            # rest (3 s), then max press (5 s)
  python tools/tune.py record out.csv 120   # stream 120 s of pressure at 20 Hz
  python tools/tune.py analyse out.csv      # press count, durations, peaks

GATT v2 (SPEC §5.5). The retainer only advertises its service while in the
pairing window (docked, case button held 5 s), and only bonded centrals can
connect: pair this computer once in that window, then pass --address.

Record sessions of: normal talking, drinking, swallowing, eating, and
deliberate holds. `analyse` shows how close each everyday activity comes to
the trigger rules, which is how you pick threshold and hold time per user.
"""
from __future__ import annotations

import asyncio
import csv
import os
import struct
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "sim"))
from device import CALIBRATE as CAL, LIVE, STATUS, SVC  # noqa: E402

ADDRESS = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--address=")), None)
sys.argv = [a for a in sys.argv if not a.startswith("--address=")]


async def connect():
    from bleak import BleakClient, BleakScanner
    dev = ADDRESS or await BleakScanner.find_device_by_filter(
        lambda d, ad: SVC.lower() in [u.lower() for u in ad.service_uuids], timeout=15)
    if not dev:
        sys.exit("Hush not found (not in the pairing window? pass --address=<bonded address>)")
    c = BleakClient(dev)
    await c.connect()
    try:
        await c.pair()
    except Exception:  # noqa: BLE001
        pass
    return c


async def calibrate():
    c = await connect()
    input("Relax your tongue, then press Enter (3 s capture)...")
    await c.write_gatt_char(CAL, b"\x01", response=True)
    await asyncio.sleep(3.5)
    input("Press your tongue forward and down as HARD as you can when you hit Enter (5 s)...")
    await c.write_gatt_char(CAL, b"\x02", response=True)
    await asyncio.sleep(5.5)
    mv, wear, armed, pending, faults, rest, mx = struct.unpack("<HBBBBhh", bytes(await c.read_gatt_char(STATUS)))
    print(f"rest {rest} hPa, max {mx} hPa, {'OK' if not faults & 0x08 else 'FAILED: press harder and retry'}")
    await c.disconnect()


async def record(path: str, seconds: float):
    c = await connect()
    rows, t0 = [], time.time()
    await c.start_notify(LIVE, lambda _, d: rows.append((round(time.time() - t0, 3), d[0])))
    await asyncio.sleep(seconds)
    await c.disconnect()
    with open(path, "w", newline="") as f:
        csv.writer(f).writerows([("t", "pct"), *rows])
    print(f"{len(rows)} samples -> {path}")


def analyse(path: str, threshold: float = 55, hyst: float = 10):
    with open(path) as f:
        data = [(float(r["t"]), float(r["pct"])) for r in csv.DictReader(f)]
    presses, start, peak = [], None, 0.0
    for t, p in data:
        if start is None and p >= threshold:
            start, peak = t, p
        elif start is not None:
            peak = max(peak, p)
            if p < threshold - hyst:
                presses.append((start, t - start, peak))
                start = None
    print(f"{len(presses)} presses above {threshold}%:")
    for s, d, pk in presses:
        flag = "  <-- would trigger HOLD" if d >= 3.0 else ("  (long)" if d >= 1.5 else "")
        print(f"  t={s:7.2f}s  dur={d:5.2f}s  peak={pk:5.1f}%{flag}")
    if presses:
        print(f"longest non-deliberate press should stay well under 1.5 s; "
              f"max here = {max(p[1] for p in presses):.2f}s")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "calibrate":
        asyncio.run(calibrate())
    elif cmd == "record":
        asyncio.run(record(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 60))
    elif cmd == "analyse":
        analyse(sys.argv[2], *(float(x) for x in sys.argv[3:5]))
    else:
        print(__doc__)
