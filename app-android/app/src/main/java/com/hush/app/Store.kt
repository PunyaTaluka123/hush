package com.hush.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's local state. Nothing here may differ between the two PINs (I1):
 * there is no field that could, because the phone never learns which one was
 * entered.
 */
class Store(private val p: SharedPreferences) {
    var role: String?
        get() = p.getString("role", null)
        set(v) = p.edit().putString("role", v).apply()
    var incident: String
        get() = p.getString("incident", ZERO_ID)!!
        set(v) = p.edit().putString("incident", v).apply()
    var disarmed: Boolean
        get() = p.getBoolean("disarmed", false)
        set(v) = p.edit().putBoolean("disarmed", v).apply()
    var locationUntil: Long
        get() = p.getLong("location_until", 0)
        set(v) = p.edit().putLong("location_until", v).apply()
    var deviceAddress: String?
        get() = p.getString("device", null)
        set(v) = p.edit().putString("device", v).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    var pins: PinParams?
        get() = p.getString("pins", null)?.let {
            val j = JSONObject(it)
            val a = j.getJSONObject("argon")
            PinParams(unb64(j.getString("salt")), unb64(j.getString("nonce")),
                Argon(a.getInt("iterations"), a.getInt("memorySize"), a.getInt("parallelism"), a.getInt("hashLength")), j.getInt("length"))
        }
        set(v) = p.edit().putString("pins", v?.let {
            JSONObject().put("salt", b64(it.salt)).put("nonce", b64(it.nonce)).put("length", it.length).put("argon",
                JSONObject().put("iterations", it.argon.iterations).put("memorySize", it.argon.memoryKiB)
                    .put("parallelism", it.argon.parallelism).put("hashLength", it.argon.hashLength)).toString()
        }).apply()
    var pinPair: Set<String>
        get() = p.getStringSet("pin_pair", emptySet())!!
        set(v) = p.edit().putStringSet("pin_pair", v).apply()

    val history: List<String> get() = JSONArray(p.getString("history", "[]")).let { a -> List(a.length()) { a.getString(it) } }

    @Synchronized fun addHistory(line: String) {
        val kept = (history + line).takeLast(50)
        p.edit().putString("history", JSONArray(kept).toString()).apply()
    }

    // ---- outgoing calls that must survive an app kill (PIN proofs), in order
    class Queued(val at: Long, val function: String, val body: ByteArray)

    @Synchronized fun enqueue(at: Long, function: String, body: ByteArray) {
        val q = JSONArray(p.getString("queue", "[]")).put(JSONObject().put("at", at).put("fn", function).put("body", String(body)))
        p.edit().putString("queue", q.toString()).commit()
    }

    @Synchronized fun peek(): Queued? = JSONArray(p.getString("queue", "[]")).optJSONObject(0)?.let {
        Queued(it.getLong("at"), it.getString("fn"), it.getString("body").toByteArray())
    }

    @Synchronized fun pop() {
        val q = JSONArray(p.getString("queue", "[]"))
        if (q.length() > 0) q.remove(0)
        p.edit().putString("queue", q.toString()).commit()
    }

    /** Everything stored, for the §7.3 comparison test. */
    fun dump(): Map<String, *> = p.all.toSortedMap()
}
