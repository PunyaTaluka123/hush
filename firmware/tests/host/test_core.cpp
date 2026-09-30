// Host tests for firmware/lib/hush_core.h (everything but hardware I/O).
//   make -C firmware/tests/host
#include <cstdio>
#include <random>

#include "hush_core.h"
#include "traces.h"

using namespace hush;

static int failures = 0;
#define CHECK(cond, msg)                                                   \
  do {                                                                     \
    if (!(cond)) { std::printf("  FAIL: %s  (line %d)\n", msg, __LINE__); failures++; } \
    else { std::printf("  ok:   %s\n", msg); }                             \
  } while (0)

static const float REST = 1000.f, MAX = 1100.f;  // hPa: 1 % of range == 1 hPa

struct Rig {
  Core c;
  uint32_t t = 1000;
  Inputs in{REST, 36.5f, false, false, 1, false};
  std::vector<LogEntry> got;  // everything the phone would have received

  Rig(bool calibrate = true) {
    c.boot(t, 1);
    if (calibrate) { c.pr.cal_rest = REST; c.pr.cal_max = MAX; }
  }
  void run(uint32_t ms) {
    for (uint32_t end = t + ms; t < end; t += DT) c.sample(t, in);
  }
  void pct(float v) { in.p_hpa = c.pr.baseline() + v; }
  void wear(int8_t cap = 1) { in.hall = in.power = false; in.cap = cap; in.temp_c = 36.5f; run(12000); }
  void dock() { in.hall = in.power = true; in.cap = 0; in.temp_c = 25; }
  // phone connected: drain, and ACK everything received
  void drain() {
    while (const LogEntry* e = c.log.next_unsent()) { got.push_back(*e); c.log.mark_sent(e->seq); }
    uint8_t a[4]; put32(a, got.empty() ? 0 : got.back().seq); c.write_ack(a, 4);
  }
  int count(uint8_t type) { drain(); int n = 0; for (auto& e : got) n += e.type == type; return n; }
};

static bool cfg_ok(uint8_t ver, uint8_t thr, uint16_t hold, uint8_t mask = 3, uint8_t arm = 1, uint8_t hap = 1) {
  Core c; uint8_t b[CONFIG_LEN] = {ver, thr, 0, 0, mask, arm, hap}; put16(b + 2, hold);
  return c.write_config(b, CONFIG_LEN);
}

