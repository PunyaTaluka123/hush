/* Sensor inputs.
 *
 * Dev kit (CONFIG_HUSH_DEVKIT, Seeed XIAO nRF52840): an FSR on the ADC stands
 * in for cavity pressure (ADC counts used as "hPa"; calibration makes the units
 * irrelevant). Hall, capacitive proximity and temperature are mocked from the
 * shell: hush wear | remove | dock | magnet | nocap | temp <c> | button | status.
 *
 * v1 board (ISP1807): BMP585 cavity pressure + temperature and the hall switch.
 * ponytail: polled at 50 Hz until the v1 board exists; move to the BMP585 FIFO
 * drained every 200 ms (SPEC §5.3) for the power budget, and wire the
 * capacitive channel once its part is chosen (until then cap = -1 selects the
 * temperature fallback in the wear state machine). */
#include <stdlib.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/shell/shell.h>

#include "app.h"

#define USER DT_PATH(zephyr_user)

#if DT_PROP_HAS_NAME(USER, io_channels, vbat)
#define HAVE_VBAT 1
static const struct adc_dt_spec vbat = ADC_DT_SPEC_GET_BY_NAME(USER, vbat);
static const struct gpio_dt_spec vbat_en = GPIO_DT_SPEC_GET_OR(USER, vbat_enable_gpios, {0});
#endif
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET_OR(USER, button_gpios, {0});

static int16_t adc_once(const struct adc_dt_spec* ch) {
  int16_t raw = 0;
  struct adc_sequence seq = {.buffer = &raw, .buffer_size = sizeof raw};
  adc_sequence_init_dt(ch, &seq);
  return adc_read_dt(ch, &seq) == 0 ? raw : 0;
}

uint16_t battery_mv(void) {
#ifdef HAVE_VBAT
  if (vbat_en.port) gpio_pin_set_dt(&vbat_en, 1);
  int32_t mv = adc_once(&vbat);
  if (vbat_en.port) gpio_pin_set_dt(&vbat_en, 0);
  if (adc_raw_to_millivolts_dt(&vbat, &mv)) return 0;
  return (uint16_t)(mv * 1510 / 510); /* XIAO: 1 MΩ / 510 kΩ divider */
#else
  return 0;
#endif
}

static bool button_pressed(void) { return button.port && gpio_pin_get_dt(&button) > 0; }

static void common_init(void) {
#ifdef HAVE_VBAT
  adc_channel_setup_dt(&vbat);
  if (vbat_en.port) gpio_pin_configure_dt(&vbat_en, GPIO_OUTPUT_INACTIVE);
#endif
  if (button.port) gpio_pin_configure_dt(&button, GPIO_INPUT);
}

#if CONFIG_HUSH_DEVKIT
/* ---------------------------------------------------------------- dev kit */
static const struct adc_dt_spec fsr = ADC_DT_SPEC_GET_BY_NAME(USER, fsr);
static const struct gpio_dt_spec fsr_pwr = GPIO_DT_SPEC_GET(USER, fsr_power_gpios);
static struct {
  bool hall;
  bool power;
  int8_t cap;
  float temp;
  int64_t button_until;
} mock = {false, false, 1, 36.5f, 0};

int sensors_init(void) {
  common_init();
  gpio_pin_configure_dt(&fsr_pwr, GPIO_OUTPUT_INACTIVE);
  return adc_channel_setup_dt(&fsr);
}

struct hush_inputs sensors_read(void) {
  gpio_pin_set_dt(&fsr_pwr, 1); /* the divider draws current only while sampling */
  k_busy_wait(50);
  float p = adc_once(&fsr);
  gpio_pin_set_dt(&fsr_pwr, 0);
  return (struct hush_inputs){p, mock.temp, mock.hall, mock.power, mock.cap, button_pressed() || k_uptime_get() < mock.button_until};
}

