# Hush

Silent distress alarm worn as a lower-teeth retainer. Spec: [docs/SPEC.md](docs/SPEC.md). Status, deviations and open questions: [docs/NOTES.md](docs/NOTES.md).

```
firmware/lib/        hush_core.h: all device logic (wear states, trigger pipeline, event log, config limits, advertising,
                     pairing) + pattern_detector.h (v0.1, unchanged) + a C ABI (hush.h) used by the firmware and the simulator
firmware/src/        Zephyr glue in C: sensors (XIAO dev kit / v1), GATT v2 + security, persistence, haptics
firmware/tests/      host/ (C++ tests incl. S14) · ztest/ (native_sim)
backend/             Firebase: functions/src (engine.ts = incident state machine), firestore.rules, public/ (contact web page)
sim/                 device.py (firmware core via ctypes), phone.py (wearer app protocol), ble_peripheral.py (GATT over BLE),
                     tests/ (S1–S13 against the emulators)
app-android/         Kotlin/Compose app, wearer + contact roles
docs/                SPEC.md, NOTES.md, pin_vector.json (shared argon2id/HMAC test vector)
```

## Tests

```bash
make -C firmware/tests/host
```

```bash
./sim/run_e2e.sh
```

The second command builds the firmware core for the simulator and the Cloud Functions, starts the Auth/Firestore/Functions emulators (needs JDK 21: `brew install openjdk@21`) and runs every scenario. Python needs `argon2-cffi` and `pytest`.

```bash
cd app-android && ./gradlew testDebugUnitTest
```

Android unit tests (JVM, Robolectric) cover §7.3, I3, I4 and I5.

## Run it
- Backend locally: `cd backend/functions && npm install && npm run emulators`
- Dev-kit firmware (Seeed XIAO nRF52840): in a west workspace (`west init -l firmware && west update`, or any Zephyr ≥ 4.0 / NCS v3.x), `west build -b xiao_ble/nrf52840 firmware`, then double-tap reset and copy `build/zephyr/zephyr.uf2` to the XIAO drive. Serial shell: `hush wear | remove | dock | magnet | button | status`.
- v1 board with MCUboot: add `--sysbuild -- -DEXTRA_CONF_FILE=dfu.conf` for BLE DFU (only while docked).
- Retainer simulator over real BLE: `pip install bless && python sim/ble_peripheral.py`
- App against the emulators: debug builds point at `10.0.2.2` (the Android emulator's host); set `hush.*` in `~/.gradle/gradle.properties` for a real project.
