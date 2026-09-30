package com.hush.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Composable
fun Screen(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

/** Runs a blocking call on `io` and reports back on the main thread. */
fun Activity.work(block: () -> String?, done: (String?) -> Unit) = Hush.io.execute {
    val r = runCatching(block).getOrElse { (it as? CallError)?.message ?: "Couldn't reach Hush. Check your connection." }
    runOnUiThread { done(r) }
}

// ---------------------------------------------------------------- sign in
@Composable
fun SignInScreen(activity: Activity) {
    var phone by remember { mutableStateOf("+65") }
    var code by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Screen("Hush") {
        Text("Sign in with your phone number.")
        if (verification == null) {
            OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            Button({
                PhoneAuthProvider.verifyPhoneNumber(PhoneAuthOptions.newBuilder(Hush.auth).setPhoneNumber(phone)
                    .setTimeout(60L, TimeUnit.SECONDS).setActivity(activity)
                    .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                        override fun onVerificationCompleted(c: PhoneAuthCredential) { Hush.auth.signInWithCredential(c) }
                        override fun onVerificationFailed(e: FirebaseException) { error = "Couldn't send a code to that number." }
                        override fun onCodeSent(id: String, t: PhoneAuthProvider.ForceResendingToken) { verification = id }
                    }).build())
            }) { Text("Send code") }
        } else {
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            Button({
                Hush.auth.signInWithCredential(PhoneAuthProvider.getCredential(verification!!, code))
                    .addOnFailureListener { error = "That code didn't work." }
            }) { Text("Sign in") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun RoleScreen(choose: (String) -> Unit) = Screen("How will you use Hush?") {
    Button({ choose("wearer") }, Modifier.fillMaxWidth()) { Text("I wear a Hush retainer") }
    OutlinedButton({ choose("contact") }, Modifier.fillMaxWidth()) { Text("Someone asked me to be their emergency contact") }
    Text(
        "What Hush stores: your name and phone number, your contacts' names and numbers, and your location only while an alarm is " +
            "active. Locations are deleted 30 days after an alarm closes; alarm records are kept for 1 year. Nothing is shared except the " +
            "text messages and calls sent to your contacts through our SMS provider.",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ---------------------------------------------------------------- wearer onboarding
@Composable
fun WearerOnboarding(activity: Activity, finished: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val next: () -> Unit = { step++ }
    when (step) {
        0 -> NameStep(activity, next)
        1 -> ContactsStep(activity, next)
        2 -> PinSetupStep(activity, next)
        3 -> PairStep(activity, next)
        4 -> CalibrateStep(next)
        else -> PermissionsStep(activity, finished)
    }
}

@Composable
private fun NameStep(activity: Activity, next: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Screen("Your name") {
        Text("Your contacts see this in alerts. Use the name they know you by.")
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Button({
            val u = Hush.auth.currentUser ?: return@Button
            Hush.db.document("users/${u.uid}").set(mapOf("name" to name.trim(), "roles" to mapOf("wearer" to true), "phone" to u.phoneNumber))
            next()
        }, enabled = name.isNotBlank()) { Text("Continue") }
    }
}

@Composable
fun ContactsStep(activity: Activity, next: () -> Unit) {
    val contacts = remember { mutableStateListOf<Pair<String, String>>() } // name, state
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("+65") }
    var msg by remember { mutableStateOf<String?>(null) }
    val uid = Hush.auth.currentUser?.uid
    DisposableEffect(uid) {
        val reg = Hush.db.collection("contacts/$uid/links").addSnapshotListener { q, _ ->
            contacts.clear()
            q?.documents?.forEach { contacts += (it.getString("name") ?: "") to (it.getString("state") ?: "") }
        }
        onDispose { reg.remove() }
    }
    Screen("Emergency contacts") {
        Text("Add 1 to 5 people. They get a text asking them to accept. Hush alerts them, never your phone.")
        contacts.forEach { (n, s) -> Text("$n: ${if (s == "active") "accepted" else "invited"}") }
        if (contacts.size < 5) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Their name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Their phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedButton({
                activity.work({ Hush.call("inviteContact", JSONObject().put("name", name.trim()).put("phone", phone)); null }) { msg = it }
                name = ""; phone = "+65"
            }, enabled = name.isNotBlank() && phone.length > 8) { Text("Invite") }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(next, enabled = contacts.isNotEmpty()) { Text("Continue") }
    }
}

@Composable
private fun PinSetupStep(activity: Activity, next: () -> Unit) {
    var real by remember { mutableStateOf<String?>(null) }
    var second by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var practice by remember { mutableStateOf(false) }
    when {
        practice -> PracticeStep(next)
        real == null -> Screen("Your PIN") {
            Text("Choose a 4 to 6 digit PIN. It cancels an alarm.")
            PinEntry { real = it }
        }
        second == null -> Screen("Your second PIN") {
            Text("Choose a second PIN of the same length. It also looks like it cancels the alarm, but your contacts are told " +
                "you were forced to cancel and to call 999. Use it if someone makes you turn Hush off.")
            Text("Tip: your first PIN with the last digit plus one, e.g. ${real!!.dropLast(1)}${(real!!.last().digitToInt() + 1) % 10}.",
                style = MaterialTheme.typography.bodySmall)
            PinEntry(length = real!!.length) { p -> if (p == real) msg = "It must be different from your first PIN." else second = p }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        else -> Screen("Saving…") {
            LaunchedEffect(Unit) {
                activity.work({ savePins(real!!, second!!, null) }) { err -> if (err == null) practice = true else { msg = err; real = null; second = null } }
            }
        }
    }
}

/** Sends both PINs once over TLS; keeps only the salt/nonce and an unordered keyed pair (SPEC §7.1–7.2). */
fun savePins(real: String, second: String, current: String?): String? {
    val data = JSONObject().put("pin", real).put("pin2", second)
    val r = if (current == null) Hush.call("setPins", data) else Hush.callWithPin("setPins", current, data, "current_proof")
    if (!r.getBoolean("ok")) return "Incorrect PIN."
    KeystorePinKey.import(unb64(r.getString("hash_key")))
    val a = r.getJSONObject("argon")
    Hush.store.pins = PinParams(unb64(r.getString("salt")), unb64(r.getString("nonce")),
        Argon(a.getInt("iterations"), a.getInt("memorySize"), a.getInt("parallelism"), a.getInt("hashLength")), real.length)
    Hush.store.pinPair = PinCrypto.pair(KeystorePinKey, real, second)
    return null
}

@Composable
private fun PinEntry(length: Int? = null, done: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(length ?: 6) }, label = { Text("PIN") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
    Button({ done(pin); pin = "" }, enabled = pin.length in 4..6 && (length == null || pin.length == length)) { Text("Continue") }
}

@Composable
private fun PracticeStep(next: () -> Unit) {
    var tries by remember { mutableStateOf(0) }
    var key by remember { mutableStateOf(0) }
    Column {
        Text("Practice: try both PINs. They look exactly the same on this phone. Nothing is sent.",
            Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.runtime.key(key) {
            IncidentScreen(Hush.store.pins?.length ?: 4, check = { Hush.wearer.pinMatches(it) }, onAcceptedHaptic = {}) { tries++; key++ }
        }
        if (tries >= 2) TextButton(next, Modifier.padding(24.dp)) { Text("I've practised both, continue") }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun PairStep(activity: Activity, next: () -> Unit) {
    var msg by remember { mutableStateOf("Put the retainer in its case and hold the case button for 5 seconds, then tap Search.") }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Screen("Pair your retainer") {
        Text(msg)
        Button({
            perms.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            val scanner = activity.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: return@Button
            msg = "Searching…"
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Gatt.SVC)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                object : ScanCallback() {
                    override fun onScanResult(type: Int, r: ScanResult) {
                        scanner.stopScan(this)
                        msg = "Found it. Accept the pairing request."
                        r.device.createBond()
                        pollBond(activity, r.device) { ok ->
                            if (!ok) { msg = "Pairing failed. Check the case button and try again."; return@pollBond }
                            Hush.store.deviceAddress = r.device.address
                            activity.work({
                                Hush.call("registerDevice", JSONObject().put("device_id", r.device.address).put("fw_version", "2"))
                                null
                            }) { err -> if (err == null) { WearerService.start(activity); next() } else msg = err }
                        }
                    }
                })
        }) { Text("Search") }
    }
}

@SuppressLint("MissingPermission")
private fun pollBond(activity: Activity, d: BluetoothDevice, done: (Boolean) -> Unit) {
    val h = android.os.Handler(activity.mainLooper)
    var n = 0
    h.post(object : Runnable {
        override fun run() {
            when {
                d.bondState == BluetoothDevice.BOND_BONDED -> done(true)
                n++ > 60 -> done(false)
                else -> h.postDelayed(this, 500)
            }
        }
    })
}

@Composable
private fun CalibrateStep(next: () -> Unit) {
    val status by Link.status.collectAsState()
    val connected by Link.connected.collectAsState()
    var phase by remember { mutableStateOf(0) }
    Screen("Calibrate") {
        when {
            !connected -> Text("Connecting to your retainer…")
            status?.wear != Wear.WORN && phase == 0 -> Text("Put the retainer in. This takes about 10 seconds to detect.")
            phase == 0 -> Button({ phase = 1 }) { Text("Start") }
            phase == 1 -> {
                Text("Relax your tongue and keep still…")
                LaunchedEffect(Unit) {
                    Link.write(Gatt.CALIBRATE, byteArrayOf(1)); delay(3500); phase = 2
                }
            }
            phase == 2 -> {
                Text("Now press your tongue forward and down against the pad as hard as you can, and hold…")
                LaunchedEffect(Unit) {
                    Link.write(Gatt.CALIBRATE, byteArrayOf(2)); delay(5500); Link.read(Gatt.STATUS); delay(500); phase = 3
                }
            }
            status?.calibrated == true -> {
                Text("Done. To raise an alarm: press and hold for 3 seconds, or press long twice.")
                Button(next) { Text("Continue") }
            }
            else -> {
                Text("That press didn't register. Try again, pressing harder.")
                Button({ phase = 1 }) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun PermissionsStep(activity: Activity, finished: () -> Unit) {
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Screen("Keep Hush running") {
        Text("Hush needs to stay connected and to share your location with your contacts during an alarm. It never shares your " +
            "location at other times.")
        Button({
            perms.launch(buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }) { Text("1. Allow location and notifications") }
        Button({ perms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }) { Text("2. Allow location all the time") }
        Button({
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        }) { Text("3. Don't let the battery saver stop Hush") }
        if (Build.VERSION.SDK_INT >= 34) Button({
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}")))
        }) { Text("4. Allow full-screen alerts") }
        Spacer(Modifier.height(12.dp))
        Button(finished) { Text("Finish") }
    }
}

// ---------------------------------------------------------------- wearer home
@Composable
fun WearerHome(activity: Activity) {
    val status by Link.status.collectAsState()
    val connected by Link.connected.collectAsState()
    var screen by remember { mutableStateOf("home") }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    var disarmed by remember { mutableStateOf(Hush.store.disarmed) }
    val length = Hush.store.pins?.length ?: 4
    when (screen) {
        "disarm" -> PinPad("Enter your PIN to disarm", length, pinMessage) { pin ->
            if (Hush.wearer.enterPin(pin, "disarm") { Hush.io.execute(it) }) { disarmed = true; screen = "home"; pinMessage = null }
            else pinMessage = "Incorrect PIN. Try again."
            syncNow()
        }
        "settings" -> SettingsScreen(activity) { screen = "home" }
        else -> Screen(when {
            !connected -> "Not connected"
            status?.wear == Wear.DOCKED -> "In the case"
            disarmed || status?.armed != true -> "Disarmed"
            else -> "Armed / Protected"
        }) {
            status?.let { Text("Retainer battery ${it.batteryMv} mV" + if (it.faults and 0x03 != 0) " · check the retainer fit" else "") }
            if (connected && status?.armed == true && !disarmed) OutlinedButton({ screen = "disarm" }) { Text("Disarm (e.g. to eat)") }
            OutlinedButton({ screen = "settings" }) { Text("Settings") }
            Text("History", style = MaterialTheme.typography.titleMedium)
            Hush.store.history.reversed().forEach { Text(it) }
        }
    }
}

@Composable
private fun SettingsScreen(activity: Activity, back: () -> Unit) {
    var delay by remember { mutableStateOf(60f) }
    var removing by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val length = Hush.store.pins?.length ?: 4
    askPin?.let { action -> PinPad("Enter your PIN", length, msg) { pin -> askPin = null; action(pin) }; return }
    Screen("Settings") {
        Text("Loud alarm after the retainer is removed: ${delay.toInt()} s")
        Slider(delay, { delay = it }, valueRange = 30f..120f, steps = 8)
        Button({
            askPin = { pin ->
                activity.work({
                    if (Hush.callWithPin("updateSettings", pin, JSONObject().put("removal_delay_s", delay.toInt())).getBoolean("ok")) "Saved."
                    else "Incorrect PIN."
                }) { msg = it }
            }
        }) { Text("Save") }
        Text("Contacts", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(removing ?: "", { removing = it }, label = { Text("Phone number to remove") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton({
            val phone = removing ?: return@OutlinedButton
            askPin = { pin ->
                activity.work({
                    if (Hush.callWithPin("removeContact", pin, JSONObject().put("phone", phone)).getBoolean("ok")) "Removed." else "Incorrect PIN."
                }) { msg = it }
            }
        }) { Text("Remove contact") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ Link.write(Gatt.HAPTIC, byteArrayOf(Haptic.TEST.toByte())) }) { Text("Test vibration") }
        }
        msg?.let { Text(it) }
        TextButton(back) { Text("Back") }
    }
}