static int cmd_wear(const struct shell* sh, size_t argc, char** argv) {
  mock.hall = false; mock.power = false; mock.cap = 1; mock.temp = 36.5f;
  shell_print(sh, "in mouth (WORN after 10 s)");
  return 0;
}
static int cmd_remove(const struct shell* sh, size_t argc, char** argv) {
  mock.hall = false; mock.power = false; mock.cap = 0; mock.temp = 25;
  shell_print(sh, "out of the mouth (REMOVED_WHILE_ARMED after 3 s)");
  return 0;
}
static int cmd_dock(const struct shell* sh, size_t argc, char** argv) {
  mock.hall = true; mock.power = true; mock.cap = 0; mock.temp = 25;
  shell_print(sh, "in the case (DOCKED after 1 s)");
  return 0;
}
static int cmd_magnet(const struct shell* sh, size_t argc, char** argv) {
  mock.hall = true; mock.power = false; mock.cap = 0; mock.temp = 25;
  shell_print(sh, "bare magnet, no charging power: must NOT dock (removal alarm still fires)");
  return 0;
}
static int cmd_nocap(const struct shell* sh, size_t argc, char** argv) {
  mock.cap = -1;
  shell_print(sh, "capacitive sensing unavailable: temperature fallback");
  return 0;
}
static int cmd_temp(const struct shell* sh, size_t argc, char** argv) {
  mock.temp = strtof(argv[1], NULL);
  shell_print(sh, "temperature set");
  return 0;
}
static int cmd_button(const struct shell* sh, size_t argc, char** argv) {
  mock.button_until = k_uptime_get() + 6000;
  shell_print(sh, "case button held 6 s (opens pairing while DOCKED)");
  return 0;
}
static int cmd_status(const struct shell* sh, size_t argc, char** argv) {
  uint8_t s[HUSH_STATUS_LEN];
  int pairing, pct;
  LOCKED(hush_read_status(core, s); pairing = hush_pairing_open(core); pct = hush_live_pct(core));
  shell_print(sh, "batt %u mV wear %u armed %u pending %u faults 0x%02x cal %d..%d hPa pairing %d pct %d",
              s[0] | s[1] << 8, s[2], s[3], s[4], s[5], (int16_t)(s[6] | s[7] << 8), (int16_t)(s[8] | s[9] << 8),
              pairing, pct);
  return 0;
}
SHELL_STATIC_SUBCMD_SET_CREATE(hush_cmds,
  SHELL_CMD(wear, NULL, "retainer in the mouth", cmd_wear),
  SHELL_CMD(remove, NULL, "retainer out of the mouth", cmd_remove),
  SHELL_CMD(dock, NULL, "retainer in the case", cmd_dock),
  SHELL_CMD(magnet, NULL, "bare magnet without the case", cmd_magnet),
  SHELL_CMD(nocap, NULL, "simulate capacitive sensing failure", cmd_nocap),
  SHELL_CMD_ARG(temp, NULL, "temp <celsius>", cmd_temp, 2, 0),
  SHELL_CMD(button, NULL, "hold the case pairing button", cmd_button),
  SHELL_CMD(status, NULL, "print STATUS", cmd_status),
  SHELL_SUBCMD_SET_END);
SHELL_CMD_REGISTER(hush, &hush_cmds, "Hush mock inputs", NULL);

#else
/* ---------------------------------------------------------------- v1 board */
static const struct device* const baro = DEVICE_DT_GET_ONE(bosch_bmp581);
static const struct gpio_dt_spec hall = GPIO_DT_SPEC_GET(USER, hall_gpios);
static const struct gpio_dt_spec power_good = GPIO_DT_SPEC_GET(USER, power_good_gpios); /* charger IC: input power present */

int sensors_init(void) {
  common_init();
  gpio_pin_configure_dt(&hall, GPIO_INPUT);
  gpio_pin_configure_dt(&power_good, GPIO_INPUT);
  return device_is_ready(baro) ? 0 : -ENODEV;
}

struct hush_inputs sensors_read(void) {
  struct sensor_value p = {0}, t = {0};
  if (sensor_sample_fetch(baro) == 0) {
    sensor_channel_get(baro, SENSOR_CHAN_PRESS, &p); /* kPa */
    sensor_channel_get(baro, SENSOR_CHAN_AMBIENT_TEMP, &t);
  } else {
    LOCKED(hush_sensor_fault(core, k_uptime_get_32(), 0x04 /* FAULT_SENSOR_IO */));
  }
  return (struct hush_inputs){(float)(sensor_value_to_double(&p) * 10.0), (float)sensor_value_to_double(&t),
                              gpio_pin_get_dt(&hall) > 0, gpio_pin_get_dt(&power_good) > 0, -1, button_pressed()};
}
#endif
