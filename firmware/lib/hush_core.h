// Hush firmware core: everything that is not hardware I/O.
//
// Pure C++17, no Zephyr, no heap. The Zephyr app (src/), the host tests
// (tests/host) and the device simulator (sim/, through hush_capi.cpp) all run
// this same code, so a behaviour tested on the laptop is the behaviour on the
// retainer.
#pragma once
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "pattern_detector.h"

namespace hush {

// ---------------------------------------------------------------- protocol
enum EvType : uint8_t {
  EV_TRIGGER_HOLD = 1, EV_TRIGGER_DOUBLE_LONG, EV_STUCK_HIGH, EV_REMOVED_WHILE_ARMED,
  EV_WORN, EV_DOCKED, EV_BATTERY_LOW, EV_SENSOR_FAULT, EV_BOOT,
};
enum Wear : uint8_t { WEAR_UNKNOWN = 0, WEAR_DOCKED, WEAR_WORN, WEAR_OFF_BODY };
enum Fault : uint8_t {
  FAULT_STUCK = 0x01, FAULT_CAVITY_LEAK = 0x02, FAULT_SENSOR_IO = 0x04, FAULT_UNCALIBRATED = 0x08,
};
enum Haptic : uint8_t { HAPTIC_NONE = 0, HAPTIC_REGISTERED, HAPTIC_DELIVERED, HAPTIC_ERROR, HAPTIC_TEST };
enum Cal : uint8_t { CAL_REST = 1, CAL_MAX = 2 };

constexpr size_t EVENT_LEN = 11, ACK_LEN = 4, CONFIG_LEN = 7, STATUS_LEN = 10;

// I11: whatever the app writes, the device never leaves these limits.
constexpr uint8_t THRESHOLD_MIN = 20, THRESHOLD_MAX = 95;
constexpr uint16_t HOLD_MIN_MS = 1500, HOLD_MAX_MS = 10000;

inline void put16(uint8_t* b, uint16_t v) { b[0] = uint8_t(v); b[1] = uint8_t(v >> 8); }
inline void put32(uint8_t* b, uint32_t v) { put16(b, uint16_t(v)); put16(b + 2, uint16_t(v >> 16)); }
inline uint16_t get16(const uint8_t* b) { return uint16_t(b[0] | b[1] << 8); }
inline uint32_t get32(const uint8_t* b) { return get16(b) | uint32_t(get16(b + 2)) << 16; }

struct Config {
  uint8_t version = 2;
  uint8_t threshold_pct = 55;
  uint16_t hold_ms = 3000;
  uint8_t pattern_mask = PATTERN_HOLD | PATTERN_DOUBLE_LONG;
  uint8_t auto_arm = 1;
  uint8_t haptics = 1;

