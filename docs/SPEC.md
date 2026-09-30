# Hush — Software System Specification (handoff v1.0)

Owner: Punya. Date: 30 Sep 2026. Supersedes v0.1 (`hush.ino`, `bridge/protocol.py`).
Decisions D1–D5 and implementation notes are at the end (§15, §16).

## 1. What Hush is
Hush is a silent distress alarm worn as a lower-teeth retainer. It raises an alarm in two ways:

- **Tongue trigger.** Press the tongue forward and down against a pad behind the lower front teeth and hold for 3 s (alternative: two long presses). Contacts are alerted immediately.
- **Removal while armed.** If the retainer leaves the mouth without going back into its charging case, the wearer's phone vibrates silently. Contacts get a quiet heads-up. After the removal delay (60 s default) without a correct PIN, contacts' phones sound a loud alarm that overrides silent mode.

Any PIN prompt accepts two PINs: the **real PIN** cancels the alarm; the **duress PIN** looks exactly like cancelling on the wearer's phone but secretly escalates to "DURESS: call 999 now."
The wearer's phone never makes a sound during an incident.

## 2. Threat model
The attacker has physical control of the wearer, the retainer and the wearer's unlocked phone; can watch the screen and force PIN entry; can switch the phone off, use airplane mode, kill or uninstall the app.
The attacker does not have the contacts' phones, the backend, or Hush's cloud accounts.

Consequences: all timers and escalation live on the server; nothing visible on the wearer's phone differs between real and duress PIN; the wearer's phone going offline during an incident is itself an escalation signal.

## 3. Invariants (each needs an automated test)
| ID | Invariant |
|---|---|
| I1 | Duress looks identical to cancelling on the wearer's device: screens, animations, haptics, timing, local history, notifications, badge counts, network request shape and size, device logs (§7.3). |
| I2 | The server owns incidents. The phone reports events; the server opens, times, escalates and resolves. Killing the app must not stop an escalation. |
| I3 | No alert SMS or call is sent from the wearer's phone. All messages to contacts go out from the server. |
| I4 | The wearer's phone never plays audio during an open incident. Vibration and screen only. |
| I5 | Loud alerts to contacts override silent mode and DND where the platform allows (§9). |
| I6 | Location is shared only during an open incident or when the wearer explicitly starts sharing; never tracked in the background otherwise. |
| I7 | PINs are never stored or sent in plaintext. Only the server knows which stored PIN is the duress one. |
| I8 | Every event carries an idempotency key. Duplicates never produce duplicate alerts. |
| I9 | Wearer phone offline for 90 s during an open incident escalates it (quiet → loud) and contacts are told "wearer's phone went offline." |
| I10 | Contacts cannot silence an alarm with one tap; they must confirm they spoke to the wearer by voice (§8.3). |
| I11 | Firmware safety limits cannot be overridden by the app: threshold 20–95 %, hold 1.5–10 s; firmware rejects anything else. |

## 4. Components
| Component | Stack |
|---|---|
| Firmware | nRF Connect SDK (Zephyr), nRF52840 (ISP1807 in v1; Seeed XIAO nRF52840 for dev). `pattern_detector.h` ported unchanged. |
| Wearer app | Android first (Kotlin, Compose, minSdk 29); iOS second (Swift). Same binary has a "contact" role. |
| Backend | Firebase Auth (phone), Firestore, Cloud Functions (TypeScript, Node 20), Cloud Tasks, FCM + APNs. Region asia-southeast1. |
| Fallback messaging | Twilio SMS + voice TTS. |
| Device simulator | Python, from `bridge/hush_bridge.py`. |

## 5. Firmware
### 5.1 Signals (v1 board)
Tongue pressure: BMP585 in a sealed cavity under a silicone membrane (hPa relative to a tracked baseline). Temperature: BMP585. Tissue proximity: capacitive electrode (main in-mouth signal). Dock: hall switch + case magnet. Charging: wireless RX coil + charger IC. Haptic: vibration motor. Battery: Li-ion coin cell, voltage fuel gauge.
The dev kit (XIAO + force sensor) keeps working via a compile-time flag, with a force-sensor path and mock capacitive/hall inputs driven by shell commands.

### 5.2 Wear state machine
States DOCKED, WORN, OFF_BODY, UNKNOWN (boot). All transitions debounced.
- any → DOCKED: hall active ≥ 1 s
- DOCKED → OFF_BODY: hall inactive ≥ 1 s
- OFF_BODY → WORN: cap proximity above threshold AND 32–39 °C, both ≥ 10 s
- WORN → OFF_BODY: cap below threshold ≥ 3 s AND not docked. Without cap sensing: temperature drop > 2 °C within 60 s.

