"""Simulated Hush retainer: the real firmware core (firmware/lib/hush_core.h)
loaded through ctypes, plus a physical model of the inputs (cavity pressure,
temperature, tissue proximity, case magnet).

Build the library first:  make -C firmware/tests/host lib
"""
from __future__ import annotations

import ctypes
import os
import struct
from dataclasses import dataclass

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "libhushcore.so")

EVENT_NAMES = {1: "TRIGGER_HOLD", 2: "TRIGGER_DOUBLE_LONG", 3: "STUCK_HIGH", 4: "REMOVED_WHILE_ARMED",
               5: "WORN", 6: "DOCKED", 7: "BATTERY_LOW", 8: "SENSOR_FAULT", 9: "BOOT"}
WEAR = {0: "UNKNOWN", 1: "DOCKED", 2: "WORN", 3: "OFF_BODY"}
HAPTIC_REGISTERED, HAPTIC_DELIVERED, HAPTIC_ERROR, HAPTIC_TEST = 1, 2, 3, 4
DT = 20  # ms, 50 Hz like the firmware


def uuid(n: int) -> str:  # GATT v2, same base as v0.1; short id in the first field (firmware/src/ble.c)
    return f"485700{n:02x}-0010-55a1-8e4d-4c902b6e1f3a"


SVC, EVENT, ACK, CONFIG, CALIBRATE, STATUS, LIVE, HAPTIC = (uuid(i) for i in range(1, 9))


def _lib() -> ctypes.CDLL:
    if not os.path.exists(LIB):
        raise RuntimeError(f"{LIB} missing: run `make -C firmware/tests/host lib`")
    L = ctypes.CDLL(LIB)
    P, U8, U16, U32, SZ, I = (ctypes.c_void_p, ctypes.c_uint8, ctypes.c_uint16, ctypes.c_uint32,
                               ctypes.c_size_t, ctypes.c_int)
    B = ctypes.c_char_p
    sig = {
        "hush_new": ([], P), "hush_free": ([P], None), "hush_boot": ([P, U32, U16], None),
        "hush_sample": ([P, U32, ctypes.c_float, ctypes.c_float, I, I, I, I], None),
        "hush_battery": ([P, U32, U16], None),
        "hush_write_config": ([P, B, SZ], I), "hush_write_ack": ([P, B, SZ], I),
        "hush_write_calibrate": ([P, U32, B, SZ], I), "hush_write_haptic": ([P, B, SZ], I),
        "hush_read_config": ([P, B], None), "hush_read_status": ([P, B], None),
        "hush_next_event": ([P, B], I), "hush_indicated": ([P, U32], None), "hush_disconnected": ([P], None),
        "hush_take_haptic": ([P], U8), "hush_adv_interval": ([P, U32], U16), "hush_adv_flags": ([P], U8),
        "hush_pairing_open": ([P], I), "hush_save": ([P, B], SZ), "hush_load": ([P, B, SZ], I),
    }
    for name, (args, res) in sig.items():
        f = getattr(L, name)
        f.argtypes, f.restype = args, res
    return L


@dataclass
class Event:
    seq: int
    uptime_ms: int
    type: int
    data: int

    @property
    def name(self) -> str:
        return EVENT_NAMES.get(self.type, str(self.type))

    @classmethod
    def unpack(cls, b: bytes) -> "Event":
        return cls(*struct.unpack("<IIBH", b))


def config_bytes(threshold_pct=55, hold_ms=3000, pattern_mask=3, auto_arm=1, haptics=1, version=2) -> bytes:
    return struct.pack("<BBHBBB", version, threshold_pct, hold_ms, pattern_mask, auto_arm, haptics)


