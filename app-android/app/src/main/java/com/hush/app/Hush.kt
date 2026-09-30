package com.hush.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hush.init(this)
    }
}

/** Starts protection again after a phone restart (the retainer may still be worn). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Hush.store.role == "wearer" && Hush.store.deviceAddress != null) {
            WearerService.start(context)
        }
    }
}

/** App-wide singletons. */
object Hush {
    lateinit var ctx: Context
        private set

    fun init(c: Context) {
        ctx = c.applicationContext
        Channels.create(ctx)
    }

    val store by lazy { Store(ctx.getSharedPreferences("hush", Context.MODE_PRIVATE)) }

    /** Network and argon2id work, one at a time and in order: a PIN proof is always queued before the next sync. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val emulator get() = BuildConfig.EMULATOR_HOST
    private val firebase by lazy {
        FirebaseApp.getApps(ctx).firstOrNull() ?: FirebaseApp.initializeApp(
            ctx,
            FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build(),
        ).also {
            if (emulator.isNotEmpty()) {
                FirebaseAuth.getInstance(it).useEmulator(emulator, 9099)
                FirebaseFirestore.getInstance(it).useEmulator(emulator, 8080)
            }
        }
    }
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance(firebase)
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance(firebase)

    private val functionsBase get() = BuildConfig.FIREBASE_PROJECT_ID.let { p ->
        if (emulator.isNotEmpty()) "http://$emulator:5001/$p/asia-southeast1/" else "https://asia-southeast1-$p.cloudfunctions.net/"
    }
    val api: Api by lazy { HttpApi(functionsBase) { auth.currentUser?.let { u -> runCatching { Tasks.await(u.getIdToken(false)).token }.getOrNull() } } }
    val wearer by lazy { Wearer(store, api, KeystorePinKey, System::currentTimeMillis) }

    /** A callable from a UI action (blocking: call from `io`). */
    fun call(function: String, data: JSONObject = JSONObject()): JSONObject =
        result(api.post(function, body(data.put("idempotency_key", UUID.randomUUID().toString()))))

    /** A PIN-protected action (settings, contacts, PIN change): proof with purpose "config". */
    fun callWithPin(function: String, pin: String, data: JSONObject, field: String = "pin_proof"): JSONObject {
        val key = UUID.randomUUID().toString()
        val proof = PinCrypto.proof(pin, store.pins ?: error("no PINs"), key, "config", ZERO_ID)
        return result(api.post(function, body(data.put("idempotency_key", key).put(field, proof))))
    }

    fun registerPushToken() {
        val uid = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { saveToken(uid, it) }
    }

    fun saveToken(uid: String, token: String) {
        db.document("push_tokens/$uid/tokens/$token")
            .set(mapOf("platform" to "android", "role" to (store.role ?: "wearer"), "updated_at" to System.currentTimeMillis()))
    }
}

/**
 * Notification channels. Wearer channels never make a sound (I4) and never
 * badge (I1); the contact's emergency channel is loud and overrides DND where
 * Android allows it (I5).
 */
object Channels {
    const val EMERGENCY = "hush_emergency"
    const val UPDATES = "hush_updates"
    const val ARMED = "hush_armed"
    const val INCIDENT = "hush_incident"
    const val STATUS = "hush_status"
    val INCIDENT_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)

    fun create(ctx: Context) {
        val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(EMERGENCY, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
                setBypassDnd(true)
                enableVibration(true)
            },
            NotificationChannel(UPDATES, "Alert updates", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(ARMED, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
            },
            NotificationChannel(INCIDENT, "Alarm screen", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = INCIDENT_VIBRATION
                setShowBadge(false)
            },
            NotificationChannel(STATUS, "Retainer status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        ))
    }
}
