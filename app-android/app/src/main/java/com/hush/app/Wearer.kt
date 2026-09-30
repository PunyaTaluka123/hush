package com.hush.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.UUID

const val ZERO_ID = "00000000000000000000"
const val INCIDENT_LOCATION_MS = 24 * 3600_000L
// SPEC §7.3: after any accepted PIN the phone keeps streaming for 6 h. It cannot
// know which PIN it was, so it does this for both; the server keeps the points
// only while the incident is still active and drops them otherwise (I6 on the server side).
const val CANCEL_LOCATION_MS = 6 * 3600_000L

data class Fix(val t: Long, val lat: Double, val lng: Double, val acc: Double)

/**
 * The wearer phone's protocol logic, without Android I/O (tested on the JVM).
 * It reports events, it never decides anything about incidents (I2), and its
 * behaviour after a PIN depends only on the local check, never on the server
 * (I1).
 */
class Wearer(
    val store: Store,
    private val api: Api,
    private val pinKey: PinKey,
    private val now: () -> Long,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val inbox = TreeMap<Long, Pair<Long, DeviceEvent>>() // seq -> (arrived at, event)
    private var serverAcked = 0L
    private var toAck = 0L

    /** An indication from the retainer. Returns true when the cancel screen must open. */
    @Synchronized
    fun onIndication(ev: DeviceEvent): Boolean {
        if (ev.seq <= serverAcked) { // already accepted by the server: the ACK was lost, just re-ACK
            toAck = maxOf(toAck, ev.seq)
            return false
        }
        if (inbox.containsKey(ev.seq)) return false
        inbox[ev.seq] = now() to ev
        return when (ev.type) {
            Ev.TRIGGER_HOLD, Ev.TRIGGER_DOUBLE_LONG -> startIncident()
            Ev.REMOVED_WHILE_ARMED -> !store.disarmed && startIncident()
            Ev.WORN, Ev.DOCKED -> { store.disarmed = false; false }
            else -> false
        }
    }

    private fun startIncident(): Boolean {
        store.locationUntil = now() + INCIDENT_LOCATION_MS
        store.addHistory("Alarm ${hhmm(now())}")
        return true
    }

    data class Sync(val ackSeq: Long, val haptic: Int)

    /**
     * One network pass (blocking). Device events and queued PIN proofs go out in
     * the order they happened, so a proof never reaches the server before the
     * removal it cancels. Returns the ACK (and haptic) to write to the retainer.
     * Throws IOException when offline; everything stays queued.
     */
    fun sync(): Sync? {
        var haptic = 0
        while (true) {
            val next = store.peek()
            val batch = synchronized(this) { inbox.values.filter { next == null || it.first <= next.at }.map { it.second } }
            if (batch.isNotEmpty()) {
                val r = result(api.post("reportDeviceEvents", reportBody(batch)))
                val acked = r.getLong("acked_seq")
                haptic = maxOf(haptic, r.getInt("haptic"))
                r.getString("incident_id").let { if (it != ZERO_ID) store.incident = it }
                synchronized(this) {
                    batch.forEach { inbox.remove(it.seq) }
                    serverAcked = maxOf(serverAcked, acked)
                    toAck = maxOf(toAck, acked)
                }
            }
            if (next == null) break
            api.post(next.function, next.body) // the answer is always {ok:true}; nothing to read (I1)
            store.pop()
        }
        return synchronized(this) { if (toAck > 0) Sync(toAck, haptic) else null }
    }

    fun pendingEvents() = synchronized(this) { inbox.size }

    private fun reportBody(evs: List<DeviceEvent>) = body(JSONObject()
        .put("idempotency_key", newKey())
        .put("device_id", store.deviceAddress ?: "unknown")
        .put("events", JSONArray(evs.map {
            JSONObject().put("device_seq", it.seq).put("type", it.type).put("data", it.data).put("observed_at", now())
        })))

    // ------------------------------------------------------------ PINs (SPEC §7)
    /** Local check only (offline "Incorrect PIN"); constant work whichever PIN. */
    fun pinMatches(pin: String) = hex(pinKey.mac(pin.toByteArray())) in store.pinPair

    /**
     * A PIN typed at the cancel or disarm prompt. Returns the local verdict at
     * once; the proof (slow: argon2id) is built on `background` and queued. Every
     * attempt is sent, right or wrong, because the server counts wrong ones (D1).
     */
    fun enterPin(pin: String, purpose: String, background: (Runnable) -> Unit): Boolean {
        val incident = store.incident
        val at = now()
        val key = newKey()
        store.pins?.let { p -> background { store.enqueue(at, "submitPin", submitPinBody(key, incident, purpose, PinCrypto.proof(pin, p, key, purpose, incident))) } }
        val ok = pinMatches(pin)
        if (ok && purpose == "cancel") {
            store.addHistory("Cancelled ${hhmm(now())}")
            store.incident = ZERO_ID
            store.locationUntil = now() + CANCEL_LOCATION_MS
        }
        if (ok && purpose == "disarm") store.disarmed = true
        return ok
    }

    // ------------------------------------------------------------ heartbeat + location
    fun wantsLocation() = now() < store.locationUntil

    fun heartbeatBody(s: DeviceStatus?, bleConnected: Boolean, phoneBatteryPct: Int, fix: Fix?): ByteArray = body(JSONObject()
        .put("idempotency_key", newKey())
        .put("device_battery_mv", s?.batteryMv ?: 0)
        .put("phone_battery_pct", phoneBatteryPct)
        .put("wear_state", (s?.wear ?: Wear.UNKNOWN).ordinal)
        .put("armed", s?.armed ?: false)
        .put("ble_connected", bleConnected)
        .apply { if (fix != null && wantsLocation()) put("location", fixJson(fix)) })

    fun locationBody(fixes: List<Fix>): ByteArray =
        body(JSONObject().put("idempotency_key", newKey()).put("points", JSONArray(fixes.map(::fixJson))))

    private fun fixJson(f: Fix) = JSONObject().put("t", f.t).put("lat", f.lat).put("lng", f.lng).put("acc", f.acc)

    /** Best effort; the next call retries. */
    fun post(function: String, body: ByteArray) = try {
        api.post(function, body)
        true
    } catch (e: IOException) {
        false
    }

    companion object {
        /** Exactly the same bytes and length for any PIN (I1). All values are fixed-length and need no escaping. */
        fun submitPinBody(key: String, incident: String, purpose: String, proof: String): ByteArray {
            require(key.length == 36 && incident.length == 20 && purpose.length == 6 && proof.length == 44)
            return """{"data":{"idempotency_key":"$key","incident_id":"$incident","purpose":"$purpose","proof":"$proof"}}""".toByteArray()
        }

        fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))
    }
}