  void pack(uint8_t* b) const {
    b[0] = version; b[1] = threshold_pct; put16(b + 2, hold_ms);
    b[4] = pattern_mask; b[5] = auto_arm; b[6] = haptics;
  }
  // Rejects (returns false, leaves *this untouched) anything outside the
  // safety limits, including a mask that would switch every trigger off.
  bool unpack(const uint8_t* b, size_t n) {
    if (n != CONFIG_LEN) return false;
    Config c;
    c.version = b[0]; c.threshold_pct = b[1]; c.hold_ms = get16(b + 2);
    c.pattern_mask = b[4]; c.auto_arm = b[5]; c.haptics = b[6];
    if (c.version != 2 || c.threshold_pct < THRESHOLD_MIN || c.threshold_pct > THRESHOLD_MAX ||
        c.hold_ms < HOLD_MIN_MS || c.hold_ms > HOLD_MAX_MS || c.pattern_mask == 0 ||
        (c.pattern_mask & ~(PATTERN_HOLD | PATTERN_DOUBLE_LONG)) || c.auto_arm > 1 || c.haptics > 1)
      return false;
    *this = c;
    return true;
  }
};

struct LogEntry {
  uint32_t seq, uptime_ms;
  uint8_t type;
  uint16_t data;
  void pack(uint8_t* b) const { put32(b, seq); put32(b + 4, uptime_ms); b[8] = type; put16(b + 9, data); }
  void unpack(const uint8_t* b) { seq = get32(b); uptime_ms = get32(b + 4); type = b[8]; data = get16(b + 9); }
};

// Haptic patterns: n pulses of on_ms, 120 ms apart.
struct Pulses { uint8_t n; uint16_t on_ms; };
inline Pulses haptic_pulses(uint8_t pattern) {
  switch (pattern) {
    case HAPTIC_REGISTERED: return {1, 150};
    case HAPTIC_DELIVERED: return {3, 80};
    case HAPTIC_ERROR: return {1, 600};
    case HAPTIC_TEST: return {2, 80};
    default: return {0, 0};
  }
}
constexpr uint16_t HAPTIC_GAP_MS = 120;

// ---------------------------------------------------------------- event log
// Un-ACKed events, oldest first. The glue persists save() whenever `dirty` is
// set, so seq keeps counting across reboots and nothing is lost before the
// phone ACKs it (which it does only after the server accepted the event).
class EventLog {
 public:
  static constexpr uint8_t CAP = 64;
  static constexpr uint32_t MAGIC = 0x48454c32;  // "HEL2"
  static constexpr size_t BLOB_MAX = 9 + CAP * EVENT_LEN;
  bool dirty = false;

  const LogEntry& append(uint8_t type, uint16_t data, uint32_t uptime_ms) {
    if (n_ == CAP) evict();
    LogEntry& e = ev_[n_++];
    e = {next_seq_++, uptime_ms, type, data};
    dirty = true;
    return e;
  }
  void ack(uint32_t seq) {  // everything up to and including seq is delivered
    uint8_t k = 0;
    while (k < n_ && ev_[k].seq <= seq) k++;
    if (!k) return;
    memmove(ev_, ev_ + k, (n_ - k) * sizeof(LogEntry));
    n_ -= k;
    dirty = true;
  }
  const LogEntry* next_unsent() const {
    for (uint8_t i = 0; i < n_; i++)
      if (ev_[i].seq > sent_) return &ev_[i];
    return nullptr;
  }
  void mark_sent(uint32_t seq) { sent_ = seq; }
  void on_disconnect() { sent_ = 0; }  // re-send everything un-ACKed on the next link
  uint8_t pending() const { return n_; }
  uint32_t next_seq() const { return next_seq_; }

  size_t save(uint8_t* b) const {
    put32(b, MAGIC); put32(b + 4, next_seq_); b[8] = n_;
    for (uint8_t i = 0; i < n_; i++) ev_[i].pack(b + 9 + i * EVENT_LEN);
    return 9 + n_ * EVENT_LEN;
  }
  bool load(const uint8_t* b, size_t len) {
    if (len < 9 || get32(b) != MAGIC || b[8] > CAP || len < 9 + b[8] * EVENT_LEN) return false;
    next_seq_ = get32(b + 4); n_ = b[8]; sent_ = 0;
    for (uint8_t i = 0; i < n_; i++) ev_[i].unpack(b + 9 + i * EVENT_LEN);
    return true;
  }

 private:
  static bool is_alarm(uint8_t t) {
    return t == EV_TRIGGER_HOLD || t == EV_TRIGGER_DOUBLE_LONG || t == EV_REMOVED_WHILE_ARMED;
  }
  // Full (phone out of range for a long time): drop the oldest non-alarm event;
  // only if all 64 are alarms, drop the oldest alarm.
  void evict() {
    uint8_t i = 0;
    while (i < n_ && is_alarm(ev_[i].type)) i++;
    if (i == n_) i = 0;
    memmove(ev_ + i, ev_ + i + 1, (n_ - i - 1) * sizeof(LogEntry));
    n_--;
  }
  LogEntry ev_[CAP];
  uint8_t n_ = 0;
  uint32_t next_seq_ = 1, sent_ = 0;
};

// ---------------------------------------------------------------- wear state
struct WearInputs {
  bool hall;       // case magnet present (raw)
  bool power;      // charging power from the case's coil present (raw)
  int8_t cap;      // tissue proximity: 1 near, 0 far, -1 capacitive sensing unavailable
  float temp_c;
};

// Entering DOCKED needs the magnet AND charging power (owner decision, 30 Sep
// 2026): a bare magnet held to a removed retainer must not disarm it. Staying
// DOCKED needs only the magnet, since a case may stop charging once full.
constexpr uint32_t DOCK_POWER_GRACE_MS = 2000;  // charger start-up time allowed while going into the case

class WearMachine {
 public:
  Wear state = WEAR_UNKNOWN;
  bool armed = false;

