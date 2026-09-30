package com.hush.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.location.LocationManagerCompat

/**
 * Wearer foreground service (SPEC §9): keeps the BLE link, forwards retainer
 * events to the server, writes ACK / HAPTIC back, heartbeats every 60 s, and
 * streams location every 15 s while the phone-side location window is open.
 * Its notification text never changes with incidents (I1).
 */
class WearerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var ticks = 0

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, armedNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        Link.onEvent = { ev -> if (Hush.wearer.onIndication(ev)) main.post(::openCancelScreen); sync() }
        Hush.store.deviceAddress?.let { Link.connect(this, it) }
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Link.close()
        super.onDestroy()
    }

    // every 15 s: retry anything queued, stream location if the window is open, heartbeat every 60 s
    private val tick: Runnable = object : Runnable {
        override fun run() {
            sync()
            if (Hush.wearer.wantsLocation()) locate { fix -> Hush.io.execute { Hush.wearer.post("pushLocation", Hush.wearer.locationBody(listOf(fix))) } }
            if (ticks++ % 4 == 0 && (Link.status.value?.armed == true || Hush.wearer.wantsLocation())) heartbeat()
            main.postDelayed(this, 15_000)
        }
    }

    private fun sync() = syncNow()

    private fun heartbeat() {
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val send = { fix: Fix? ->
            Hush.io.execute { Hush.wearer.post("heartbeat", Hush.wearer.heartbeatBody(Link.status.value, Link.connected.value, battery, fix)) }
        }
        if (Hush.wearer.wantsLocation()) locate(send) else send(null)
    }

    @SuppressLint("MissingPermission")
    private fun locate(done: (Fix) -> Unit) {
        val lm = getSystemService(LocationManager::class.java)
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching {
            LocationManagerCompat.getCurrentLocation(lm, provider, null as android.os.CancellationSignal?, mainExecutor, androidx.core.util.Consumer<Location?> { l ->
                if (l != null) done(Fix(System.currentTimeMillis(), l.latitude, l.longitude, l.accuracy.toDouble()))
            })
        }
    }

    /** Vibration and a full-screen PIN pad; never a sound (I4). */
    private fun openCancelScreen() {
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(Channels.INCIDENT_VIBRATION, -1))
        val open = PendingIntent.getActivity(this, 1, Intent(this, IncidentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(INCIDENT_NOTIFICATION_ID, Notification.Builder(this, Channels.INCIDENT)
            .setSmallIcon(R.drawable.ic_hush).setContentTitle("Hush").setContentText("Enter your PIN")
            .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(open, true).setOngoing(true).build())
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val INCIDENT_NOTIFICATION_ID = 2

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, WearerService::class.java))

        /** One text, always: it must not change after an alarm, whichever PIN ended it (I1). */
        fun armedNotification(ctx: Context): Notification = Notification.Builder(ctx, Channels.ARMED)
            .setSmallIcon(R.drawable.ic_hush).setContentTitle("Hush").setContentText("Hush is running")
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
    }
}
