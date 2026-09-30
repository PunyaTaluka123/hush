# Implementation notes (SPEC §16)

Status as of 30 Sep 2026. "Verified" means an automated check ran and passed on the development Mac.

## Invariant → automated check (all run and passing on 30 Sep 2026)

| ID | Check | Where |
|---|---|---|
| I1 | Wearer-observable behaviour identical for both PINs: fixed-time frames pixel-identical (and non-blank), same UI verdict, haptic, history, request sizes, local DB; server sends the wearer nothing; wearer can read no incident | `app-android/.../IncidentScreenTest`, `WearerTest.bothPinsLookIdenticalOnThePhone`, `submitPinIsFixedSize`, `sim/tests/test_scenarios.py::test_s5_*` |
| I1 | "duress"/"silent" nowhere in the app (UI, storage keys, logs, code) | `InvariantTest.noRevealingWordsInTheApp` |
| I2 | App killed → server still escalates | `test_s8_app_killed_contacts_still_escalate` |
| I3 | No SMS/call permissions or APIs in the app | `InvariantTest.theWearersPhoneNeverTextsOrCalls_I3` |
| I4 | Wearer channels silent and badge-free; audio APIs only in the contact alarm | `InvariantTest.audioOnlyInTheContactsAlarm_I4`, `channels_I4_I5` |
| I5 | `hush_emergency`: IMPORTANCE_HIGH, USAGE_ALARM, bypasses DND; critical payloads for loud/DURESS | `InvariantTest.channels_I4_I5`, priority checks in S1/S5 |
| I6 | Server stores locations only for OPEN_*/DURESS_ACTIVE; phone sends none outside its alarm window | `test_i6_location_only_during_open_incident`, `WearerTest.noLocationOutsideAnAlarm` |
| I7 | argon2id at rest, server-only, fixed-size proof; Kotlin, Python and backend crypto agree | `test_i7_pins_hashed_and_server_only`, `InvariantTest.pinCryptoMatchesTheBackend` (docs/pin_vector.json) |
| I8 | Duplicates never double-alert | `test_s10_*`, `test_s9b_*`, `test_i8_*`, `WearerTest.retainerIsAckedOnlyAfterTheServerAccepted` |
| I9 | Phone offline 90 s → loud + note | `test_s7_*`, `test_s7b_*` |
| I10 | No one-tap silence; DURESS needs the operator callback (D2) | `test_s11_*`, `test_d2_*` |
| I11 | Firmware rejects out-of-range config | `firmware/tests/host/test_core.cpp`, `sim/tests/test_device.py::test_s13_*` |

Scenarios: S1–S12 in `sim/tests/test_scenarios.py` (emulators, fake clock), S13 in `sim/tests/test_device.py`, S14 in `firmware/tests/host/test_core.cpp` (8 h trace through the whole firmware core: 0 triggers, and a deliberate hold still fires afterwards). D8 (dock needs power): 4 host tests + `test_bare_magnet_does_not_hide_a_removal`.

Counts: firmware host 2 suites (all checks pass) · sim + emulator 32 · Android 14 + lint clean.

## Builds
- Firmware, Zephyr v4.2.0 + SDK 0.17.2, `xiao_ble/nrf52840`: compiles with no warnings (flash 255 KB / 788 KB, RAM 50 KB). Also compiled: the DFU variant (`dfu.conf` + an MCUboot slot layout) and the ztest suite.
- Android: `assembleDebug`, `lintDebug` (0 errors), `testDebugUnitTest`.

## What has not been run yet
- **ztest on native_sim**: Linux only; wired into `.github/workflows/ci.yml` (never run; the CI file itself is untested). It also builds for the XIAO, so it can be flashed to a board.
- **On hardware / devices**: no firmware on a XIAO, no app on a phone, no BLE between them; `sim/ble_peripheral.py` (needs `bless`) not run; no loud alert through DND on a real phone (M4); power (§5.6), BLE range, and real FCM/Twilio latency unmeasured (emulator S1 p95 0.08 s).
- **Contact web page** (`backend/public/index.html`) not exercised in a browser.
- **nRF Connect SDK**: verified on upstream Zephyr v4.2.0 with the same APIs; not built on NCS itself.
- **v1 board path** (`sensors.c` non-devkit branch: BMP585, hall, power-good GPIO) needs the v1 board's devicetree before it can compile.
- **iOS**: not started (D4: after the Android pilot).

