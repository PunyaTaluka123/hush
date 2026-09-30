package com.hush.app

import android.app.NotificationManager
import android.media.AudioAttributes
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InvariantTest {
    private val main = File("src/main")
    private fun sources() = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()

    @Test
    fun noRevealingWordsInTheApp() { // §7.3: not in UI strings, storage keys, logs, or anything else we ship
        val bad = Regex("duress|silent", RegexOption.IGNORE_CASE)
        assertTrue(sources().isNotEmpty())
        sources().forEach { assertFalse(it.path, bad.containsMatchIn(it.readText())) }
    }

    @Test
    fun theWearersPhoneNeverTextsOrCalls_I3() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        listOf("SEND_SMS", "CALL_PHONE", "READ_SMS", "RECEIVE_SMS").forEach { assertFalse(it, manifest.contains(it)) }
        sources().forEach { f ->
            assertFalse(f.path, Regex("SmsManager|ACTION_CALL\\b|ACTION_SENDTO").containsMatchIn(f.readText()))
        }
    }

    @Test
    fun audioOnlyInTheContactsAlarm_I4() {
        val audio = Regex("MediaPlayer|\\bRingtone\\b|getRingtone|TextToSpeech|AudioTrack|ToneGenerator|SoundPool")
        sources().filter { it.name != "Messaging.kt" }.forEach { assertFalse(it.path, audio.containsMatchIn(it.readText())) }
    }

    @Test
    fun channels_I4_I5() {
        val ctx = RuntimeEnvironment.getApplication()
        Channels.create(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.getNotificationChannel(Channels.EMERGENCY).run {
            assertEquals(NotificationManager.IMPORTANCE_HIGH, importance)
            assertEquals(AudioAttributes.USAGE_ALARM, audioAttributes.usage)
            assertTrue(canBypassDnd())
        }
        listOf(Channels.ARMED, Channels.INCIDENT, Channels.STATUS).forEach { id ->
            nm.getNotificationChannel(id).run {
                assertNull("$id must not make a sound", sound)
                assertFalse("$id must not badge", canShowBadge())
            }
        }
    }

    @Test
    fun pinCryptoMatchesTheBackend() { // docs/pin_vector.json is generated from backend/functions/src/pins.ts
        val v = JSONObject(File("../../docs/pin_vector.json").readText())
        val a = v.getJSONObject("argon")
        val p = PinParams(unb64(v.getString("salt")), unb64(v.getString("nonce")),
            Argon(a.getInt("iterations"), a.getInt("memorySize"), a.getInt("parallelism"), a.getInt("hashLength")), 4)
        assertEquals(v.getString("argon2id_hex"), hex(PinCrypto.argon2id(v.getString("pin"), p.salt, p.argon)))
        assertEquals(v.getString("proof_b64"),
            PinCrypto.proof(v.getString("pin"), p, v.getString("idempotency_key"), v.getString("purpose"), v.getString("incident_id")))
        assertEquals(v.getString("offline_hmac_hex"), hex(PinCrypto.hmac(unb64(v.getString("hash_key")), v.getString("pin").toByteArray())))
    }

    @Test
    fun protocolPayloads() {
        val ev = DeviceEvent.parse(byteArrayOf(7, 0, 0, 0, 0x10, 0x27, 0, 0, 1, 72, 0))!!
        assertEquals(DeviceEvent(7, 10000, Ev.TRIGGER_HOLD, 72), ev)
        val st = DeviceStatus.parse(byteArrayOf(0x68, 0x10, 2, 1, 0, 0, 0xe8.toByte(), 3, 0x4c, 4))!!
        assertEquals(DeviceStatus(4200, Wear.WORN, true, 0, 0, 1000, 1100), st)
        assertArrayEquals(byteArrayOf(2, 55, 0xb8.toByte(), 0x0b, 3, 1, 1), DeviceConfig().bytes())
        assertFalse(DeviceConfig(thresholdPct = 5).valid) // the firmware rejects it anyway (I11)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0), ackBytes(257))
    }
}
