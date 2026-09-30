/* Shared by the Zephyr glue. All logic lives in lib/hush_core.h behind the C
 * ABI in lib/hush.h; these files only move bytes between it and the hardware. */
#ifndef HUSH_APP_H
#define HUSH_APP_H
#include <stdbool.h>
#include <zephyr/kernel.h>

#include "hush.h"

extern void* core;             /* hush_static() */
extern struct k_mutex core_lock; /* held for every hush_* call: sampling loop and BLE callbacks race */
#define LOCKED(stmt)                            \
  do {                                          \
    k_mutex_lock(&core_lock, K_FOREVER);        \
    stmt;                                       \
    k_mutex_unlock(&core_lock);                 \
  } while (0)

/* Events with seq >= this are not in flash yet and must not be indicated: a
 * reboot would reuse their seq for a different event. */
extern volatile uint32_t persisted_next_seq;

struct hush_inputs {
  float p_hpa;
  float temp_c;
  bool hall;
  bool power; /* charging power from the case present */
  int8_t cap; /* 1 near, 0 far, -1 unavailable */
  bool button;
};

int sensors_init(void);
struct hush_inputs sensors_read(void);
uint16_t battery_mv(void);

int ble_init(void);
void ble_tick(uint32_t now);

#endif