## Deviations from the spec (each is small; say if you want the spec's version)
1. **pct formula** (§5.3): divides by `cal_max − cal_rest`, not `cal_max − baseline`. The spec's form divides by ~0 if the baseline drifts up (warm mouth, membrane creep), which turns noise into triggers. Identical right after calibration.
2. **Sweep every 1 min, not 30 s** (§6.3): Cloud Scheduler's minimum. Exact timer times come from Cloud Tasks (one task per due time, de-duplicated); the sweep is the backstop.
3. **LINK_LOST** is a wearer push plus a state flag, not an incident document. It never alerts contacts on its own (as §6.1 says), so it has no state machine to run.
4. **Audit log** lives only in `incidents/{id}/events` (server-only), not in an `audit[]` array on the incident. An array that contacts can read would show *why* an incident became DURESS.
5. **Links keyed by phone number** (`contacts/{wearer}/links/{+65…}`), not contact uid: the uid does not exist until the contact accepts. The rules match on the signed-in phone number.
6. **`setPins` fields are `pin` / `pin2`**, and `submitPin` has a `purpose` (`cancel` | `disarm`). Neutral names keep the phone's code and traffic free of "duress" (§7.3).
7. **"Per-session nonce"** = a per-user nonce rotated on every `setPins`; replay protection comes from the single-use idempotency key inside the MAC. A nonce per app session would break proofs queued offline across sessions.
8. **Removal delay** (D3) applies to new incidents; an open one keeps the delay it started with.
9. **DFU is not in the dev-kit build.** The XIAO ships with a UF2 bootloader (update over USB); MCUmgr + MCUboot live in `dfu.conf` for boards with MCUboot slots (v1).

## Additions not in the spec (security gaps found while building)
- **PIN required for settings and contact removal** (`updateSettings`, `removeContact`). The attacker may hold the unlocked phone (§2); without this they could remove contacts or stretch the delay. A second PIN typed at these prompts escalates silently and the change still applies, so it looks identical.
- **Advertising without name or service UUID** outside the pairing window, plus BLE privacy (RPA). A stranger's scanner does not see "Hush". The flag byte (§5.5) is still there.
- **Pairing window when both bond slots are full clears the bonds** (max 2, §5.5), so a lost phone can't lock the owner out. Entering the window already requires physical access to the retainer and case.
- **Persist-before-indicate**: an event is never indicated before it is in flash, so a reboot can't reuse its seq.
- **Device generation** in `devices/{id}`: bumped on each `registerDevice` (new bond), so a flash-erased retainer that restarts seq at 1 isn't de-duplicated against old events.
- **HAPTIC(3)** when a trigger arrives and the wearer has no active contacts.
- **Operator role** (`operatorResolve`, custom claim `operator`) for D2.

Decided since (SPEC §15): D6 keep the 6 h post-cancel streaming with the server dropping points; D7 tongue screen as spec; D8 docking needs charging power.

## Open questions for the owner
1. **Phone switched off or app uninstalled *before* the retainer is removed** produces no alert (LINK_LOST is wearer-only, and the retainer's event never reaches the server). Option: a quiet contact heads-up after N minutes of silence while armed and worn.
2. **Suggested PIN scheme "last digit + 1"** is public, so an attacker who knows it can try the neighbouring PIN. It is only a suggestion in onboarding; consider dropping it.
3. **The iOS Critical Alerts entitlement** needs filing now (D4); until then iOS contacts get time-sensitive notifications plus Twilio calls (`IOS_CRITICAL_ALERTS=false`).

## Cost cap (D5)
US$10 of estimated Twilio spend per incident (SMS $0.08, call $0.25), tracked on the incident. The first loud and first DURESS messages to every contact always go out. Over the cap, repeat calls and informational SMS are skipped and each skip is logged (`budget_skip`). With 5 contacts, a full loud round with 3 repeat calls, then DURESS with its repeats, hits the cap on the last DURESS round (`test_d5_budget_cap_keeps_first_alerts`).

## Before production
- Twilio: set `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` in Secret Manager, `TWILIO_FROM` and `PUBLIC_FUNCTIONS_URL` params, and point status callbacks at `twilioStatus`.
- Firestore TTL policies are declared in `firestore.indexes.json` (`expire_at` on events, outbox, idem, devevents, incidents).
- `minInstances: 1` on `reportDeviceEvents`, `submitPin` and `deliverOutbox` (no cold start on the alarm path) costs a few dollars a month.
- Bluetooth SIG company ID for the manufacturer data (currently the 0xFFFF test ID).
- App Check on the callables.
- Content-free crash reporting (M5).
