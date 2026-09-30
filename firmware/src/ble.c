/* GATT v2 (SPEC §5.5): LE Secure Connections only, bonded, every characteristic
 * encrypted; pairing only inside the DOCKED + 5 s button window; at most 2
 * bonds; advertising interval and flags from the core; DFU only while DOCKED. */
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/logging/log.h>
#ifdef CONFIG_MCUMGR
#include <zephyr/mgmt/mcumgr/mgmt/callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/mgmt.h>
#endif

#include "app.h"

LOG_MODULE_REGISTER(hush_ble, LOG_LEVEL_INF);

/* Same UUID base as v0.1; short id in the first field. Kept in sync with sim/
 * and app-android by sim/tests/test_protocol_parity.py. */
#define HUSH_UUID(n) BT_UUID_128_ENCODE(0x48570000 + (n), 0x0010, 0x55a1, 0x8e4d, 0x4c902b6e1f3a)
static const struct bt_uuid_128 svc_uuid = BT_UUID_INIT_128(HUSH_UUID(1));
static const struct bt_uuid_128 event_uuid = BT_UUID_INIT_128(HUSH_UUID(2));
static const struct bt_uuid_128 ack_uuid = BT_UUID_INIT_128(HUSH_UUID(3));
static const struct bt_uuid_128 config_uuid = BT_UUID_INIT_128(HUSH_UUID(4));
static const struct bt_uuid_128 cal_uuid = BT_UUID_INIT_128(HUSH_UUID(5));
static const struct bt_uuid_128 status_uuid = BT_UUID_INIT_128(HUSH_UUID(6));
static const struct bt_uuid_128 live_uuid = BT_UUID_INIT_128(HUSH_UUID(7));
static const struct bt_uuid_128 haptic_uuid = BT_UUID_INIT_128(HUSH_UUID(8));

#define ENC_RW (BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT)

static struct bt_conn* conn;
static bool event_sub, status_sub, live_sub;
static volatile bool ind_busy;
static uint8_t ind_buf[HUSH_EVENT_LEN];
static uint32_t ind_seq;
static struct bt_gatt_indicate_params ind;

/* ---------------------------------------------------------------- characteristics */
/* The core validates every write; a rejection is an ATT error (I11). */
static ssize_t accept(int ok, uint16_t len) { return ok ? len : BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED); }

static ssize_t write_ack(struct bt_conn* c, const struct bt_gatt_attr* a, const void* buf, uint16_t len, uint16_t off, uint8_t f) {
  int ok = 0;
  if (!off) LOCKED(ok = hush_write_ack(core, buf, len));
  return accept(ok, len);
}
static ssize_t write_config(struct bt_conn* c, const struct bt_gatt_attr* a, const void* buf, uint16_t len, uint16_t off, uint8_t f) {
  int ok = 0;
  if (!off) LOCKED(ok = hush_write_config(core, buf, len));
  return accept(ok, len);
}
static ssize_t write_cal(struct bt_conn* c, const struct bt_gatt_attr* a, const void* buf, uint16_t len, uint16_t off, uint8_t f) {
  int ok = 0;
  if (!off) LOCKED(ok = hush_write_calibrate(core, k_uptime_get_32(), buf, len));
  return accept(ok, len);
}
static ssize_t write_haptic(struct bt_conn* c, const struct bt_gatt_attr* a, const void* buf, uint16_t len, uint16_t off, uint8_t f) {
  int ok = 0;
  if (!off) LOCKED(ok = hush_write_haptic(core, buf, len));
  return accept(ok, len);
}
static ssize_t read_config(struct bt_conn* c, const struct bt_gatt_attr* a, void* buf, uint16_t len, uint16_t off) {
  uint8_t b[HUSH_CONFIG_LEN];
  LOCKED(hush_read_config(core, b));
  return bt_gatt_attr_read(c, a, buf, len, off, b, sizeof b);
}
static ssize_t read_status(struct bt_conn* c, const struct bt_gatt_attr* a, void* buf, uint16_t len, uint16_t off) {
  uint8_t b[HUSH_STATUS_LEN];
  LOCKED(hush_read_status(core, b));
  return bt_gatt_attr_read(c, a, buf, len, off, b, sizeof b);
}
static void event_ccc(const struct bt_gatt_attr* a, uint16_t v) { event_sub = v & BT_GATT_CCC_INDICATE; }
static void status_ccc(const struct bt_gatt_attr* a, uint16_t v) { status_sub = v & BT_GATT_CCC_NOTIFY; }
static void live_ccc(const struct bt_gatt_attr* a, uint16_t v) { live_sub = v & BT_GATT_CCC_NOTIFY; }