Arming is automatic on entering WORN if `auto_arm` (default on). DOCKED disarms without alarm. WORN → OFF_BODY while armed emits REMOVED_WHILE_ARMED. Docking inside the debounce window emits nothing.

### 5.3 Trigger detection
50 Hz via sensor FIFO, MCU drains every 200 ms. pct of calibrated range; slow baseline (τ ≈ 20 s), frozen during a press, temperature compensated. `hush::PatternDetector` defaults: threshold 55 %, hold 3000 ms, long 1500 ms, gap 150–1500 ms, refractory 10 s, stuck 30 s, release debounce 100 ms.
Cavity self-test every 10 min while not pressed; a cavity that has drifted to ambient (last docked reading as reference) means a leak → SENSOR_FAULT.

### 5.4 Event log
Persistent ring buffer ≥ 64 entries `{seq u32, uptime_ms u32, type u8, data u16}`; seq monotonic across reboot; delivered in order by BLE indication; removed only on ACK(seq) after the server accepted it.

| Code | Name | data |
|---|---|---|
| 1 | TRIGGER_HOLD | peak pct |
| 2 | TRIGGER_DOUBLE_LONG | peak pct |
| 3 | STUCK_HIGH | – |
| 4 | REMOVED_WHILE_ARMED | – |
| 5 | WORN | – |
| 6 | DOCKED | – |
| 7 | BATTERY_LOW | mV |
| 8 | SENSOR_FAULT | fault code |
| 9 | BOOT | reset reason |

### 5.5 BLE GATT v2 (little-endian)
| Characteristic | Props | Payload |
|---|---|---|
| EVENT | indicate | seq u32, uptime_ms u32, type u8, data u16 |
| ACK | write | seq u32 (everything ≤ seq delivered) |
| CONFIG | read, write | version u8=2, threshold_pct u8, hold_ms u16, pattern_mask u8, auto_arm u8, haptics u8 |
| CALIBRATE | write | 0x01 rest (3 s), 0x02 max (5 s) |
| STATUS | read, notify 30 s | battery_mV u16, wear_state u8, armed u8, pending_events u8, fault_flags u8, cal_rest_hPa i16, cal_max_hPa i16 |
| HAPTIC | write | 1 registered, 2 delivered, 3 error, 4 test |
| LIVE | notify while subscribed | pct u8 at 20 Hz |
| DFU | MCUmgr SMP | only while DOCKED |

Security: LE Secure Connections + bonding, all characteristics encrypted. Pairing only while DOCKED after a 5 s case-button hold; otherwise filter accept list. Max 2 bonds.
Advertising: 1 s normally; 20 ms for 30 s when an event is pending, then 100 ms until delivered. Manufacturer data flag byte: bit0 pending, bit1 armed.
Haptics: trigger registered 1 × 150 ms; HAPTIC(2) 3 × 80 ms; no haptic for REMOVED_WHILE_ARMED.

### 5.6 Power targets (PPK2)
WORN connected idle ≤ 60 µA; OFF_BODY slow advertising ≤ 25 µA; DOCKED unlimited.

## 6. Incident model (server)
### 6.1 Types
| Type | Opened by | Contacts at t=0 | Loud |
|---|---|---|---|
| TONGUE | TRIGGER_HOLD / DOUBLE_LONG | loud | t=0 |
| REMOVAL | REMOVED_WHILE_ARMED | quiet heads-up (push + SMS) | removal delay unless real PIN |
| DURESS | duress PIN in an open incident or at manual disarm | upgrade message | immediately |
| LINK_LOST | BLE link down while armed and worn | none (wearer only) | only via I9 on an overlapping incident |

### 6.2 States
OPEN_QUIET → OPEN_LOUD → (CANCELLED | RESOLVED_BY_CONTACT | DURESS_ACTIVE → RESOLVED)
- OPEN_QUIET → OPEN_LOUD: removal timer; wearer phone offline 90 s (I9); a second trigger.
- OPEN_* → CANCELLED: real PIN. Contacts get "Cancelled by [name] at 21:04."
- OPEN_* or CANCELLED (≤ 10 min) → DURESS_ACTIVE: duress PIN (re-opens a cancelled incident).
- OPEN_* → RESOLVED_BY_CONTACT: contact with voice confirmation (§8.3).
- DURESS_ACTIVE → RESOLVED: contact voice confirmation + operator callback (D2), or 24 h + operator note. The wearer's phone can never resolve duress.
- A new trigger during an open REMOVAL upgrades it to TONGUE (loud now).
- After CANCELLED the server sends no pushes of any kind to the wearer's phone about it.

