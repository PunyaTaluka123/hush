package com.hush.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** GATT v2 (SPEC §5.5). Kept in sync with firmware/src/ble.c by sim/tests/test_protocol_parity.py. */
object Gatt {
    private fun uuid(n: Int) = UUID.fromString("485700%02x-0010-55a1-8e4d-4c902b6e1f3a".format(n))
    val SVC = uuid(1)
    val EVENT = uuid(2)
    val ACK = uuid(3)
    val CONFIG = uuid(4)
    val CALIBRATE = uuid(5)
    val STATUS = uuid(6)
    val LIVE = uuid(7)
    val HAPTIC = uuid(8)
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object Ev {
    const val TRIGGER_HOLD = 1
    const val TRIGGER_DOUBLE_LONG = 2
    const val STUCK_HIGH = 3
    const val REMOVED_WHILE_ARMED = 4
    const val WORN = 5
    const val DOCKED = 6
    const val BATTERY_LOW = 7
    const val SENSOR_FAULT = 8
    const val BOOT = 9
}

object Haptic {
    const val REGISTERED = 1
    const val DELIVERED = 2
    const val ERROR = 3
    const val TEST = 4
}

private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

data class DeviceEvent(val seq: Long, val uptimeMs: Long, val type: Int, val data: Int) {
    companion object {
        fun parse(b: ByteArray): DeviceEvent? = if (b.size != 11) null else le(b).run {
            DeviceEvent(int.toLong() and 0xffffffffL, int.toLong() and 0xffffffffL, get().toInt() and 0xff, short.toInt() and 0xffff)
        }
    }
}

enum class Wear { UNKNOWN, DOCKED, WORN, OFF_BODY }

data class DeviceStatus(
    val batteryMv: Int, val wear: Wear, val armed: Boolean, val pending: Int, val faults: Int,
    val calRestHpa: Int, val calMaxHpa: Int,
) {
    val calibrated get() = faults and 0x08 == 0

    companion object {
        fun parse(b: ByteArray): DeviceStatus? = if (b.size != 10) null else le(b).run {
            DeviceStatus(
                short.toInt() and 0xffff, Wear.entries.getOrElse(get().toInt()) { Wear.UNKNOWN }, get() != 0.toByte(),
                get().toInt() and 0xff, get().toInt() and 0xff, short.toInt(), short.toInt(),
            )
        }
    }
}

/** CONFIG v2. The firmware enforces the limits (I11); the app only avoids offering values it would reject. */
data class DeviceConfig(
    val thresholdPct: Int = 55, val holdMs: Int = 3000, val patternMask: Int = 3, val autoArm: Boolean = true, val haptics: Boolean = true,
) {
    val valid get() = thresholdPct in 20..95 && holdMs in 1500..10000 && patternMask in 1..3

    fun bytes(): ByteArray = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(2); put(thresholdPct.toByte()); putShort(holdMs.toShort()); put(patternMask.toByte())
        put(if (autoArm) 1 else 0); put(if (haptics) 1 else 0)
    }.array()

    companion object {
        fun parse(b: ByteArray): DeviceConfig? = if (b.size != 7 || b[0] != 2.toByte()) null else le(b).run {
            get(); DeviceConfig(get().toInt() and 0xff, short.toInt() and 0xffff, get().toInt(), get() != 0.toByte(), get() != 0.toByte())
        }
    }
}

fun ackBytes(seq: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq.toInt()).array()
