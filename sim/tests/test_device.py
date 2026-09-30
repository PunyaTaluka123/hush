"""Simulator device (the firmware core via ctypes). No emulator needed."""
from device import Device, config_bytes


def test_s13_config_threshold_5pct_rejected_by_firmware():
    d = Device()
    before = d.read_config()
    assert not d.write_config(config_bytes(threshold_pct=5))
    assert d.read_config() == before
    assert d.write_config(config_bytes(threshold_pct=20, hold_ms=1500))
    assert not d.write_config(config_bytes(hold_ms=10001))


def test_event_log_survives_reboot_in_order():
    d = Device()
    d.wear()
    d.calibrate()
    while (e := d.indicate()) is not None:
        d.write_ack(e.seq)  # delivered and accepted
    d.press(3300, 72)
    d = d.reboot()
    got = []
    while (e := d.indicate()) is not None:
        got.append(e)
    assert [e.name for e in got][:2] == ["TRIGGER_HOLD", "BOOT"] and got[0].seq < got[1].seq
    assert [e.seq for e in got] == sorted(e.seq for e in got)


def test_status_and_advertising():
    d = Device()
    d.wear()
    d.calibrate()
    s = d.status()
    assert s["wear"] == "WORN" and s["armed"] and s["cal_max_hpa"] - s["cal_rest_hpa"] == 100
    assert d.adv()[1] & 0b10


def test_bare_magnet_does_not_hide_a_removal():
    d = Device()
    d.wear()
    d.calibrate()
    while (e := d.indicate()) is not None:
        d.write_ack(e.seq)
    d.remove(1000)
    d.magnet(8000)
    names = [e.name for e in iter(d.indicate, None)]
    assert "REMOVED_WHILE_ARMED" in names and "DOCKED" not in names
