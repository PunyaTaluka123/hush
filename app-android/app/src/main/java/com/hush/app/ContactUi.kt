package com.hush.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import org.json.JSONObject
import java.util.Locale

@Composable
fun ContactHome(activity: Activity, invite: Pair<String, String>?, inviteHandled: () -> Unit) {
    val uid = Hush.auth.currentUser?.uid ?: return
    val open = remember { mutableStateListOf<DocumentSnapshot>() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(invite) {
        invite?.let { acceptInvite(it) { m -> activity.runOnUiThread { msg = m; inviteHandled() } } }
    }
    DisposableEffect(uid) {
        val reg = Hush.db.collection("incidents").whereArrayContains("contacts", uid).whereEqualTo("open", true)
            .addSnapshotListener { q, _ -> open.clear(); q?.documents?.let(open::addAll) }
        onDispose { reg.remove() }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Screen("Hush contact") {
        msg?.let { Text(it) }
        if (open.isEmpty()) Text("No alarms right now. If someone you cover raises one, this phone will ring even when muted.")
        open.forEach { d ->
            Card(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().then(Modifier)) {
                    Text(d.getString("summary") ?: "", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                    Button({ activity.startActivity(Intent(activity, AlarmActivity::class.java).putExtra("incident", d.id)) }) { Text("Open") }
                }
            }
        }
        Text("So alerts can ring through a muted phone and Do Not Disturb:", style = MaterialTheme.typography.titleMedium)
        Button({ if (Build.VERSION.SDK_INT >= 33) perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("1. Allow notifications") }
        Button({ activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("2. Allow Do Not Disturb access") }
        if (Build.VERSION.SDK_INT >= 34) Button({
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}")))
        }) { Text("3. Allow full-screen alerts") }
        val dnd = activity.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        Text(if (dnd) "Do Not Disturb access: allowed" else "Do Not Disturb access: not yet allowed", style = MaterialTheme.typography.bodySmall)
    }
}

/** SPEC §8.3: "I'm on it", "Call 999", and "They're safe" (never one tap, I10). */
@Composable
fun AlarmScreen(activity: Activity, incidentId: String, initialText: String) {
    var inc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var fix by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    var showCall by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(incidentId) {
        val a = Hush.db.document("incidents/$incidentId").addSnapshotListener { s, _ -> inc = s }
        val b = Hush.db.collection("incidents/$incidentId/locations").orderBy("t", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { q, _ -> fix = q?.documents?.firstOrNull() }
        onDispose { a.remove(); b.remove() }
    }
    val lat = fix?.getDouble("lat")
    val lng = fix?.getDouble("lng")
    LaunchedEffect(lat, lng) {
        if (lat != null && lng != null) Hush.io.execute {
            @Suppress("DEPRECATION")
            val line = runCatching { Geocoder(activity, Locale.UK).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
            activity.runOnUiThread { address = line }
        }
    }
    val name = inc?.getString("wearer_name")?.trim()?.substringBefore(' ') ?: "them"
    val call = { fn: String, data: JSONObject, done: (JSONObject) -> Unit ->
        Hush.io.execute {
            val r = runCatching { Hush.call(fn, data.put("incident_id", incidentId)) }
            activity.runOnUiThread { r.onSuccess(done).onFailure { msg = "Couldn't reach Hush. Try again." } }
        }
    }
    Screen("Hush alert") {
        Text(inc?.getString("summary") ?: initialText, style = MaterialTheme.typography.titleLarge)
        val responders = (inc?.get("responders") as? Map<*, *>)?.values?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.orEmpty()
        if (responders.isNotEmpty()) Text("Responding: ${responders.joinToString()}")
        if (lat != null && lng != null) {
            Text("Last location: ${address ?: "%.5f, %.5f".format(lat, lng)} (±${fix?.getDouble("acc")?.toInt() ?: 0} m)")
            OutlinedButton({ activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) }) { Text("Open in maps") }
        }
        Button({ AlarmSound.stop(); call("contactAck", JSONObject()) { msg = "Others can see you're responding." } }, Modifier.fillMaxWidth()) {
            Text("I'm on it")
        }
        Button({
            AlarmSound.stop()
            showCall = true
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:999")))
        }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Call 999") }
        if (showCall) Card(Modifier.fillMaxWidth()) {
            Text("Tell the operator: \"$name may be in danger and unable to speak. Their last location is " +
                "${address ?: "unknown address"}, coordinates ${lat?.let { "%.5f".format(it) } ?: "?"}, ${lng?.let { "%.5f".format(it) } ?: "?"}.\"")
        }
        OutlinedButton({ resolving = true }, Modifier.fillMaxWidth()) { Text("They're safe") }
        msg?.let { Text(it) }
    }
    if (resolving) {
        var spoke by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resolving = false },
            title = { Text("Confirm $name is safe") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(spoke, { spoke = it })
                        Text("I spoke to $name by voice call or in person")
                    }
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type $name's first name") })
                }
            },
            confirmButton = {
                TextButton({
                    resolving = false
                    call("contactResolve", JSONObject().put("spoke_by_voice", spoke).put("typed_name", typed)) { r ->
                        msg = when (r.optString("result")) {
                            "resolved" -> "Marked safe. The other contacts have been told."
                            "awaiting_operator" -> "Thank you. A Hush operator will call to confirm before this alarm closes."
                            "refused" -> "That didn't match. Check the name, and only confirm after you've spoken to them."
                            else -> "This alarm has already ended."
                        }
                        if (r.optString("result") == "resolved") AlarmSound.stop()
                    }
                }, enabled = spoke && typed.isNotBlank()) { Text("Confirm") }
            },
            dismissButton = { TextButton({ resolving = false }) { Text("Cancel") } },
        )
    }
}
