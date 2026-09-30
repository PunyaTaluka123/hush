/* On-target checks of the firmware core through the same C ABI and settings
 * (NVS) storage the retainer uses. Host tests (tests/host) cover the logic in
 * depth; this covers what only exists under Zephyr.
 *   west twister -T firmware/tests/ztest -p native_sim     (Linux) */
#include <string.h>
#include <zephyr/settings/settings.h>
#include <zephyr/ztest.h>

#include "hush.h"

static uint8_t loaded[HUSH_LOG_BLOB_MAX];
static ssize_t loaded_len = -1;

static int on_set(const char* name, size_t len, settings_read_cb read_cb, void* arg) {
  if (!strcmp(name, "log") && len <= sizeof loaded) loaded_len = read_cb(arg, loaded, len);
  return 0;
}
static struct settings_handler handler = {.name = "hush", .h_set = on_set};

static void* setup(void) {
  zassert_ok(settings_subsys_init());
  zassert_ok(settings_register(&handler));
  return NULL;
}

ZTEST(hush_core, test_config_limits_i11)
{
  void* c = hush_static();
  uint8_t bad[HUSH_CONFIG_LEN] = {2, 5, 0xb8, 0x0b, 3, 1, 1};
  uint8_t ok[HUSH_CONFIG_LEN] = {2, 60, 0xb8, 0x0b, 3, 1, 1};
  uint8_t out[HUSH_CONFIG_LEN];
  zassert_false(hush_write_config(c, bad, sizeof bad), "threshold 5 %% must be rejected");
  zassert_true(hush_write_config(c, ok, sizeof ok));
  hush_read_config(c, out);
  zassert_mem_equal(out, ok, sizeof ok);
}

ZTEST(hush_core, test_event_log_survives_flash_roundtrip)
{
  void* c = hush_static();
  uint8_t blob[HUSH_LOG_BLOB_MAX], ev[HUSH_EVENT_LEN], all[4] = {0xff, 0xff, 0xff, 0xff};
  hush_boot(c, 1000, 1);
  size_t n = hush_save_log(c, blob);
  uint32_t next = hush_next_seq(c);
  zassert_ok(settings_save_one("hush/log", blob, n));

  hush_write_ack(c, all, sizeof all); /* forget everything in RAM */
  zassert_false(hush_next_event(c, ev));

  zassert_ok(settings_load_subtree("hush"));
  zassert_equal(loaded_len, (ssize_t)n);
  zassert_true(hush_load_log(c, loaded, loaded_len));
  zassert_equal(hush_next_seq(c), next, "seq continues after reboot");
  zassert_true(hush_next_event(c, ev));
  zassert_equal(ev[8], 9, "pending BOOT event restored");
}

ZTEST_SUITE(hush_core, NULL, setup, NULL, NULL, NULL);