### 6.3 Heartbeat
Every 60 s while armed: battery, wear state, BLE link state, location only if an incident is open. Server keeps `last_seen`. A sweep applies I9 and LINK_LOST (BLE down > 90 s while WORN + armed → wearer only).

## 7. PIN and duress
### 7.1 Setup
Real PIN 4–6 digits; duress PIN different, same length. Suggested scheme "last digit + 1" with a practice mode. Sent once over TLS to `setPins`; server stores `argon2id(pin, per_user_salt)` labelled.
### 7.2 Verification
Phone stores both hashes as an unordered, unlabelled pair using a server-issued hashing key in Android Keystore (offline "Incorrect PIN"). `submitPin(incident_id, pin_proof)`: HMAC keyed by a per-session nonce; server decides. Fixed, padded request size. Wrong PIN: "Incorrect PIN. Try again."; 3 consecutive wrong during an open incident → silent DURESS (D1: yes).
### 7.3 Indistinguishability checklist (automated)
Same screens/order, same "Alarm cancelled" screen, same 1.2 s success animation, same haptic. UI completes on a fixed schedule, never waits for the server; offline proofs are queued. Same local history text ("Cancelled 21:04"). No notification/badge/banner about a duress incident, ever. App returns to idle "Armed / Protected"; location streams silently for 6 h every 15 s in a foreground service whose notification matches the armed notification. "duress"/"silent" never appear in UI strings, local DB, logcat or crash reports. Test: both paths on an emulator, fixed-time screenshots pixel-identical except the clock; compare request sizes and local DB.

## 8. Contacts
### 8.1 Onboarding
Invite by phone → SMS link. App → push; no app → SMS + voice only. Contact must accept. 1–5 contacts per wearer; a contact may cover several wearers.
### 8.2 Alert content
| Stage | Channel | Content |
|---|---|---|
| REMOVAL heads-up | push (normal) + SMS | "Heads-up: [Name]'s Hush was removed while armed. We'll alert you loudly in 60 s unless they cancel. You don't need to do anything yet." |
| Loud | critical push + SMS + voice (repeat every 2 min, ≤ 3, until acked) | "EMERGENCY: [Name] triggered Hush. They may be unable to speak. Live location: [link]. Call 999 if you can't confirm they're safe. Do NOT call or text their phone." |
| DURESS | critical push + SMS + voice | "DURESS: [Name] was forced to cancel their alarm. Call 999 now and give this location: [link]. Do NOT contact [Name]'s phone." |
| Cancelled | push + SMS | "[Name] cancelled the alarm at 21:04 with their PIN." |
| Phone offline | added to current alert | "[Name]'s phone went offline at 21:05." |
Live location link → server-hosted page, login required, trail refreshed every 15 s.
### 8.3 Contact actions
"I'm on it" (others see who responds); "Call 999" (dialer + address/coordinates card); "They're safe" requires (a) "I spoke to [Name] by voice call or in person" and (b) typing the wearer's first name. DURESS additionally needs the operator callback (D2).

## 9. Platform notifications
iOS: Critical Alerts entitlement (apply early); until granted, time-sensitive + Twilio voice.
Android contact: channel `hush_emergency` IMPORTANCE_HIGH, USAGE_ALARM, full-screen intent; request DND access; alarm stream until acknowledged.
Android wearer: `connectedDevice` foreground service; battery-optimisation exemption; Doze-tolerant (high-priority FCM). Incident UI: vibration + full-screen PIN pad, no sound (I4).

## 10. Authorities
Out of scope for v1. `AuthorityChannel` interface with a no-op default; TODO for SPF emergency SMS 70999 once a protocol is agreed. DURESS tells contacts to call 999.