BT_GATT_SERVICE_DEFINE(hush_svc,
  BT_GATT_PRIMARY_SERVICE(&svc_uuid),
  BT_GATT_CHARACTERISTIC(&event_uuid.uuid, BT_GATT_CHRC_INDICATE, BT_GATT_PERM_NONE, NULL, NULL, NULL),
  BT_GATT_CCC(event_ccc, ENC_RW),
  BT_GATT_CHARACTERISTIC(&ack_uuid.uuid, BT_GATT_CHRC_WRITE, BT_GATT_PERM_WRITE_ENCRYPT, NULL, write_ack, NULL),
  BT_GATT_CHARACTERISTIC(&config_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE, ENC_RW, read_config, write_config, NULL),
  BT_GATT_CHARACTERISTIC(&cal_uuid.uuid, BT_GATT_CHRC_WRITE, BT_GATT_PERM_WRITE_ENCRYPT, NULL, write_cal, NULL),
  BT_GATT_CHARACTERISTIC(&status_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ_ENCRYPT, read_status, NULL, NULL),
  BT_GATT_CCC(status_ccc, ENC_RW),
  BT_GATT_CHARACTERISTIC(&haptic_uuid.uuid, BT_GATT_CHRC_WRITE, BT_GATT_PERM_WRITE_ENCRYPT, NULL, write_haptic, NULL),
  BT_GATT_CHARACTERISTIC(&live_uuid.uuid, BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_NONE, NULL, NULL, NULL),
  BT_GATT_CCC(live_ccc, ENC_RW),
);

static const struct bt_gatt_attr* attr_of(const struct bt_uuid_128* u) {
  return bt_gatt_find_by_uuid(hush_svc.attrs, hush_svc.attr_count, &u->uuid);
}

/* ---------------------------------------------------------------- connections, pairing */
static bool adv_on, adv_dirty = true, adv_pairing;
static uint16_t adv_iv;
static uint8_t adv_flags;

static void connected(struct bt_conn* c, uint8_t err) {
  adv_on = false;
  if (err) {
    adv_dirty = true;
    return;
  }
  conn = bt_conn_ref(c);
  bt_conn_set_security(c, BT_SECURITY_L2); /* encrypt with the bond, or pair inside the window */
}

static void disconnected(struct bt_conn* c, uint8_t reason) {
  if (conn == c) {
    bt_conn_unref(conn);
    conn = NULL;
  }
  event_sub = status_sub = live_sub = false;
  ind_busy = false;
  LOCKED(hush_disconnected(core)); /* re-send everything un-ACKed next time */
  adv_dirty = true;
  LOG_INF("disconnected 0x%02x", reason);
}

static struct bt_conn_cb conn_cbs = {.connected = connected, .disconnected = disconnected};

static enum bt_security_err pairing_accept(struct bt_conn* c, const struct bt_conn_pairing_feat* feat) {
  int open;
  LOCKED(open = hush_pairing_open(core));
  return open ? BT_SECURITY_ERR_SUCCESS : BT_SECURITY_ERR_PAIR_NOT_ALLOWED;
}
static void pairing_complete(struct bt_conn* c, bool bonded) {
  if (bonded) LOCKED(hush_pairing_bonded(core));
  adv_dirty = true;
}
static struct bt_conn_auth_cb auth_cb = {.pairing_accept = pairing_accept};
static struct bt_conn_auth_info_cb auth_info_cb = {.pairing_complete = pairing_complete};

static void count_bond(const struct bt_bond_info* info, void* n) { ++*(int*)n; }
static void accept_bond(const struct bt_bond_info* info, void* unused) { bt_le_filter_accept_list_add(&info->addr); }

/* ---------------------------------------------------------------- DFU gate */
#ifdef CONFIG_MCUMGR
static enum mgmt_cb_return smp_gate(uint32_t event, enum mgmt_cb_return prev, int32_t* rc, uint16_t* group,
                                    bool* abort_more, void* data, size_t len) {
  int ok;
  LOCKED(ok = hush_dfu_allowed(core));
  if (ok) return MGMT_CB_OK;
  *rc = MGMT_ERR_EACCESSDENIED;
  return MGMT_CB_ERROR_RC;
}
static struct mgmt_callback smp_cb = {.callback = smp_gate, .event_id = MGMT_EVT_OP_CMD_RECV};
#endif

/* ---------------------------------------------------------------- advertising */
/* Outside the pairing window only the flag byte is advertised and only bonded
 * phones may connect; the name and service UUID appear only while pairing, so a
 * stranger's scanner does not see "Hush". */
static uint8_t mfg[3] = {0xff, 0xff, 0}; /* ponytail: test company ID until Hush has a Bluetooth SIG one */
static const uint8_t flags_pairing[] = {BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR};
static const uint8_t flags_bonded[] = {BT_LE_AD_NO_BREDR};
static const uint8_t svc_bytes[] = {HUSH_UUID(1)};

