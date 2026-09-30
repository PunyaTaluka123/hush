/* Hush retainer firmware (SPEC §5). 50 Hz loop: read sensors, run the core,
 * play haptics, persist the event log before it is indicated, drive BLE. */
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/atomic.h>

#include "app.h"

LOG_MODULE_REGISTER(hush, LOG_LEVEL_INF);

K_MUTEX_DEFINE(core_lock);
void* core;
volatile uint32_t persisted_next_seq;

#define SAMPLE_MS 20
#define BATTERY_MS 30000

/* ---------------------------------------------------------------- haptics */
static const struct gpio_dt_spec motor = GPIO_DT_SPEC_GET(DT_PATH(zephyr_user), motor_gpios);
static uint8_t buzz_left;
static uint16_t buzz_on_ms;
static bool buzz_on;

static void buzz_step(struct k_work* w);
static K_WORK_DELAYABLE_DEFINE(buzz_work, buzz_step);

static void buzz_step(struct k_work* w) {
  if (buzz_on) {
    gpio_pin_set_dt(&motor, 0);
    buzz_on = false;
    if (buzz_left) k_work_reschedule(&buzz_work, K_MSEC(HUSH_HAPTIC_GAP_MS));
    return;
  }
  if (!buzz_left) return;
  buzz_left--;
  buzz_on = true;
  gpio_pin_set_dt(&motor, 1);
  k_work_reschedule(&buzz_work, K_MSEC(buzz_on_ms));
}

static void play(uint8_t pattern) {
  hush_haptic_pulses(pattern, &buzz_left, &buzz_on_ms);
  buzz_on = false;
  gpio_pin_set_dt(&motor, 0);
  k_work_reschedule(&buzz_work, K_NO_WAIT);
}

/* ---------------------------------------------------------------- persistence */
static atomic_t dirty;

static void save_step(struct k_work* w) {
  static uint8_t buf[HUSH_LOG_BLOB_MAX];
  size_t n;
  uint32_t next;
  atomic_val_t d = atomic_clear(&dirty);
  if (d & HUSH_DIRTY_SETTINGS) {
    LOCKED(n = hush_save_settings(core, buf));
    if (settings_save_one("hush/cfg", buf, n)) atomic_or(&dirty, HUSH_DIRTY_SETTINGS);
  }
  if (d & HUSH_DIRTY_LOG) {
    LOCKED(n = hush_save_log(core, buf); next = hush_next_seq(core));
    if (settings_save_one("hush/log", buf, n) == 0) persisted_next_seq = next;
    else atomic_or(&dirty, HUSH_DIRTY_LOG); /* retried on the next sample */
  }
}
static K_WORK_DEFINE(save_work, save_step);

static int load(const char* name, size_t len, settings_read_cb read_cb, void* arg) {
  static uint8_t buf[HUSH_LOG_BLOB_MAX];
  if (len > sizeof buf) return -EINVAL;
  ssize_t n = read_cb(arg, buf, len);
  if (n < 0) return n;
  if (!strcmp(name, "log")) LOCKED(hush_load_log(core, buf, n));
  else if (!strcmp(name, "cfg")) LOCKED(hush_load_settings(core, buf, n));
  return 0;
}
static struct settings_handler hush_settings = {.name = "hush", .h_set = load};

/* ---------------------------------------------------------------- main */
int main(void) {
  core = hush_static();
  gpio_pin_configure_dt(&motor, GPIO_OUTPUT_INACTIVE);
  if (sensors_init()) LOG_ERR("sensor init failed");

  settings_subsys_init();
  settings_register(&hush_settings);
  int err = bt_enable(NULL);
  if (err) LOG_ERR("bt_enable %d", err);
  ble_init();
  settings_load(); /* bonds, config + calibration, event log */

  uint32_t cause = 0;
  hwinfo_get_reset_cause(&cause);
  hwinfo_clear_reset_cause();
  LOCKED(hush_boot(core, k_uptime_get_32(), (uint16_t)cause));
  atomic_or(&dirty, HUSH_DIRTY_LOG);
  save_step(NULL);

  int64_t next = k_uptime_get();
  uint32_t last_batt = 0;
  bool first = true;
  for (;;) {
    uint32_t now = k_uptime_get_32();
    struct hush_inputs in = sensors_read();
    uint8_t haptic;
    int d;
    LOCKED(hush_sample(core, now, in.p_hpa, in.temp_c, in.hall, in.power, in.cap, in.button); haptic = hush_take_haptic(core);
           d = hush_take_dirty(core));
    if (haptic) play(haptic);
    if (first || now - last_batt >= BATTERY_MS) {
      uint16_t mv = battery_mv();
      first = false;
      last_batt = now;
      LOCKED(hush_battery(core, now, mv); d |= hush_take_dirty(core));
    }
    if (d || atomic_get(&dirty)) {
      atomic_or(&dirty, d);
      k_work_submit(&save_work);
    }
    ble_tick(now);
    next += SAMPLE_MS;
    k_sleep(K_TIMEOUT_ABS_MS(next));
  }
  return 0;
}
