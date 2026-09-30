package com.hush.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/** A server stand-in that records every request byte-for-byte. */
class FakeServer : Api {
    val requests = mutableListOf<Pair<String, ByteArray>>()
    var online = true

    override fun post(function: String, body: ByteArray): ByteArray {
        if (!online) throw IOException("offline")
        requests += function to body
        val data = JSONObject(String(body)).getJSONObject("data")
        val result = if (function == "reportDeviceEvents") {
            val evs = data.getJSONArray("events")
            JSONObject().put("acked_seq", (0 until evs.length()).maxOf { evs.getJSONObject(it).getLong("device_seq") })
                .put("incident_id", "Inc0000000000000000A").put("haptic", 0)
        } else {
            JSONObject().put("ok", true)
        }
        return JSONObject().put("result", result).toString().toByteArray()
    }
}

val TEST_KEY = PinKey { PinCrypto.hmac(ByteArray(32) { 7 }, it) }

fun testWearer(api: Api, name: String, now: Long = 1_790_000_000_000L): Wearer {
    val store = Store(RuntimeEnvironment.getApplication().getSharedPreferences(name, 0)).apply {
        pins = PinParams(ByteArray(16) { it.toByte() }, ByteArray(16) { 1 }, Argon(2, 19456, 1, 32), 4)
        pinPair = PinCrypto.pair(TEST_KEY, "4821", "4822")
        deviceAddress = "AA:BB:CC:DD:EE:FF"
    }
    var n = 0
    return Wearer(store, api, TEST_KEY, { now }, { "00000000-0000-4000-8000-%012d".format(n++) })
}

@RunWith(RobolectricTestRunner::class)
class WearerTest {
    private data class Observed(
        val screenOpened: Boolean, val accepted: Boolean, val requests: List<Pair<String, Int>>, val store: Map<String, *>,
        val history: List<String>, val streamsLocation: Boolean,
    )

    /** Retainer removed, PIN at the cancel screen: everything the phone does or keeps. */
    private fun path(pin: String): Observed {
        val api = FakeServer()
        val w = testWearer(api, "path-$pin")
        w.onIndication(DeviceEvent(1, 100, Ev.WORN, 0))
        w.sync()
        val opened = w.onIndication(DeviceEvent(2, 200, Ev.REMOVED_WHILE_ARMED, 0))
        w.sync()
        val accepted = w.enterPin(pin, "cancel") { it.run() }
        w.sync()
        return Observed(opened, accepted, api.requests.map { it.first to it.second.size }, w.store.dump(), w.store.history, w.wantsLocation())
    }

    @Test
    fun bothPinsLookIdenticalOnThePhone() { // §7.3: same UI verdict, same history, same requests and sizes, same local database
        val a = path("4821")
        val b = path("4822")
        assertEquals(a, b)
        assertTrue(a.screenOpened && a.accepted && a.streamsLocation)
        assertEquals(listOf("Alarm 22:13", "Cancelled 22:13"), a.history.map { it.replace(Regex("\\d\\d:\\d\\d"), "22:13") })
    }

    @Test
    fun submitPinIsFixedSize() {
        val sizes = listOf("4821", "4822", "0000").map { pin ->
            val api = FakeServer()
            testWearer(api, "size-$pin").run { enterPin(pin, "cancel") { it.run() }; sync() }
            api.requests.single { it.first == "submitPin" }.second.size
        }
        assertEquals(1, sizes.toSet().size)
    }

    @Test
    fun wrongPinShowsRetryButIsStillSentForTheServerToCount() {
        val api = FakeServer()
        val w = testWearer(api, "wrong")
        w.onIndication(DeviceEvent(1, 100, Ev.REMOVED_WHILE_ARMED, 0))
        assertFalse(w.enterPin("1111", "cancel") { it.run() })
        w.sync()
        assertEquals(1, api.requests.count { it.first == "submitPin" })
        assertTrue(w.store.history.none { it.startsWith("Cancelled") })
    }

    @Test
    fun offlineEventsAndProofsGoOutInTheOrderTheyHappened() {
        val api = FakeServer().apply { online = false }
        val w = testWearer(api, "order")
        w.onIndication(DeviceEvent(5, 100, Ev.REMOVED_WHILE_ARMED, 0))
        w.enterPin("4821", "cancel") { it.run() }
        assertTrue(runCatching { w.sync() }.exceptionOrNull() is IOException)
        api.online = true
        val s = w.sync()!!
        assertEquals(listOf("reportDeviceEvents", "submitPin"), api.requests.map { it.first })
        assertEquals(5L, s.ackSeq)
    }

    @Test
    fun retainerIsAckedOnlyAfterTheServerAccepted() {
        val api = FakeServer().apply { online = false }
        val w = testWearer(api, "ack")
        w.onIndication(DeviceEvent(1, 100, Ev.TRIGGER_HOLD, 70))
        assertTrue(runCatching { w.sync() }.isFailure)
        api.online = true
        assertEquals(1L, w.sync()!!.ackSeq)
        // ACK lost, the retainer re-sends after reconnecting: re-ACK, never re-report (I8)
        assertFalse(w.onIndication(DeviceEvent(1, 100, Ev.TRIGGER_HOLD, 70)))
        w.sync()
        assertEquals(1, api.requests.count { it.first == "reportDeviceEvents" })
    }

    @Test
    fun disarmedRemovalOpensNothing() {
        val w = testWearer(FakeServer(), "disarm")
        assertTrue(w.enterPin("4822", "disarm") { it.run() })
        assertFalse(w.onIndication(DeviceEvent(1, 100, Ev.REMOVED_WHILE_ARMED, 0)))
        assertTrue(w.onIndication(DeviceEvent(2, 200, Ev.TRIGGER_HOLD, 70))) // the tongue trigger always works
        w.onIndication(DeviceEvent(3, 300, Ev.WORN, 0))
        assertFalse(w.store.disarmed)
    }

    @Test
    fun noLocationOutsideAnAlarm() { // I6 (phone side)
        val w = testWearer(FakeServer(), "loc")
        assertFalse(w.wantsLocation())
        assertFalse(JSONObject(String(w.heartbeatBody(null, true, 50, Fix(1, 1.0, 2.0, 3.0)))).getJSONObject("data").has("location"))
        assertNull(w.sync())
    }
}
