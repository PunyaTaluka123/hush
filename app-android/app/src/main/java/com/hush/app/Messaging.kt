package com.hush.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * Push messages. Contacts get alerts (data-only, so the app owns the alarm);
 * the wearer only ever gets "retainer not connected", shown without sound.
 */
class Messaging : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Hush.auth.currentUser?.uid?.let { Hush.saveToken(it, token) }
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        val text = d["text"] ?: return
        val nm = getSystemService(NotificationManager::class.java)
        if (d["kind"] == "link_lost") {
            nm.notify(3, Notification.Builder(this, Channels.STATUS).setSmallIcon(R.drawable.ic_hush).setContentTitle("Hush")
                .setContentText(text).build())
            return
        }
        // Delivery receipt for the incident's audit log (SPEC §13)
        d["msg"]?.let { msg -> Hush.io.execute { runCatching { Hush.call("notificationReceipt", JSONObject().put("msg", msg)) } } }
        val incident = d["incident"].orEmpty()
        val open = PendingIntent.getActivity(this, incident.hashCode(),
            Intent(this, AlarmActivity::class.java).putExtra("incident", incident).putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val critical = d["priority"] == "critical"
        if (critical) AlarmSound.start(this)
        nm.notify(incident.hashCode(), Notification.Builder(this, if (critical) Channels.EMERGENCY else Channels.UPDATES)
            .setSmallIcon(R.drawable.ic_hush).setContentTitle(if (critical) "Hush emergency" else "Hush")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setCategory(if (critical) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .apply { if (critical) setFullScreenIntent(open, true) }
            .setAutoCancel(!critical).build())
    }
}

/** Contact's alarm: plays on the alarm stream (rings through a muted phone and alarm-allowed DND, I5) until acknowledged. */
object AlarmSound {
    private var player: MediaPlayer? = null

    @Synchronized fun start(ctx: Context) {
        if (player != null) return
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            isLooping = true
            prepare()
            start()
        }
    }

    @Synchronized fun stop() {
        player?.run { stop(); release() }
        player = null
    }
}
