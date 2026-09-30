/* C ABI over hush::Core (hush_capi.cpp). The Zephyr glue (src/ C files) and the
 * simulator (sim/device.py, ctypes) both drive the firmware through this, so
 * the simulator exercises the exact interface the retainer runs. */
#ifndef HUSH_H
#define HUSH_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HUSH_EVENT_LEN 11
#define HUSH_CONFIG_LEN 7
#define HUSH_STATUS_LEN 10
#define HUSH_LOG_BLOB_MAX (9 + 64 * HUSH_EVENT_LEN)
#define HUSH_SETTINGS_LEN 19
#define HUSH_DIRTY_LOG 1
#define HUSH_DIRTY_SETTINGS 2
#define HUSH_HAPTIC_GAP_MS 120

void* hush_new(void);    /* host: heap instance */
void* hush_static(void); /* firmware: the one instance, no heap */
void hush_free(void* c);
void hush_boot(void* c, uint32_t now, uint16_t reset_reason);
void hush_sample(void* c, uint32_t now, float p_hpa, float temp_c, int hall, int power, int cap, int button);
void hush_battery(void* c, uint32_t now, uint16_t mv);
void hush_sensor_fault(void* c, uint32_t now, uint8_t code);

/* GATT writes: 1 accepted, 0 rejected (ATT error) */
int hush_write_config(void* c, const uint8_t* b, size_t n);
int hush_write_ack(void* c, const uint8_t* b, size_t n);
int hush_write_calibrate(void* c, uint32_t now, const uint8_t* b, size_t n);
int hush_write_haptic(void* c, const uint8_t* b, size_t n);

/* GATT reads, indications, advertising */
void hush_read_config(void* c, uint8_t* out);
void hush_read_status(void* c, uint8_t* out);
int hush_next_event(void* c, uint8_t* out); /* next un-sent event (in order); 0 if none */
void hush_indicated(void* c, uint32_t seq); /* central confirmed it */
void hush_disconnected(void* c);           /* re-send everything un-ACKed next time */
uint8_t hush_take_haptic(void* c);
void hush_haptic_pulses(uint8_t pattern, uint8_t* n, uint16_t* on_ms); /* pulses are HUSH_HAPTIC_GAP_MS apart */
uint16_t hush_adv_interval(void* c, uint32_t now);
uint8_t hush_adv_flags(void* c);
int hush_pairing_open(void* c);
void hush_pairing_bonded(void* c);
int hush_dfu_allowed(void* c);
uint8_t hush_live_pct(void* c);

/* Persistence */
int hush_take_dirty(void* c); /* HUSH_DIRTY_* bits, cleared */
uint32_t hush_next_seq(void* c);
size_t hush_save_log(void* c, uint8_t* out);
size_t hush_save_settings(void* c, uint8_t* out);
int hush_load_log(void* c, const uint8_t* b, size_t n);
int hush_load_settings(void* c, const uint8_t* b, size_t n);
size_t hush_save(void* c, uint8_t* out); /* settings + log, for the simulator's reboots */
int hush_load(void* c, const uint8_t* b, size_t n);

#ifdef __cplusplus
}
#endif
#endif