## 11. Backend
### 11.1 Firestore
```
users/{uid}                         role flags, name, phone, settings; private/pins server-only
devices/{deviceId}                  owner, fw, last STATUS, bonded phone
contacts/{uid}/links/{phone}        state invited|active, priority, contact_uid
incidents/{id}                      wearer, type, state, opened_at, timers
incidents/{id}/events               append-only audit (server-only)
incidents/{id}/locations            {t, lat, lng, acc}; TTL 30 d after close
push_tokens/{uid}/tokens/{token}
```
Rules: wearer writes events/heartbeats only for own devices; contacts read incidents only for wearers they're actively linked to; nobody but Functions reads pin hashes or real-vs-duress fields; the wearer's client reads no incident at all.
### 11.2 Functions (callable, every request has `idempotency_key`)
registerDevice, reportDeviceEvents, heartbeat, pushLocation, submitPin (always `{ok:true}`), setPins, inviteContact, acceptInvite, contactAck, contactResolve, timerFire. All state changes go through `applyTransition` in a transaction and append to the audit log.

## 12. Simulator and scenarios
`sim/`: BLE peripheral emulator (BlueZ) + headless mode against the backend emulator; scripted wear/dock/trigger sequences. Scenarios (Firebase emulator, fake clock):

| # | Scenario | Expected |
|---|---|---|
| S1 | Tongue hold while worn | loud alert < 8 s p95; device gets HAPTIC(2) |
| S2 | Removed, docked within 3 s | no incident |
| S3 | Removed, real PIN at 20 s | heads-up then cancelled; never loud |
| S4 | Removed, no PIN | loud at 60 s |
| S5 | Removed, duress PIN at 20 s | wearer identical to S3; contacts get DURESS |
| S6 | Real PIN then duress 5 min later | re-opened as DURESS |
| S7 | Airplane mode during REMOVAL | loud within 90 s; "phone offline" note |
| S8 | App killed during TONGUE | contacts still escalate |
| S9 | Out of range when triggered, reconnect after 2 min | delivered once, in order; one incident |
| S10 | Same event re-sent 5 times | one incident |
| S11 | "They're safe" without voice confirmation | refused |
| S12 | 3 wrong PINs | silent DURESS; wearer sees only retry |
| S13 | Config threshold 5 % | rejected by firmware |
| S14 | 8 h simulated wear | 0 triggers |

## 13. Privacy, security, compliance
No location outside incidents, no analytics SDKs, nothing to third parties except Twilio message content. Locations deleted 30 d after close; audit log kept 1 year. PDPA plain-language notice; contacts consent on accepting. Secrets in Secret Manager. Every notification logged with its delivery receipt.

## 14. Milestones
M1 firmware on XIAO (mocked sensors, event log, GATT v2, security): host tests + ztest; S9, S10, S13.
M2 backend incident engine: S1–S8, S10, S12 headless.
M3 Android wearer app: S1–S5, S7, S8 on device; §7.3 automated.
M4 contact role + critical alerts + Twilio: S11; loud alert overrides DND on a test phone.
M5 hardening: battery, Doze, reconnection, content-free crash reporting; 24 h soak.

## 15. Owner decisions (30 Sep 2026)
| # | Question | Decision |
|---|---|---|
| D1 | 3 wrong PINs silently escalate to DURESS? | **Yes.** |
| D2 | Resolving DURESS | **One contact (voice-confirmed) + a Hush operator callback.** 24 h + operator note remains the fallback. |
| D3 | REMOVAL loud delay | **User-settable, 30–120 s**, default 60 s. |
| D4 | iOS timing | **After the Android pilot** (delegated to implementer). Reason: iOS loud alerts depend on Apple's Critical Alerts entitlement, which is the long pole. File the entitlement request now; build iOS once M3/M4 have settled the protocol. |
| D5 | Provider and budget | **Twilio.** Cap **US$10 of Twilio spend per incident** (delegated to implementer). The first loud and first DURESS message to every contact always go out; the cap only suppresses repeat calls and informational SMS. Cost is estimated at send time (SMS $0.08, call $0.25) and logged. |

Further decisions (30 Sep 2026, raised during implementation):

| # | Question | Decision |
|---|---|---|
| D6 | I1 vs I6: the phone can't tell which PIN was typed, so §7.3's 6 h location streaming happens after every cancel | **Keep 6 h on both paths; the server stores points only for DURESS_ACTIVE and drops them otherwise.** |
| D7 | Tongue trigger shows the full-screen cancel screen and vibrates (§9), visible to someone holding the phone | **As spec.** |
| D8 | Any magnet disarms (DOCKED = hall only) | **DOCKED requires the hall switch AND charging power from the case** (magnet alone keeps an existing DOCKED state, so a full case that stops charging stays docked). |

## 16. Implementation notes and open questions
See `docs/NOTES.md`.
