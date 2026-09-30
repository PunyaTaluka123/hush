"""BLE peripheral emulator (SPEC §12): a simulated retainer (the real firmware
core, via device.py) advertised over real Bluetooth with the GATT v2 layout,
so app-android can be developed and tested without hardware.

  pip install bless                  # BlueZ on Linux; CoreBluetooth on macOS
  make -C firmware/tests/host lib
  python sim/ble_peripheral.py       # starts worn and calibrated

Console: hold | double | remove | dock | wear | reboot | status | quit

Limits vs the real retainer: bonding/encryption is whatever the host stack
enforces for "encryption required" attributes, there is no DFU service, and
indication confirmations are not visible through bless, so un-ACKed events
are re-indicated every 5 s (the phone and server de-duplicate by seq).
"""
from __future__ import annotations

import asyncio
import struct
import sys

from bless import BlessServer, GATTAttributePermissions as Perm, GATTCharacteristicProperties as Prop

from device import ACK, CALIBRATE, CONFIG, EVENT, HAPTIC, LIVE, STATUS, SVC, Device
ENC_R, ENC_W = Perm.readEncryptionRequired, Perm.writeEncryptionRequired


class Peripheral:
    def __init__(self):
        self.dev = Device()
        self.dev.wear()
        self.dev.calibrate()
        self.server: BlessServer | None = None
        self.last_indicate = 0.0

    def read(self, ch, **_):
        u = str(ch.uuid).lower()
        if u == CONFIG:
            return bytearray(self.dev.read_config())
        if u == STATUS:
            return bytearray(self.dev.status_bytes())
        return bytearray()

    def write(self, ch, value: bytearray, **_):
        u, v = str(ch.uuid).lower(), bytes(value)
        ok = {ACK: lambda: self.dev.write_ack(struct.unpack("<I", v)[0]) if len(v) == 4 else False,
              CONFIG: lambda: self.dev.write_config(v),
              CALIBRATE: lambda: self.dev.write_calibrate(v[0]) if len(v) == 1 else False,
              HAPTIC: lambda: self.dev.write_haptic(v[0]) if len(v) == 1 else False}.get(u, lambda: False)()
        if not ok:
            raise ValueError("rejected")  # surfaces as an ATT error on the central (I11)

    def send(self, u: str, payload: bytes):
        self.server.get_characteristic(u).value = bytearray(payload)
        self.server.update_value(SVC, u)

    async def tick(self):
        loop = asyncio.get_running_loop()
        n = 0
        while True:
            await asyncio.sleep(0.2)
            self.dev.run(200)
            for h in self.dev.haptics:
                print(f"  [haptic pattern {h}]")
            self.dev.haptics.clear()
            n += 1
            if n % 150 == 0:
                self.send(STATUS, self.dev.status_bytes())  # every 30 s
            if loop.time() - self.last_indicate > 5:
                self.dev.disconnected()  # re-offer anything not yet ACKed
            while (ev := self.dev.indicate()) is not None:
                self.send(EVENT, struct.pack("<IIBH", ev.seq, ev.uptime_ms, ev.type, ev.data))
                self.last_indicate = loop.time()
                print(f"  -> EVENT seq={ev.seq} {ev.name} data={ev.data}")
            self.send(LIVE, bytes([min(100, int(self.dev.press_hpa / self.dev.SPAN * 100))]))

    async def console(self):
        loop = asyncio.get_running_loop()
        while True:
            cmd = (await loop.run_in_executor(None, sys.stdin.readline)).strip()
            d = self.dev
            if cmd == "hold":
                await asyncio.to_thread(d.press, 3300, 72)
            elif cmd == "double":
                d.press(1800, 70, 500); d.press(1800, 70)
            elif cmd == "remove":
                d.remove()
            elif cmd == "dock":
                d.dock()
            elif cmd == "wear":
                d.wear()
            elif cmd == "reboot":
                self.dev = d.reboot()
            elif cmd == "status":
                print(d.status(), "adv", d.adv())
            elif cmd == "quit":
                return

    async def run(self):
        self.server = BlessServer(name="Hush")
        self.server.read_request_func, self.server.write_request_func = self.read, self.write
        await self.server.add_new_service(SVC)
        chars = [(EVENT, Prop.read | Prop.indicate, ENC_R), (ACK, Prop.write, ENC_W),
                 (CONFIG, Prop.read | Prop.write, ENC_R | ENC_W), (CALIBRATE, Prop.write, ENC_W),
                 (STATUS, Prop.read | Prop.notify, ENC_R), (LIVE, Prop.notify, ENC_R), (HAPTIC, Prop.write, ENC_W)]
        for u, props, perms in chars:
            await self.server.add_new_characteristic(SVC, u, props, None, perms)
        await self.server.start()
        print(f"Hush simulator advertising {SVC}. Commands: hold double remove dock wear reboot status quit")
        tick = asyncio.create_task(self.tick())
        await self.console()
        tick.cancel()
        await self.server.stop()


if __name__ == "__main__":
    asyncio.run(Peripheral().run())
