package com.hush.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * The BLE link to the bonded retainer. Android's GATT client allows one
 * operation at a time, so reads and writes go through a small queue.
 */
@SuppressLint("MissingPermission") // checked during onboarding; the service only runs once granted
object Link {
    val connected = MutableStateFlow(false)
    val status = MutableStateFlow<DeviceStatus?>(null)
    val config = MutableStateFlow<DeviceConfig?>(null)
    val livePct = MutableStateFlow(0)
    @Volatile var onEvent: (DeviceEvent) -> Unit = {}

    private var gatt: BluetoothGatt? = null
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false

    fun connect(ctx: Context, address: String) {
        if (gatt != null) return
        val device = ctx.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        gatt = device.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE) // autoConnect: reconnects on its own
    }

    fun close() {
        gatt?.close()
        gatt = null
        connected.value = false
        synchronized(ops) { ops.clear(); busy = false }
    }

    fun write(uuid: UUID, value: ByteArray) = enqueue { g ->
        val c = g.getService(Gatt.SVC)?.getCharacteristic(uuid) ?: return@enqueue false
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.run { this.value = value; writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(this) }
        }
    }

    fun read(uuid: UUID) = enqueue { g -> g.getService(Gatt.SVC)?.getCharacteristic(uuid)?.let(g::readCharacteristic) ?: false }

    fun subscribe(uuid: UUID, on: Boolean = true) = enqueue { g ->
        val c = g.getService(Gatt.SVC)?.getCharacteristic(uuid) ?: return@enqueue false
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val v = when {
            !on -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        g.setCharacteristicNotification(c, on)
        val d = c.getDescriptor(Gatt.CCC)
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.run { value = v; g.writeDescriptor(this) }
        }
    }

    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        synchronized(ops) { ops.addLast(op) }
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        synchronized(ops) {
            while (!busy) {
                val op = ops.removeFirstOrNull() ?: return
                busy = op(g) // started: wait for its callback. Failed to start: try the next one.
            }
        }
    }

    private fun done() {
        synchronized(ops) { busy = false }
        pump()
    }

    private fun onValue(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Gatt.EVENT -> DeviceEvent.parse(v)?.let { onEvent(it) }
            Gatt.STATUS -> DeviceStatus.parse(v)?.let { status.value = it }
            Gatt.CONFIG -> DeviceConfig.parse(v)?.let { config.value = it }
            Gatt.LIVE -> if (v.size == 1) livePct.value = v[0].toInt() and 0xff
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.value = false
                synchronized(ops) { ops.clear(); busy = false }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            connected.value = true
            subscribe(Gatt.EVENT) // events flow as soon as the retainer sees the subscription
            subscribe(Gatt.STATUS)
            read(Gatt.STATUS)
            read(Gatt.CONFIG)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onValue(c.uuid, value)

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onValue(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, value)
            done()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) {
                if (status == BluetoothGatt.GATT_SUCCESS) c.value?.let { onValue(c.uuid, it) }
                done()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = done()
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done()
    }
}
