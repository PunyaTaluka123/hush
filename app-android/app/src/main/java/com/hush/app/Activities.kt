package com.hush.app

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException

/** Kicks a network pass now (after a PIN, or when the app opens). */
fun syncNow() = Hush.io.execute {
    try {
        Hush.wearer.sync()?.let { s ->
            Link.write(Gatt.ACK, ackBytes(s.ackSeq))
            if (s.haptic != 0) Link.write(Gatt.HAPTIC, byteArrayOf(s.haptic.toByte()))
        }
    } catch (e: IOException) {
        // offline: queued, retried by the service
    } catch (e: CallError) {
    }
}

class MainActivity : ComponentActivity() {
    private var invite by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readInvite(intent)
        setContent {
            HushTheme {
                var user by remember { mutableStateOf(Hush.auth.currentUser) }
                var role by remember { mutableStateOf(Hush.store.role) }
                var onboarded by remember { mutableStateOf(Hush.store.onboarded) }
                DisposableEffect(Unit) {
                    val l = FirebaseAuth.AuthStateListener { user = it.currentUser; if (it.currentUser != null) Hush.registerPushToken() }
                    Hush.auth.addAuthStateListener(l)
                    onDispose { Hush.auth.removeAuthStateListener(l) }
                }
                when {
                    user == null -> SignInScreen(this)
                    role == null -> RoleScreen { r -> Hush.store.role = r; role = r; if (r == "contact") invite?.let { acceptInvite(it) } }
                    role == "wearer" && !onboarded -> WearerOnboarding(this) { Hush.store.onboarded = true; onboarded = true }
                    role == "wearer" -> WearerHome(this)
                    else -> ContactHome(this, invite) { invite = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    private fun readInvite(i: Intent?) {
        val u = i?.data ?: return
        if (u.path == "/join") invite = (u.getQueryParameter("w") ?: return) to (u.getQueryParameter("t") ?: return)
    }

    override fun onResume() {
        super.onResume()
        if (Hush.store.role == "wearer" && Hush.store.deviceAddress != null) {
            WearerService.start(this)
            syncNow()
        }
    }
}

fun acceptInvite(invite: Pair<String, String>, done: (String) -> Unit = {}) = Hush.io.execute {
    val msg = runCatching {
        Hush.call("acceptInvite", org.json.JSONObject().put("wearer", invite.first).put("token", invite.second))
        "You're now an emergency contact. We'll alert you if they raise an alarm."
    }.getOrElse { "This invitation isn't valid for your phone number." }
    done(msg)
}

/** The wearer's full-screen cancel prompt (SPEC §9), shown over the lock screen. */
class IncidentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val length = Hush.store.pins?.length ?: 4
        setContent {
            HushTheme {
                IncidentScreen(
                    length,
                    check = { pin -> Hush.wearer.enterPin(pin, "cancel") { Hush.io.execute(it) }.also { syncNow() } },
                    onAcceptedHaptic = {
                        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                    },
                    onFinished = {
                        getSystemService(NotificationManager::class.java).cancel(WearerService.INCIDENT_NOTIFICATION_ID)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishAndRemoveTask() // nothing left in Recents
                    },
                )
            }
        }
    }
}

/** The contact's full-screen alarm (SPEC §8.3). */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incident = intent.getStringExtra("incident").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        setContent { HushTheme { AlarmScreen(this, incident, text) } }
    }
}
