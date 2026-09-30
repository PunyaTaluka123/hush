// Hush trigger-pattern detector.
//
// Why patterns and not a plain threshold: in healthy adults, swallowing saliva
// reaches roughly 80-90% of maximum tongue pressure (IOPI studies). Any
// threshold low enough to be comfortable will be crossed dozens of times an
// hour by swallowing alone. What swallows do NOT do is stay high for seconds.
// So Hush triggers on DURATION, with pressure only as a gate.
//
// Patterns (both on by default; mask lets the app disable either):
//   HOLD         pressure above threshold continuously for hold_ms (default 3 s)
//   DOUBLE_LONG  two presses of >= long_ms (1.5 s) separated by a short release
//
// Pure C++ with no Arduino dependencies so it can be unit-tested on a laptop.
#pragma once
#include <stdint.h>

namespace hush {

enum class Event : uint8_t {
  NONE = 0,
  TRIGGER_HOLD = 1,
  TRIGGER_DOUBLE_LONG = 2,
  STUCK_HIGH = 3,        // pressure high for a very long time: fault or device bitten
};

enum PatternMask : uint8_t { PATTERN_HOLD = 0x01, PATTERN_DOUBLE_LONG = 0x02 };

struct DetectorConfig {
  float threshold_pct = 55.0f;     // % of the user's calibrated max; adjustable in app
  float hysteresis_pct = 10.0f;    // release only when below threshold - hysteresis...
  uint32_t release_debounce_ms = 100;  // ...for at least this long (rides out tremor)
  uint32_t hold_ms = 3000;
  uint32_t long_ms = 1500;
  uint32_t gap_min_ms = 150;
  uint32_t gap_max_ms = 1500;
  uint32_t refractory_ms = 10000;  // after a trigger, ignore input for this long
  uint32_t stuck_ms = 30000;
  uint8_t pattern_mask = PATTERN_HOLD | PATTERN_DOUBLE_LONG;
};

class PatternDetector {
 public:
  explicit PatternDetector(const DetectorConfig& cfg = DetectorConfig()) : cfg_(cfg) {}

  void setConfig(const DetectorConfig& cfg) { cfg_ = cfg; reset(); }
  const DetectorConfig& config() const { return cfg_; }

  void reset() {
    pressed_ = false;
    press_start_ = 0;
    fired_this_press_ = false;
    stuck_reported_ = false;
    low_since_valid_ = false;
    have_first_long_ = false;
    first_long_end_ = 0;
  }

  // Call at a steady rate (e.g. 50 Hz). pressure_pct is 0..100 of calibrated max.
  Event update(uint32_t now_ms, float pressure_pct) {
    if (in_refractory(now_ms)) {
      // still track press state so a press that continues through the
      // refractory window cannot fire the moment it ends
      track_press(now_ms, pressure_pct);
      fired_this_press_ = pressed_;
      return check_stuck(now_ms);
    }

    const bool was_pressed = pressed_;
    track_press(now_ms, pressure_pct);

    if (pressed_ && !was_pressed) {            // press started
      fired_this_press_ = false;
      // a pending first long press expires if the gap was too long or too short
      if (have_first_long_) {
        uint32_t gap = now_ms - first_long_end_;
        if (gap < cfg_.gap_min_ms || gap > cfg_.gap_max_ms) have_first_long_ = false;
      }
    }

    if (pressed_ && !fired_this_press_) {
      uint32_t dur = now_ms - press_start_;
      if ((cfg_.pattern_mask & PATTERN_DOUBLE_LONG) && have_first_long_ && dur >= cfg_.long_ms) {
        return fire(now_ms, Event::TRIGGER_DOUBLE_LONG);
      }
      if ((cfg_.pattern_mask & PATTERN_HOLD) && dur >= cfg_.hold_ms) {
        return fire(now_ms, Event::TRIGGER_HOLD);
      }
    }

    if (!pressed_ && was_pressed) {            // press ended
      uint32_t dur = now_ms - press_start_;
      if (dur >= cfg_.long_ms && !fired_this_press_) {
        have_first_long_ = true;
        first_long_end_ = now_ms;
      } else {
        have_first_long_ = false;
      }
    }
    if (have_first_long_ && !pressed_ && now_ms - first_long_end_ > cfg_.gap_max_ms) {
      have_first_long_ = false;
    }
    return check_stuck(now_ms);
  }

  bool pressed() const { return pressed_; }

 private:
  void track_press(uint32_t now_ms, float p) {
    if (!pressed_ && p >= cfg_.threshold_pct) {
      pressed_ = true;
      press_start_ = now_ms;
      stuck_reported_ = false;
      low_since_valid_ = false;
    } else if (pressed_) {
      if (p < cfg_.threshold_pct - cfg_.hysteresis_pct) {
        if (!low_since_valid_) { low_since_ = now_ms; low_since_valid_ = true; }
        if (now_ms - low_since_ >= cfg_.release_debounce_ms) {
          pressed_ = false;
          low_since_valid_ = false;
        }
      } else {
        low_since_valid_ = false;
      }
    }
  }

  Event check_stuck(uint32_t now_ms) {
    if (pressed_ && !stuck_reported_ && now_ms - press_start_ >= cfg_.stuck_ms) {
      stuck_reported_ = true;
      return Event::STUCK_HIGH;
    }
    return Event::NONE;
  }

  bool in_refractory(uint32_t now_ms) const {
    return last_fire_valid_ && now_ms - last_fire_ < cfg_.refractory_ms;
  }

  Event fire(uint32_t now_ms, Event e) {
    fired_this_press_ = true;
    have_first_long_ = false;
    last_fire_ = now_ms;
    last_fire_valid_ = true;
    return e;
  }

  DetectorConfig cfg_;
  bool pressed_ = false;
  uint32_t press_start_ = 0;
  bool fired_this_press_ = false;
  bool stuck_reported_ = false;
  uint32_t low_since_ = 0;
  bool low_since_valid_ = false;
  bool have_first_long_ = false;
  uint32_t first_long_end_ = 0;
  uint32_t last_fire_ = 0;
  bool last_fire_valid_ = false;
};

}  // namespace hush
