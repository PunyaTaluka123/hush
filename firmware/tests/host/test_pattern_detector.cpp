// Host unit tests for the Hush pattern detector.
//   make -C firmware/tests/host
//
// Signals are synthetic but shaped on published tongue-pressure data: saliva
// swallows peak at ~80-90% of max and last under ~1.2 s; chewing produces
// rhythmic short pulses; speech stays low.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <random>
#include <vector>

#include "pattern_detector.h"
#include "traces.h"

using hush::DetectorConfig;
using hush::Event;
using hush::PatternDetector;

static int failures = 0;

#define CHECK(cond, msg)                                       \
  do {                                                         \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); failures++; } \
    else { std::printf("  ok:   %s\n", msg); }                 \
  } while (0)

struct Result { int hold = 0, dbl = 0, stuck = 0; std::vector<uint32_t> at; };

static Result run(const Trace& tr, const DetectorConfig& cfg = DetectorConfig()) {
  PatternDetector d(cfg);
  Result r;
  uint32_t t = 1000;
  for (float v : tr.p) {
    Event e = d.update(t, v);
    if (e == Event::TRIGGER_HOLD) { r.hold++; r.at.push_back(t); }
    if (e == Event::TRIGGER_DOUBLE_LONG) { r.dbl++; r.at.push_back(t); }
    if (e == Event::STUCK_HIGH) r.stuck++;
    t += DT;
  }
  return r;
}

int main() {
  std::mt19937 rng(42);

  std::puts("deliberate triggers");
  { Trace tr; tr.idle(2000); tr.press(3300, 72, rng); tr.idle(2000);
    Result r = run(tr);
    CHECK(r.hold == 1 && r.dbl == 0, "3.3 s hold at 72% -> exactly one HOLD trigger");
    CHECK(!r.at.empty() && r.at[0] - 3000 >= 2900 && r.at[0] - 3000 <= 3200,
          "HOLD fires ~3 s after press start"); }

  { Trace tr; tr.idle(1000); tr.press(1800, 70, rng); tr.idle(500); tr.press(1800, 70, rng); tr.idle(2000);
    Result r = run(tr);
    CHECK(r.dbl == 1 && r.hold == 0, "long-gap-long (1.8 s, 0.5 s, 1.8 s) -> DOUBLE_LONG"); }

  { Trace tr; tr.idle(1000); tr.press(2500, 75, rng); tr.idle(3000);
    CHECK(run(tr).hold == 0, "2.5 s hold is not enough"); }

  { Trace tr; tr.idle(1000); tr.press(1800, 70, rng); tr.idle(2500); tr.press(1800, 70, rng); tr.idle(1000);
    Result r = run(tr);
    CHECK(r.dbl == 0 && r.hold == 0, "two long presses with 2.5 s gap do not pair up"); }

  { Trace tr; tr.idle(1000); tr.press(3500, 70, rng); tr.idle(1500); tr.press(3500, 70, rng); tr.idle(1000);
    CHECK(run(tr).hold == 1, "second hold inside 10 s refractory is ignored"); }

  { Trace tr; tr.idle(1000); tr.press(3500, 62, rng, 14.f); tr.idle(1000);
    CHECK(run(tr).hold == 1, "wobbly hold (tremor dips below threshold, within hysteresis) still fires"); }

  { DetectorConfig c; c.pattern_mask = hush::PATTERN_HOLD;
    Trace tr; tr.idle(1000); tr.press(1800, 70, rng); tr.idle(500); tr.press(1800, 70, rng); tr.idle(1000);
    CHECK(run(tr, c).dbl == 0, "DOUBLE_LONG disabled via pattern mask"); }

  { Trace tr; tr.idle(1000); tr.press(35000, 80, rng); tr.idle(500);
    Result r = run(tr);
    CHECK(r.hold == 1 && r.stuck == 1, "35 s constant pressure -> one HOLD then STUCK_HIGH"); }

  std::puts("\nfalse-alarm sources");
  { Trace tr; for (int i = 0; i < 30; i++) { tr.idle(4000); tr.bump(900, 92); }
    CHECK(run(tr).hold + run(tr).dbl == 0, "30 hard saliva swallows at 92% of max -> no trigger"); }

  { Trace tr; for (int i = 0; i < 10; i++) { tr.bump(800, 88); tr.idle(300, 20); }
    CHECK(run(tr).hold + run(tr).dbl == 0, "rapid gulping (drinking water) -> no trigger"); }

  { Trace tr; for (int i = 0; i < 120; i++) { tr.bump(400, 85); tr.idle(350, 15); }
    CHECK(run(tr).hold + run(tr).dbl == 0, "90 s of chewing -> no trigger"); }

  { Trace tr; std::uniform_real_distribution<float> u(0, 35);
    for (int i = 0; i < 3000; i++) tr.p.push_back(u(rng));
    CHECK(run(tr).hold + run(tr).dbl == 0, "60 s of speech-level pressure noise -> no trigger"); }

  { // slow swallow edge case: 1.4 s, just under long_ms, twice in a row
    Trace tr; tr.idle(1000); tr.bump(1400, 95); tr.idle(400); tr.bump(1400, 95); tr.idle(1000);
    CHECK(run(tr).dbl == 0, "two slow 1.4 s swallows back-to-back -> no DOUBLE_LONG"); }

  std::puts("\n8-hour soak (awake wear: swallows ~1/min, speech, meals of chewing)");
  { Trace tr = soak_8h(rng);
    Result r = run(tr);
    char msg[128];
    std::snprintf(msg, sizeof msg, "false triggers in 8 h synthetic wear: %d", r.hold + r.dbl);
    CHECK(r.hold + r.dbl == 0, msg); }

  std::printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "ALL PASSED", failures,
              failures == 1 ? "" : "s");
  return failures ? 1 : 0;
}