int main() {
  std::mt19937 rng(7);

  std::puts("config limits (I11, S13)");
  CHECK(!cfg_ok(2, 5, 3000), "S13: threshold 5 % rejected");
  CHECK(!cfg_ok(2, 19, 3000) && cfg_ok(2, 20, 3000) && cfg_ok(2, 95, 3000) && !cfg_ok(2, 96, 3000),
        "threshold accepted exactly on 20..95");
  CHECK(!cfg_ok(2, 55, 1499) && cfg_ok(2, 55, 1500) && cfg_ok(2, 55, 10000) && !cfg_ok(2, 55, 10001),
        "hold accepted exactly on 1500..10000 ms");
  CHECK(!cfg_ok(1, 55, 3000), "v1 config layout rejected");
  CHECK(!cfg_ok(2, 55, 3000, 0) && !cfg_ok(2, 55, 3000, 4), "pattern mask must enable a known pattern");
  CHECK(!cfg_ok(2, 55, 3000, 3, 2) && !cfg_ok(2, 55, 3000, 3, 1, 2), "auto_arm / haptics are booleans");
  { Core c; uint8_t b[CONFIG_LEN] = {2, 5, 0xB8, 0x0B, 3, 1, 1}, out[CONFIG_LEN];
    c.write_config(b, CONFIG_LEN); c.cfg.pack(out);
    CHECK(out[1] == 55 && !c.settings_dirty, "rejected write leaves the stored config untouched");
    CHECK(!c.write_config(b, 6), "short write rejected"); }

  std::puts("\nevent log");
  { EventLog l;
    for (int i = 0; i < 3; i++) l.append(EV_WORN, i, i);
    const LogEntry* e = l.next_unsent();
    CHECK(e && e->seq == 1, "first event has seq 1");
    l.mark_sent(1); l.mark_sent(2);
    CHECK(l.next_unsent()->seq == 3, "delivery continues in order");
    l.on_disconnect();
    CHECK(l.next_unsent()->seq == 1 && l.pending() == 3, "un-ACKed events re-sent after reconnect");
    l.ack(2);
    CHECK(l.pending() == 1 && l.next_unsent()->seq == 3, "ACK(2) removes everything <= 2 only");
    uint8_t blob[EventLog::BLOB_MAX]; size_t n = l.save(blob);
    EventLog r; CHECK(r.load(blob, n) && r.pending() == 1, "pending events survive reboot");
    CHECK(r.append(EV_BOOT, 0, 0).seq == 4, "seq keeps counting across reboot");
    blob[0] ^= 1; CHECK(!EventLog().load(blob, n), "corrupt blob rejected"); }
  { EventLog l;
    l.append(EV_TRIGGER_HOLD, 70, 0);
    for (int i = 0; i < 100; i++) l.append(EV_WORN, 0, i);
    CHECK(l.pending() == EventLog::CAP && l.next_unsent()->type == EV_TRIGGER_HOLD,
          "full log evicts status events before the trigger"); }

  std::puts("\nwear state machine");
  { Rig r; r.in.cap = 0; r.in.temp_c = 22; r.run(1500);
    CHECK(r.c.wear.state == WEAR_OFF_BODY, "boot, no magnet -> OFF_BODY after 1 s");
    r.in.cap = 1; r.in.temp_c = 36.5f; r.run(9500);
    CHECK(r.c.wear.state == WEAR_OFF_BODY, "not WORN before 10 s in mouth");
    r.run(700);
    CHECK(r.c.wear.state == WEAR_WORN && r.c.wear.armed && r.count(EV_WORN) == 1, "WORN + armed after 10 s");
    r.in.cap = 0; r.run(2800);
    CHECK(r.c.wear.state == WEAR_WORN, "cap dropout < 3 s ignored (e.g. tongue movement)");
    r.run(400);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 1, "removed while armed -> REMOVED_WHILE_ARMED"); }
  { Rig r; r.wear(); r.drain(); r.got.clear();
    r.in.cap = 0; r.run(2000); r.in.hall = true; r.run(800); r.in.power = true; r.run(3000);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 0 && r.count(EV_DOCKED) == 1 && !r.c.wear.armed,
          "S2: removed and docked within 3 s (charger up 0.8 s later) -> DOCKED only, disarmed"); }
  { Rig r; r.wear(); r.drain(); r.got.clear();
    r.in.cap = 0; r.run(1000); r.in.hall = true; r.run(10000);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 1 && r.count(EV_DOCKED) == 0 && r.c.wear.state == WEAR_OFF_BODY,
          "bare magnet (no charging power) during removal: alarm still fires, never DOCKED"); }
  { Rig r; r.in.hall = true; r.wear(); r.in.hall = true; r.run(5000); r.drain(); r.got.clear();
    r.in.cap = 0; r.run(4000);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 1, "magnet held to the retainer while worn, then removed: alarm fires"); }
  { Rig r; r.dock(); r.run(2000); r.in.power = false; r.run(60000);
    CHECK(r.c.wear.state == WEAR_DOCKED, "case stops charging when full: stays DOCKED on the magnet");
    r.in.hall = false; r.run(1500);
    CHECK(r.c.wear.state == WEAR_OFF_BODY, "taken out: OFF_BODY"); }
  { Rig r; r.wear(); r.dock(); r.run(1200);
    r.in.hall = r.in.power = false; r.in.cap = 0; r.in.temp_c = 22; r.run(5000);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 0 && r.c.wear.state == WEAR_OFF_BODY, "taken out of the case: no alarm"); }
  { Rig r; uint8_t b[CONFIG_LEN]; r.c.cfg.auto_arm = 0; r.c.cfg.pack(b); r.c.write_config(b, CONFIG_LEN);
    r.wear(); r.in.cap = 0; r.run(4000);
    CHECK(!r.c.wear.armed && r.count(EV_REMOVED_WHILE_ARMED) == 0, "auto_arm off: WORN but never armed"); }
  { Rig r; r.wear(-1);
    CHECK(r.c.wear.state == WEAR_WORN, "no capacitive sensing: WORN on temperature alone");
    for (int i = 0; i < 30; i++) { r.in.temp_c -= 0.1f; r.run(1000); }
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 1, "no capacitive sensing: > 2 °C drop in 60 s -> removed");
    r.run(60000);
    CHECK(r.count(EV_REMOVED_WHILE_ARMED) == 1, "...and a still-warm retainer does not re-arm and fire again"); }

  std::puts("\ntrigger pipeline (hPa -> pct -> detector)");
  { Rig r; r.wear(); r.run(5000);
    std::normal_distribution<float> n(0, 2);
    for (uint32_t end = r.t + 3300; r.t < end; r.t += DT) { r.in.p_hpa = REST + 72 + n(rng); r.c.sample(r.t, r.in); }
    r.in.p_hpa = REST; r.run(2000);
    r.drain();
    int holds = 0; uint16_t peak = 0;
    for (auto& e : r.got) if (e.type == EV_TRIGGER_HOLD) { holds++; peak = e.data; }
    CHECK(holds == 1 && peak >= 70 && peak <= 80, "3.3 s press at 72 % -> one TRIGGER_HOLD carrying peak pct");
    CHECK(r.c.haptic == HAPTIC_REGISTERED, "trigger queues the 'registered' haptic"); }
  { Rig r; r.in.cap = 0; r.in.temp_c = 22; r.run(2000); r.in.p_hpa = REST + 80; r.run(5000);
    CHECK(r.count(EV_TRIGGER_HOLD) == 0, "squeezed while OFF_BODY -> no trigger"); }
  { Rig r; r.wear(); r.in.temp_c = 30;
    for (int i = 0; i < 90; i++) { r.in.temp_c += 0.1f; r.in.p_hpa = REST * (r.in.temp_c + 273.15f) / 303.15f; r.run(1000); }
    CHECK(r.count(EV_TRIGGER_HOLD) + r.count(EV_TRIGGER_DOUBLE_LONG) == 0,
          "cavity warming 30 -> 39 °C (+30 hPa) is not a press"); }
  { Rig r(false); r.wear(); r.in.p_hpa = REST + 500; r.run(5000); uint8_t s[STATUS_LEN]; r.c.status(s);
    CHECK(r.count(EV_TRIGGER_HOLD) == 0 && (s[5] & FAULT_UNCALIBRATED), "uncalibrated: flagged in STATUS"); }
  { Rig r(false); r.wear(); uint8_t k = CAL_REST;
    r.c.write_calibrate(r.t, &k, 1); r.run(3100);
    k = CAL_MAX; r.c.write_calibrate(r.t, &k, 1); r.in.p_hpa = REST + 60; r.run(5100); r.in.p_hpa = REST; r.run(2000);
    CHECK(r.c.pr.calibrated() && fabsf(r.c.pr.cal_max - r.c.pr.cal_rest - 60) < 0.5f && r.c.settings_dirty,
          "CALIBRATE rest (3 s) then max (5 s) sets the range");
    CHECK(r.count(EV_TRIGGER_HOLD) == 0, "the 5 s max press during calibration does not trigger"); }
  { Rig r; r.wear(); r.in.p_hpa = REST + 90; r.run(35000); r.in.p_hpa = REST; r.run(500);
    CHECK(r.count(EV_TRIGGER_HOLD) == 1 && r.count(EV_STUCK_HIGH) == 1, "35 s constant press -> HOLD then STUCK_HIGH"); }

  std::puts("\nS14: 8 h synthetic wear through the whole core");
  { Rig r; r.wear(); r.drain(); r.got.clear();
    Trace tr = soak_8h(rng);
    for (float v : tr.p) { r.in.p_hpa = REST + v; r.c.sample(r.t, r.in); r.t += DT; }
    int n = r.count(EV_TRIGGER_HOLD) + r.count(EV_TRIGGER_DOUBLE_LONG);
    char msg[96]; std::snprintf(msg, sizeof msg, "false triggers in 8 h: %d", n);
    CHECK(n == 0, msg);
    r.in.p_hpa = REST + 5; r.run(3000);
    Trace press; press.press(3300, 75, rng);
    for (float v : press.p) { r.in.p_hpa = REST + v; r.c.sample(r.t, r.in); r.t += DT; }
    r.in.p_hpa = REST + 5; r.run(1000);
    CHECK(r.count(EV_TRIGGER_HOLD) == 1, "a deliberate hold still fires after 8 h (baseline has not drifted)"); }

  std::puts("\ncavity self-test");
  CHECK(!cavity_leaks(1013, 22, 1013 * 310.15f / 295.15f, 37), "sealed cavity: rest rises with temperature");
  CHECK(cavity_leaks(1013, 22, 1014, 37), "leaking cavity: rest stays at ambient");
  CHECK(!cavity_leaks(1013, 35, 1013, 36), "too little temperature change -> no verdict");
  { Rig r; r.dock(); r.in.temp_c = 22; r.in.p_hpa = 1013; r.run(2000);
    r.in.hall = r.in.power = false; r.in.p_hpa = 1013; r.c.pr.reset_baseline(1013, 36.5f); r.wear(); r.run(610000);
    CHECK(r.count(EV_SENSOR_FAULT) == 1 && (r.c.faults & FAULT_CAVITY_LEAK), "leak -> one SENSOR_FAULT(cavity leak)"); }

  std::puts("\nadvertising, pairing, haptics, status");
  { Rig r; r.drain();
    CHECK(r.c.adv_interval_ms(r.t) == 1000 && r.c.adv_flags() == 0, "idle: 1 s, no flags");
    r.wear();
    CHECK(r.c.adv_interval_ms(r.t) == 20 && r.c.adv_flags() == 3, "event pending: 20 ms, flags pending|armed");
    r.run(31000);
    CHECK(r.c.adv_interval_ms(r.t) == 100, "after 30 s still pending: 100 ms");
    r.drain();
    CHECK(r.c.adv_interval_ms(r.t) == 1000, "delivered: back to 1 s"); }
  { Pairing p;
    p.update(0, false, true); p.update(6000, false, true);
    CHECK(!p.open, "button without dock never opens pairing");
    p.update(7000, true, true); p.update(11900, true, true);
    CHECK(!p.open, "docked, button 4.9 s: closed");
    p.update(12000, true, true);
    CHECK(p.open, "docked, button 5 s: open");
    p.update(13000, false, false);
    CHECK(!p.open, "undocked: closed");
    p.update(20000, true, true); p.update(25000, true, false); p.update(85000, true, false);
    CHECK(!p.open, "window closes after 60 s"); }
  { Core c; uint8_t v = 2, bad = 9;
    CHECK(c.write_haptic(&v, 1) && c.haptic == HAPTIC_DELIVERED && haptic_pulses(2).n == 3 && haptic_pulses(2).on_ms == 80,
          "HAPTIC(2) -> three 80 ms pulses");
    CHECK(!c.write_haptic(&bad, 1), "unknown haptic pattern rejected");
    c.cfg.haptics = 0; c.haptic = 0; c.write_haptic(&v, 1); uint8_t t = HAPTIC_TEST; bool ok = c.haptic == 0;
    c.write_haptic(&t, 1);
    CHECK(ok && c.haptic == HAPTIC_TEST, "haptics off: only the explicit test pattern plays"); }
  { Rig r; r.c.battery(r.t, 3400); r.c.battery(r.t, 3450); r.c.battery(r.t, 3700); r.c.battery(r.t, 3400);
    CHECK(r.count(EV_BATTERY_LOW) == 2, "BATTERY_LOW once per dip below 3.5 V");
    uint8_t s[STATUS_LEN]; r.wear(); r.c.status(s);
    CHECK(get16(s) == 3400 && s[2] == WEAR_WORN && s[3] == 1 && (int16_t)get16(s + 6) == 1000 &&
          (int16_t)get16(s + 8) == 1100, "STATUS layout: mV, wear, armed, pending, faults, cal hPa"); }
  { Core a, b; uint8_t cfgb[CONFIG_LEN] = {2, 70, 0xA0, 0x0F, 1, 0, 1}, blob[Core::SETTINGS_LEN];
    a.write_config(cfgb, CONFIG_LEN); a.pr.cal_rest = 990; a.pr.cal_max = 1090; a.save_settings(blob);
    CHECK(b.load_settings(blob, Core::SETTINGS_LEN) && b.cfg.threshold_pct == 70 && b.cfg.hold_ms == 4000 &&
          b.pr.cal_max == 1090, "settings (config + calibration) survive reboot");
    blob[5] = 5; CHECK(!Core().load_settings(blob, Core::SETTINGS_LEN), "stored settings re-validated on load (I11)"); }

  std::printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "ALL PASSED", failures, failures == 1 ? "" : "s");
  return failures ? 1 : 0;
}
