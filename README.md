# Hush

[![ci](https://github.com/PunyaTaluka123/hush/actions/workflows/ci.yml/badge.svg)](https://github.com/PunyaTaluka123/hush/actions/workflows/ci.yml)

**A silent distress alarm worn as a lower-teeth retainer.** Pressing the tongue against a pad and holding for 3 seconds alerts your emergency contacts. Nothing is visible and nothing makes a sound. If the retainer is pulled out while armed and not put back in its case, contacts get a quiet heads-up, then a loud alarm that overrides silent mode unless the wearer enters their PIN.

There are two PINs. The real one cancels the alarm. The **duress PIN** looks exactly the same on the wearer's phone, but tells contacts "DURESS: call 999 now".

The system has four parts that share one protocol: **firmware** for an nRF52840 (Zephyr RTOS), a **Firebase backend** that owns every incident, an **Android app** for both wearers and contacts, and a **Python simulator** that runs the real firmware core against the backend emulators in CI.

| | |
| --- | --- |
| Spec | [docs/SPEC.md](docs/SPEC.md): threat model, 11 invariants, BLE protocol, incident state machine, 14 test scenarios |
| Status | [docs/NOTES.md](docs/NOTES.md): what is verified, deviations from the spec and why, open questions |

---

## The hard part: designing against an attacker who has the phone

The threat model (SPEC §2) assumes the attacker **physically controls the wearer, the retainer and the wearer's unlocked phone**. They can watch the screen, force a PIN, switch the phone off or uninstall the app. Most of the interesting design follows from that:

- **The server owns incidents (I2).** The phone only reports events; Cloud Functions open, time, escalate and resolve incidents. Killing the app doesn't stop an escalation, and a phone that goes offline for 90 s during an incident *is itself* an escalation signal (I9).
- **Duress must be indistinguishable on the phone (I1).** Both PINs show the same screens, the same 1.2 s animation, the same haptic, the same local history, and **requests of the same size**. The phone holds both PIN hashes as an unordered, unlabelled pair (argon2id), so it can say "Incorrect PIN" offline but can't tell which correct PIN was typed. It sends a fixed-size HMAC proof and the server decides. The words "duress" and "silent" appear nowhere in the app's UI, storage keys, logs or code, and a unit test greps for them.
- **Nothing alarming leaves the wearer's phone (I3, I4).** No SMS or call permissions at all. Every alert goes out from the server (FCM push, then Twilio SMS and voice), and the wearer's phone never plays audio during an incident.
- **Contacts can't silence it with one tap (I10).** "They're safe" requires confirming a voice conversation and typing the wearer's first name. Resolving a DURESS also needs an operator callback.
- **Firmware enforces its own safety limits (I11).** A compromised app can't set the trigger threshold to 5 % or the hold time to 0; the firmware rejects anything outside 20–95 % and 1.5–10 s.

## Architecture

```
 Retainer (nRF52840, Zephyr)                Wearer's phone (Android)              Firebase (asia-southeast1)
 ┌───────────────────────────┐   BLE GATT   ┌──────────────────────────┐  HTTPS   ┌─────────────────────────────┐
 │ pressure sensor → pattern │  encrypted,  │ foreground service       │ callable │ Cloud Functions             │
 │   detector (hold / 2×long)│  bonded      │ relays events, ACKs only │ + idem.  │  engine.ts: incident state  │
 │ wear state machine        │ ───────────► │   after server accepts   │ ───────► │   machine (applyTransition) │
 │ persistent event log      │ ◄─────────── │ PIN pad, heartbeat,      │          │  deliver.ts: outbox → FCM,  │
 │   (seq, ACK-based delete) │  ACK/haptic  │   location in incidents  │          │   Twilio SMS + voice        │
 └───────────────────────────┘              └──────────────────────────┘          │  Cloud Tasks timers + sweep │
                                                                                  │ Firestore (+ security rules)│
                                             Contact's phone (same app) ◄──────── └─────────────────────────────┘
                                             critical alarm, bypasses DND, live location page
```

**Incident lifecycle** (server-side, SPEC §6):

```
OPEN_QUIET ──timer / phone offline 90 s / 2nd trigger──► OPEN_LOUD
    │                                                       │
    ├── real PIN ──► CANCELLED ── duress PIN (≤10 min) ──┐  ├── contact, voice-confirmed ──► RESOLVED_BY_CONTACT
    └── duress PIN / 3 wrong PINs ───────────────────────┴──┴──► DURESS_ACTIVE ── contact + operator ──► RESOLVED
```

Every state change goes through one `applyTransition` inside a Firestore transaction and appends to a server-only audit log.

### Reliable event delivery

The retainer may be out of range when the wearer triggers it. Events go into a **persistent ring buffer** with a sequence number that stays monotonic across reboots, and are written to flash *before* they're sent over BLE. They're delivered in order by BLE indication and deleted only when the phone ACKs, which the phone does only after the server has accepted the event. Every request carries an idempotency key, so retries and duplicates never cause duplicate alerts (I8, scenarios S9 and S10).

## Repository layout

```
firmware/
  lib/hush_core.h          all device logic as portable C++: wear states, trigger pipeline,
                           event log, config limits, advertising, pairing
  lib/pattern_detector.h   tongue-press pattern recognition (hold / double long press, stuck detection)
  lib/hush.h               C ABI over the core, used by the Zephyr app *and* the Python simulator
  src/                     Zephyr glue in C: sensors, GATT v2 + LE Secure Connections, persistence, haptics
  tests/host/              C++ tests, incl. an 8 h simulated-wear trace (S14: zero false triggers)
  tests/ztest/             Zephyr tests on native_sim
backend/
  functions/src/engine.ts  incident state machine
  functions/src/deliver.ts outbox delivery: FCM, Twilio, per-incident cost cap
  functions/src/pins.ts    argon2id hashing, HMAC proof verification
  firestore.rules          wearer reads no incident; contacts read only linked wearers
  public/index.html        live-location page for contacts
app-android/               Kotlin + Jetpack Compose, wearer and contact roles in one app
sim/
  device.py                the real firmware core loaded via ctypes
  phone.py                 wearer-app protocol in Python
  ble_peripheral.py        the core exposed as a real BLE GATT peripheral (bless)
  tests/                   scenarios S1–S13 against the Firebase emulators with a fake clock
docs/                      SPEC.md, NOTES.md, pin_vector.json (shared crypto test vector)
```

**One core, three hosts.** All device behaviour lives in `hush_core.h` behind a small C ABI. The same code compiles into the Zephyr firmware, the host C++ tests, and a shared library that the Python simulator loads with `ctypes`. So the end-to-end scenario tests exercise the *actual* firmware logic, not a mock of it.

**One crypto test vector, three languages.** `docs/pin_vector.json` is checked by the Kotlin app, the TypeScript backend and the Python simulator, so PIN hashing and proofs can't drift apart.

## Testing

Each of the 11 invariants maps to at least one automated test ([table in NOTES.md](docs/NOTES.md)). CI runs four jobs on every push:

| Job | What runs |
| --- | --- |
| `firmware-host` | 76 C++ checks over the firmware core, incl. the 8 h false-trigger trace |
| `backend-e2e` | 32 pytest tests (scenarios S1–S13 plus invariant checks): real Cloud Functions on Auth/Firestore/Functions emulators, driven by the simulated device and phone |
| `android` | Robolectric unit tests (pixel-identical duress vs. cancel frames, no revealing strings, no SMS APIs, channel config), lint, debug build |
| `zephyr` | Firmware build for the Seeed XIAO nRF52840 + ztest on `native_sim` |

Run them locally:

```bash
make -C firmware/tests/host                       # firmware core (C++17)
./sim/run_e2e.sh                                  # needs JDK 21, Node 20, Python with argon2-cffi + pytest
cd app-android && ./gradlew testDebugUnitTest     # Android (JDK 21)
```

`run_e2e.sh` builds the firmware core as a shared library, builds the Cloud Functions, starts the emulators and runs every scenario.

## Running it

- **Backend locally**: `cd backend/functions && npm install && npm run emulators`
- **Dev-kit firmware** (Seeed XIAO nRF52840): in a west workspace (`west init -l firmware && west update`, or any Zephyr ≥ 4.0 / NCS v3.x), run `west build -b xiao_ble/nrf52840 firmware`, double-tap reset and copy `build/zephyr/zephyr.uf2` to the XIAO drive. Serial shell: `hush wear | remove | dock | magnet | button | status`.
- **v1 board with MCUboot**: add `--sysbuild -- -DEXTRA_CONF_FILE=dfu.conf` for BLE DFU (allowed only while docked).
- **Retainer simulator over real BLE**: `pip install bless && python sim/ble_peripheral.py`
- **App against the emulators**: debug builds point at `10.0.2.2` (the Android emulator's host). Set `hush.*` in `~/.gradle/gradle.properties` for a real Firebase project. The committed values are emulator placeholders.

## Status

Software-complete for the Android pilot and verified in simulation and CI. **Not yet run on hardware**: no firmware on a physical retainer, no BLE to a real phone, no loud alert through Do Not Disturb on a device, and power and latency are unmeasured. iOS waits on Apple's Critical Alerts entitlement. NOTES.md has the full list, along with the spec deviations and the security gaps found while building (e.g. requiring a PIN to remove contacts, because the attacker may hold the unlocked phone).

## Tech stack

C / C++17 · Zephyr RTOS · nRF52840 · BLE (LE Secure Connections) · TypeScript · Firebase (Cloud Functions, Firestore, Auth, FCM) · Cloud Tasks · Twilio · Kotlin · Jetpack Compose · Robolectric · Python · pytest · ctypes · argon2id · GitHub Actions