class Device:
    # Sealed cavity: rest pressure is REST at mouth temperature and scales with
    # absolute temperature (p ∝ T). A press adds press_hpa on top.
    REST, SPAN, MOUTH_K = 1000.0, 100.0, 309.65  # hPa; 1 % of the press range == 1 hPa

    def __init__(self, flash: bytes | None = None, reset_reason: int = 1):
        self.L = _lib()
        self.h = self.L.hush_new()
        self.t = 1000
        self.press_hpa, self.temp, self.hall, self.power, self.cap, self.button = 0.0, 22.0, False, False, 0, False
        if flash:
            assert self.L.hush_load(self.h, flash, len(flash)), "flash image rejected"
        self.L.hush_boot(self.h, self.t, reset_reason)
        self.haptics: list[int] = []

    def __del__(self):
        if getattr(self, "h", None):
            self.L.hush_free(self.h)

    # ---- physical world
    def run(self, ms: int):
        for _ in range(ms // DT):
            p = self.REST * (self.temp + 273.15) / self.MOUTH_K + self.press_hpa
            self.L.hush_sample(self.h, self.t, p, self.temp, self.hall, self.power, self.cap, self.button)
            self.t += DT
            h = self.L.hush_take_haptic(self.h)
            if h:
                self.haptics.append(h)

    def calibrate(self):
        """Onboarding calibration, done in the mouth: rest 3 s, then hardest press 5 s."""
        self.write_calibrate(1); self.run(3100)
        self.write_calibrate(2); self.press_hpa = self.SPAN; self.run(5100); self.press_hpa = 0
        self.run(2000)

    def wear(self):
        self.hall, self.power, self.cap, self.temp = False, False, 1, 36.5
        self.run(12000)

    def remove(self, ms: int = 4000):
        self.cap, self.temp = 0, 30.0
        self.run(ms)

    def dock(self, ms: int = 1500):
        self.hall, self.power, self.cap = True, True, 0
        self.run(ms)

    def magnet(self, ms: int = 1500):
        """A bare magnet held to the retainer: no charging power, so it must not dock."""
        self.hall, self.power, self.cap = True, False, 0
        self.run(ms)

    def press(self, ms: int, pct: float, rest_after: int = 1000):
        self.press_hpa = pct / 100 * self.SPAN
        self.run(ms)
        self.press_hpa = 0
        self.run(rest_after)

    def reboot(self) -> "Device":
        d = Device(self.save(), reset_reason=4)
        d.t, d.press_hpa, d.temp, d.hall, d.power, d.cap = self.t, self.press_hpa, self.temp, self.hall, self.power, self.cap
        return d

    # ---- GATT (what a central sees)
    def write_config(self, b: bytes) -> bool:
        return bool(self.L.hush_write_config(self.h, b, len(b)))

    def read_config(self) -> bytes:
        out = ctypes.create_string_buffer(7)
        self.L.hush_read_config(self.h, out)
        return out.raw

    def write_ack(self, seq: int) -> bool:
        return bool(self.L.hush_write_ack(self.h, struct.pack("<I", seq), 4))

    def write_calibrate(self, kind: int) -> bool:
        return bool(self.L.hush_write_calibrate(self.h, self.t, bytes([kind]), 1))

    def write_haptic(self, pattern: int) -> bool:
        return bool(self.L.hush_write_haptic(self.h, bytes([pattern]), 1))

    def indicate(self) -> Event | None:
        """Next event to indicate; the central's confirmation is implied."""
        out = ctypes.create_string_buffer(11)
        if not self.L.hush_next_event(self.h, out):
            return None
        ev = Event.unpack(out.raw)
        self.L.hush_indicated(self.h, ev.seq)
        return ev

    def disconnected(self):
        self.L.hush_disconnected(self.h)

    def status(self) -> dict:
        out = ctypes.create_string_buffer(10)
        self.L.hush_read_status(self.h, out)
        mv, wear, armed, pending, faults, rest, mx = struct.unpack("<HBBBBhh", out.raw)
        return {"battery_mv": mv, "wear": WEAR[wear], "wear_state": wear, "armed": bool(armed),
                "pending": pending, "faults": faults, "cal_rest_hpa": rest, "cal_max_hpa": mx}

    def status_bytes(self) -> bytes:
        out = ctypes.create_string_buffer(10)
        self.L.hush_read_status(self.h, out)
        return out.raw

    def adv(self) -> tuple[int, int]:
        return self.L.hush_adv_interval(self.h, self.t), self.L.hush_adv_flags(self.h)

    def save(self) -> bytes:
        out = ctypes.create_string_buffer(1024)
        n = self.L.hush_save(self.h, out)
        return out.raw[:n]