  // Returns an event type to log, or 0.
  uint8_t update(uint32_t now, const WearInputs& in, bool auto_arm) {
    track(hall_on_, in.hall, now);
    track(hall_off_, !in.hall, now);
    track(dock_, in.hall && in.power, now);
    track(cap_far_, in.cap == 0, now);
    temps(now, in.temp_c);
    bool warm = in.temp_c >= 32 && in.temp_c <= 39;
    // Without capacitive sensing, "in mouth" is temperature alone, and a still-falling
    // temperature (just taken out) does not count.
    bool mouth = in.cap < 0 ? warm && temp_max() - in.temp_c <= 1.0f : in.cap == 1 && warm;
    track(mouth_, mouth, now);

    if (state != WEAR_DOCKED && held(dock_, now, 1000)) {
      state = WEAR_DOCKED;
      armed = false;
      return EV_DOCKED;
    }
    switch (state) {
      case WEAR_UNKNOWN:
      case WEAR_DOCKED:
        if (held(hall_off_, now, 1000)) state = WEAR_OFF_BODY;
        break;
      case WEAR_OFF_BODY:
        if (held(mouth_, now, 10000)) {
          state = WEAR_WORN;
          if (auto_arm) armed = true;
          return EV_WORN;
        }
        break;
      case WEAR_WORN: {
        bool gone = in.cap >= 0 ? held(cap_far_, now, 3000) : temp_max() - in.temp_c > 2.0f;
        // Going into the case: magnet plus power (or magnet within the charger's
        // start-up grace) holds the alarm back until DOCKED takes over.
        bool docking = in.hall && (in.power || !held(hall_on_, now, DOCK_POWER_GRACE_MS));
        if (gone && !docking) {
          state = WEAR_OFF_BODY;
          return armed ? EV_REMOVED_WHILE_ARMED : 0;
        }
        break;
      }
    }
    return 0;
  }

 private:
  struct Since { bool on = false; uint32_t t = 0; };
  static void track(Since& s, bool cond, uint32_t now) {
    if (!cond) s.on = false;
    else if (!s.on) s = {true, now};
  }
  static bool held(const Since& s, uint32_t now, uint32_t ms) { return s.on && now - s.t >= ms; }

  // max temperature over the last 60 s, in 12 buckets of 5 s
  void temps(uint32_t now, float t) {
    uint32_t b = now / 5000;
    if (b != bucket_) {
      for (uint32_t k = bucket_ + 1; k <= b && k <= bucket_ + 12; k++) tmax_[k % 12] = -1000;
      bucket_ = b;
    }
    if (t > tmax_[b % 12]) tmax_[b % 12] = t;
  }
  float temp_max() const {
    float m = -1000;
    for (float v : tmax_) m = v > m ? v : m;
    return m;
  }
  Since hall_on_, hall_off_, dock_, cap_far_, mouth_;
  float tmax_[12] = {-1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000};
  uint32_t bucket_ = 0;
};

// ---------------------------------------------------------------- pressure
// Cavity pressure -> percent of the calibrated press range.
//
// The baseline is a slow EMA (tau 20 s), frozen during a press. A sealed cavity
// obeys p ∝ T, so the EMA runs on temperature-normalised pressure and a warming
// mouth does not look like a press.
//
// Deviation from SPEC §5.3: divides by (cal_max - cal_rest), not
// (cal_max - baseline). They are equal right after calibration, but the spec's
// form divides by ~0 if the baseline drifts up towards cal_max, which would
// turn noise into triggers.
class Pressure {
 public:
  static constexpr float TAU_S = 20.0f, MIN_SPAN_HPA = 2.0f;
  float cal_rest = NAN, cal_max = NAN;  // absolute hPa (persisted)

