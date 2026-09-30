// C ABI over hush::Core, declared in hush.h.
#include "hush.h"

#include "hush_core.h"

using hush::Core;
static_assert(HUSH_EVENT_LEN == hush::EVENT_LEN && HUSH_CONFIG_LEN == hush::CONFIG_LEN &&
              HUSH_STATUS_LEN == hush::STATUS_LEN && HUSH_LOG_BLOB_MAX == hush::EventLog::BLOB_MAX &&
              HUSH_SETTINGS_LEN == Core::SETTINGS_LEN && HUSH_HAPTIC_GAP_MS == hush::HAPTIC_GAP_MS, "hush.h out of sync with hush_core.h");

static Core& C(void* c) { return *static_cast<Core*>(c); }
static Core the_core;  // firmware instance (namespace scope: no __cxa_guard needed)

extern "C" {
#ifndef __ZEPHYR__
void* hush_new(void) { return new Core(); }
void hush_free(void* c) { delete static_cast<Core*>(c); }
#endif
void* hush_static(void) { return &the_core; }
void hush_boot(void* c, uint32_t now, uint16_t reason) { C(c).boot(now, reason); }
void hush_sample(void* c, uint32_t now, float p_hpa, float temp_c, int hall, int power, int cap, int button) {
  C(c).sample(now, {p_hpa, temp_c, hall != 0, power != 0, int8_t(cap), button != 0});
}
void hush_battery(void* c, uint32_t now, uint16_t mv) { C(c).battery(now, mv); }
void hush_sensor_fault(void* c, uint32_t now, uint8_t code) { C(c).sensor_fault(now, code); }

int hush_write_config(void* c, const uint8_t* b, size_t n) { return C(c).write_config(b, n); }
int hush_write_ack(void* c, const uint8_t* b, size_t n) { return C(c).write_ack(b, n); }
int hush_write_calibrate(void* c, uint32_t now, const uint8_t* b, size_t n) { return C(c).write_calibrate(now, b, n); }
int hush_write_haptic(void* c, const uint8_t* b, size_t n) { return C(c).write_haptic(b, n); }

void hush_read_config(void* c, uint8_t* out) { C(c).cfg.pack(out); }
void hush_read_status(void* c, uint8_t* out) { C(c).status(out); }
int hush_next_event(void* c, uint8_t* out) {
  const hush::LogEntry* e = C(c).log.next_unsent();
  if (e) e->pack(out);
  return e != nullptr;
}
void hush_indicated(void* c, uint32_t seq) { C(c).log.mark_sent(seq); }
void hush_disconnected(void* c) { C(c).log.on_disconnect(); }
uint8_t hush_take_haptic(void* c) {
  uint8_t h = C(c).haptic;
  C(c).haptic = hush::HAPTIC_NONE;
  return h;
}
void hush_haptic_pulses(uint8_t pattern, uint8_t* n, uint16_t* on_ms) {
  hush::Pulses p = hush::haptic_pulses(pattern);
  *n = p.n;
  *on_ms = p.on_ms;
}
uint16_t hush_adv_interval(void* c, uint32_t now) { return C(c).adv_interval_ms(now); }
uint8_t hush_adv_flags(void* c) { return C(c).adv_flags(); }
int hush_pairing_open(void* c) { return C(c).pairing.open; }
void hush_pairing_bonded(void* c) { C(c).pairing.bonded(); }
int hush_dfu_allowed(void* c) { return C(c).dfu_allowed(); }
uint8_t hush_live_pct(void* c) { return uint8_t(C(c).pct > 100 ? 100 : C(c).pct); }

int hush_take_dirty(void* c) {
  int d = (C(c).log.dirty ? HUSH_DIRTY_LOG : 0) | (C(c).settings_dirty ? HUSH_DIRTY_SETTINGS : 0);
  C(c).log.dirty = C(c).settings_dirty = false;
  return d;
}
uint32_t hush_next_seq(void* c) { return C(c).log.next_seq(); }
size_t hush_save_log(void* c, uint8_t* out) { return C(c).log.save(out); }
size_t hush_save_settings(void* c, uint8_t* out) { return C(c).save_settings(out); }
int hush_load_log(void* c, const uint8_t* b, size_t n) { return C(c).log.load(b, n); }
int hush_load_settings(void* c, const uint8_t* b, size_t n) { return C(c).load_settings(b, n); }

size_t hush_save(void* c, uint8_t* out) {
  size_t n = C(c).save_settings(out);
  return n + C(c).log.save(out + n);
}
int hush_load(void* c, const uint8_t* b, size_t n) {
  return n > Core::SETTINGS_LEN && C(c).load_settings(b, Core::SETTINGS_LEN) &&
         C(c).log.load(b + Core::SETTINGS_LEN, n - Core::SETTINGS_LEN);
}
}
