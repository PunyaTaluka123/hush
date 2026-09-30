// Synthetic tongue-pressure traces shared by the host tests (percent of max, 50 Hz).
// Shaped on published data: saliva swallows peak at ~80-90 % and last < ~1.2 s;
// chewing is rhythmic short pulses; speech stays low.
#pragma once
#include <cmath>
#include <cstdint>
#include <random>
#include <vector>

static const uint32_t DT = 20;  // 50 Hz, same as firmware

struct Trace {
  std::vector<float> p;  // pressure % per 20 ms sample
  void idle(uint32_t ms, float level = 5.f) { for (uint32_t t = 0; t < ms; t += DT) p.push_back(level); }
  // raised-cosine bump (swallow / chew / tap)
  void bump(uint32_t ms, float peak, float base = 5.f) {
    uint32_t n = ms / DT;
    for (uint32_t i = 0; i < n; i++)
      p.push_back(base + (peak - base) * 0.5f * (1 - std::cos(2 * M_PI * i / n)));
  }
  // deliberate press: fast rise, plateau with tremor, fast release
  void press(uint32_t ms, float level, std::mt19937& rng, float tremor = 6.f) {
    std::normal_distribution<float> n(0, tremor / 2);
    for (int i = 0; i < 6; i++) p.push_back(5 + (level - 5) * i / 6.f);
    for (uint32_t t = 120; t < ms; t += DT) p.push_back(level + n(rng));
    for (int i = 5; i >= 0; i--) p.push_back(5 + (level - 5) * i / 6.f);
  }
};

// 8 h of awake wear: swallows ~1/min, speech-level noise, meals of chewing.
inline Trace soak_8h(std::mt19937& rng) {
  Trace tr; std::uniform_int_distribution<int> gap(20000, 90000), dur(600, 1300), pk(70, 95);
  std::uniform_real_distribution<float> sp(0, 30);
  uint64_t total = 0;
  while (total < 8ull * 3600 * 1000) {
    uint32_t g = gap(rng);
    for (uint32_t t = 0; t < g; t += DT) tr.p.push_back(sp(rng));  // talking / resting
    tr.bump(dur(rng), (float)pk(rng));
    total += g + 1000;
    if (tr.p.size() % 50000 < 60) for (int i = 0; i < 200; i++) { tr.bump(400, 85); tr.idle(350, 15); }
  }
  return tr;
}