  bool calibrated() const { return cal_max - cal_rest >= MIN_SPAN_HPA; }  // false when NaN
  float baseline() const { return base_; }
  void reset_baseline(float p, float temp_c) { norm_ = p; tref_ = temp_c + 273.15f; base_ = p; }

  float update(float dt_s, float p, float temp_c, bool freeze) {
    float tk = temp_c + 273.15f;
    if (isnan(norm_)) reset_baseline(p, temp_c);
    if (!freeze) norm_ += (p * tref_ / tk - norm_) * (dt_s / TAU_S);
    base_ = norm_ * tk / tref_;
    if (!calibrated()) return 0;  // never triggers; STATUS reports FAULT_UNCALIBRATED
    float pct = (p - base_) / (cal_max - cal_rest) * 100.0f;
    return pct < 0 ? 0 : (pct > 120 ? 120 : pct);
  }

 private:
  float norm_ = NAN, tref_ = 0, base_ = NAN;
};

// Membrane integrity. Taken from the case (reference read while DOCKED) into the
// mouth, a sealed cavity's rest pressure rises by ~p*dT/T (≈ +50 hPa for 20→37 °C);
// a leaking one equalises with ambient and barely moves.
// ponytail: thresholds are guesses until measured on real cavities; membrane
// compliance eats part of the rise, which is what LEAK_RATIO allows for.
constexpr float LEAK_MIN_RISE_HPA = 5.0f, LEAK_RATIO = 0.3f;
inline bool cavity_leaks(float p_ref, float t_ref_c, float p_rest, float t_now_c) {
  float expect = p_ref * ((t_now_c + 273.15f) / (t_ref_c + 273.15f) - 1.0f);
  if (!(expect >= LEAK_MIN_RISE_HPA)) return false;  // not enough temperature change to tell
  return p_rest - p_ref < LEAK_RATIO * expect;
}

// ---------------------------------------------------------------- pairing
// Pairing window: opens after the case button is held 5 s while DOCKED, closes
// after 60 s, when undocked, or once a bond completes.
class Pairing {
 public:
  bool open = false;
  void update(uint32_t now, bool docked, bool button) {
    if (!docked) { open = false; btn_ = false; return; }
    if (!button) btn_ = false;
    else if (!btn_) { btn_ = true; since_ = now; }
    if (btn_ && !open && now - since_ >= 5000) { open = true; until_ = now + 60000; }
    if (open && int32_t(now - until_) >= 0) open = false;
  }
  void bonded() { open = false; }

 private:
  bool btn_ = false;
  uint32_t since_ = 0, until_ = 0;
};

// ---------------------------------------------------------------- core
struct Inputs {
  float p_hpa;
  float temp_c;
  bool hall;
  bool power;       // see WearInputs
  int8_t cap;
  bool button;      // case pairing button (dev kit: user button / shell)
};

class Core {
 public:
  Config cfg;
  EventLog log;
  WearMachine wear;
  Pressure pr;
  Pairing pairing;
  uint16_t batt_mv = 0;
  uint8_t faults = 0;
  uint8_t haptic = HAPTIC_NONE;  // next pattern for the glue to play (it clears it)
  float pct = 0;                  // last pressure sample, % of range (LIVE characteristic)
  bool settings_dirty = false;

  static constexpr uint32_t SETTINGS_MAGIC = 0x48535432;  // "HST2"
  static constexpr size_t SETTINGS_LEN = 4 + CONFIG_LEN + 8;
  static constexpr uint16_t BATT_LOW_MV = 3500, BATT_OK_MV = 3600;
  static constexpr uint32_t LEAK_CHECK_MS = 600000;