static void adv_restart(uint16_t iv_ms, uint8_t flags, bool pairing) {
  bt_le_adv_stop();
  bt_le_filter_accept_list_clear();
  if (!pairing) bt_foreach_bond(BT_ID_DEFAULT, accept_bond, NULL);
  mfg[2] = flags;
  const struct bt_data ad_pair[] = {
    BT_DATA(BT_DATA_FLAGS, flags_pairing, sizeof flags_pairing),
    BT_DATA(BT_DATA_UUID128_ALL, svc_bytes, sizeof svc_bytes),
    BT_DATA(BT_DATA_MANUFACTURER_DATA, mfg, sizeof mfg),
  };
  const struct bt_data ad_bonded[] = {
    BT_DATA(BT_DATA_FLAGS, flags_bonded, sizeof flags_bonded),
    BT_DATA(BT_DATA_MANUFACTURER_DATA, mfg, sizeof mfg),
  };
  const struct bt_data sd[] = {BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1)};
  uint32_t units = (uint32_t)iv_ms * 8 / 5; /* 0.625 ms */
  struct bt_le_adv_param p = BT_LE_ADV_PARAM_INIT(BT_LE_ADV_OPT_CONN | (pairing ? 0 : BT_LE_ADV_OPT_FILTER_CONN),
                                                  units, units, NULL);
  int err = pairing ? bt_le_adv_start(&p, ad_pair, ARRAY_SIZE(ad_pair), sd, ARRAY_SIZE(sd))
                    : bt_le_adv_start(&p, ad_bonded, ARRAY_SIZE(ad_bonded), NULL, 0);
  adv_on = err == 0;
  if (err) LOG_ERR("adv start %d", err);
}

/* ---------------------------------------------------------------- per-sample tick */
static void ind_done(struct bt_conn* c, struct bt_gatt_indicate_params* p, uint8_t err) {
  if (!err) LOCKED(hush_indicated(core, ind_seq)); /* removed from the log only on ACK */
  ind_busy = false;
}

void ble_tick(uint32_t now) {
  static uint32_t last_status, last_live;
  uint16_t iv;
  uint8_t flags, pct;
  int pairing, have = 0;
  uint8_t ev[HUSH_EVENT_LEN];
  k_mutex_lock(&core_lock, K_FOREVER);
  iv = hush_adv_interval(core, now);
  flags = hush_adv_flags(core);
  pairing = hush_pairing_open(core);
  pct = hush_live_pct(core);
  if (conn && event_sub && !ind_busy) have = hush_next_event(core, ev);
  k_mutex_unlock(&core_lock);

  if (pairing && !adv_pairing) { /* entering the window with both bond slots used: start over */
    int n = 0;
    bt_foreach_bond(BT_ID_DEFAULT, count_bond, &n);
    if (n >= CONFIG_BT_MAX_PAIRED) bt_unpair(BT_ID_DEFAULT, BT_ADDR_LE_ANY);
  }
  if (!conn) {
    if (adv_dirty || !adv_on || iv != adv_iv || flags != adv_flags || pairing != adv_pairing) {
      adv_iv = iv;
      adv_flags = flags;
      adv_pairing = pairing;
      adv_dirty = false;
      adv_restart(iv, flags, pairing);
    }
    return;
  }
  adv_pairing = pairing;
  uint32_t seq = ev[0] | ev[1] << 8 | ev[2] << 16 | (uint32_t)ev[3] << 24;
  if (have && seq < persisted_next_seq) {
    memcpy(ind_buf, ev, sizeof ind_buf);
    ind_seq = seq;
    ind = (struct bt_gatt_indicate_params){.attr = attr_of(&event_uuid), .func = ind_done, .data = ind_buf, .len = sizeof ind_buf};
    ind_busy = true;
    if (bt_gatt_indicate(conn, &ind)) ind_busy = false;
  }
  if (status_sub && now - last_status >= 30000) {
    uint8_t b[HUSH_STATUS_LEN];
    last_status = now;
    LOCKED(hush_read_status(core, b));
    bt_gatt_notify(conn, attr_of(&status_uuid), b, sizeof b);
  }
  if (live_sub && now - last_live >= 50) { /* 20 Hz, tuning only */
    last_live = now;
    bt_gatt_notify(conn, attr_of(&live_uuid), &pct, 1);
  }
}

int ble_init(void) {
  bt_conn_cb_register(&conn_cbs);
#ifdef CONFIG_MCUMGR
  mgmt_callback_register(&smp_cb);
#endif
  int err = bt_conn_auth_cb_register(&auth_cb);
  return err ? err : bt_conn_auth_info_cb_register(&auth_info_cb);
}