  void boot(uint32_t now, uint16_t reset_reason) {
    apply_config();
    log.append(EV_BOOT, reset_reason, now);
  }

  // 50 Hz. On the retainer the glue drains the sensor FIFO every 200 ms and
  // calls this once per sample with reconstructed timestamps.
  void sample(uint32_t now, const Inputs& in) {
    float dt = have_last_ ? float(now - last_) / 1000.0f : 0.0f;
    last_ = now; have_last_ = true;

    if (uint8_t e = wear.update(now, {in.hall, in.power, in.cap, in.temp_c}, cfg.auto_arm)) log.append(e, 0, now);
    pairing.update(now, wear.state == WEAR_DOCKED, in.button);
    if (wear.state == WEAR_DOCKED && in.hall) { dock_p_ = in.p_hpa; dock_t_ = in.temp_c; }  // ambient reference
    if (log.pending() && !pending_since_valid_) { pending_since_ = now; pending_since_valid_ = true; }
    if (!log.pending()) pending_since_valid_ = false;

    if (cal_kind_) { calibration_step(now, in); return; }  // no detection while calibrating

    pct = pr.update(dt, in.p_hpa, in.temp_c, det_.pressed());
    if (wear.state != WEAR_WORN) {  // a retainer squeezed in a hand must not trigger
      det_.reset();
      faults &= ~FAULT_STUCK;
      return;
    }
    peak_ = det_.pressed() ? (pct > peak_ ? pct : peak_) : pct;
    Event e = det_.update(now, pct);
    if (e == Event::TRIGGER_HOLD || e == Event::TRIGGER_DOUBLE_LONG) {
      log.append(uint8_t(e), uint16_t(peak_ > 100 ? 100 : peak_), now);
      if (cfg.haptics) haptic = HAPTIC_REGISTERED;
    } else if (e == Event::STUCK_HIGH) {
      faults |= FAULT_STUCK;
      log.append(EV_STUCK_HIGH, 0, now);
    }
    if (!det_.pressed()) faults &= ~FAULT_STUCK;

    if (!det_.pressed() && now - last_leak_check_ >= LEAK_CHECK_MS) {
      last_leak_check_ = now;
      if (!isnan(dock_p_) && cavity_leaks(dock_p_, dock_t_, pr.baseline(), in.temp_c) &&
          !(faults & FAULT_CAVITY_LEAK)) {
        faults |= FAULT_CAVITY_LEAK;
        log.append(EV_SENSOR_FAULT, FAULT_CAVITY_LEAK, now);
      }
    }
  }

  void battery(uint32_t now, uint16_t mv) {
    batt_mv = mv;
    if (mv && mv < BATT_LOW_MV && !batt_low_) { batt_low_ = true; log.append(EV_BATTERY_LOW, mv, now); }
    if (mv >= BATT_OK_MV) batt_low_ = false;
  }

  void sensor_fault(uint32_t now, uint8_t code) {
    if (faults & code) return;
    faults |= code;
    log.append(EV_SENSOR_FAULT, code, now);
  }

  // GATT writes. Each returns false for a malformed or out-of-range value
  // (the glue answers with an ATT error).
  bool write_config(const uint8_t* b, size_t n) {
    if (!cfg.unpack(b, n)) return false;
    apply_config();
    settings_dirty = true;
    return true;
  }
  bool write_ack(const uint8_t* b, size_t n) {
    if (n != ACK_LEN) return false;
    log.ack(get32(b));
    return true;
  }
  bool write_calibrate(uint32_t now, const uint8_t* b, size_t n) {
    if (n != 1 || (b[0] != CAL_REST && b[0] != CAL_MAX)) return false;
    cal_kind_ = b[0];
    cal_end_ = now + (cal_kind_ == CAL_REST ? 3000 : 5000);
    cal_sum_ = 0; cal_n_ = 0; cal_peak_ = -1e9f;
    det_.reset();
    return true;
  }
  bool write_haptic(const uint8_t* b, size_t n) {
    if (n != 1 || b[0] < HAPTIC_REGISTERED || b[0] > HAPTIC_TEST) return false;
    if (cfg.haptics || b[0] == HAPTIC_TEST) haptic = b[0];
    return true;
  }

  void status(uint8_t* b) const {
    put16(b, batt_mv);
    b[2] = wear.state;
    b[3] = wear.armed;
    b[4] = log.pending();
    b[5] = uint8_t(faults | (pr.calibrated() ? 0 : FAULT_UNCALIBRATED));
    put16(b + 6, uint16_t(int16_t(isnan(pr.cal_rest) ? 0 : lroundf(pr.cal_rest))));
    put16(b + 8, uint16_t(int16_t(isnan(pr.cal_max) ? 0 : lroundf(pr.cal_max))));
  }

  // Advertising: 1 s normally; with an event pending, 20 ms for 30 s then 100 ms.
  uint16_t adv_interval_ms(uint32_t now) const {
    if (!log.pending()) return 1000;
    return pending_since_valid_ && now - pending_since_ >= 30000 ? 100 : 20;
  }
  uint8_t adv_flags() const { return uint8_t((log.pending() ? 0x01 : 0) | (wear.armed ? 0x02 : 0)); }

  bool dfu_allowed() const { return wear.state == WEAR_DOCKED; }
  bool calibrating() const { return cal_kind_ != 0; }

  size_t save_settings(uint8_t* b) const {
    put32(b, SETTINGS_MAGIC);
    cfg.pack(b + 4);
    memcpy(b + 4 + CONFIG_LEN, &pr.cal_rest, 4);
    memcpy(b + 8 + CONFIG_LEN, &pr.cal_max, 4);
    return SETTINGS_LEN;
  }
  bool load_settings(const uint8_t* b, size_t n) {
    Config c;
    if (n != SETTINGS_LEN || get32(b) != SETTINGS_MAGIC || !c.unpack(b + 4, CONFIG_LEN)) return false;
    cfg = c;
    memcpy(&pr.cal_rest, b + 4 + CONFIG_LEN, 4);
    memcpy(&pr.cal_max, b + 8 + CONFIG_LEN, 4);
    apply_config();
    return true;
  }

 private:

  void apply_config() {
    DetectorConfig d;
    d.threshold_pct = cfg.threshold_pct;
    d.hold_ms = cfg.hold_ms;
    d.pattern_mask = cfg.pattern_mask;
    det_.setConfig(d);
  }

  void calibration_step(uint32_t now, const Inputs& in) {
    cal_sum_ += double(in.p_hpa); cal_n_++;
    if (in.p_hpa > cal_peak_) cal_peak_ = in.p_hpa;
    if (int32_t(now - cal_end_) < 0) return;
    if (cal_kind_ == CAL_REST) {
      pr.cal_rest = float(cal_sum_ / cal_n_);
      pr.reset_baseline(pr.cal_rest, in.temp_c);
    } else {
      pr.cal_max = cal_peak_;
    }
    cal_kind_ = 0;
    settings_dirty = true;
    // a max capture that is not clearly above rest means the press did not register
    if (!isnan(pr.cal_rest) && !isnan(pr.cal_max) && !pr.calibrated()) haptic = HAPTIC_ERROR;
  }

  PatternDetector det_;
  float peak_ = 0;
  uint32_t last_ = 0;
  bool have_last_ = false;
  bool batt_low_ = false;
  float dock_p_ = NAN, dock_t_ = NAN;
  uint32_t last_leak_check_ = 0;
  uint32_t pending_since_ = 0;
  bool pending_since_valid_ = false;
  uint8_t cal_kind_ = 0;
  uint32_t cal_end_ = 0, cal_n_ = 0;
  double cal_sum_ = 0;
  float cal_peak_ = 0;
};

}  // namespace hush
