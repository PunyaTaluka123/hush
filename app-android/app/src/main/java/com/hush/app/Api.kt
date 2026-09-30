package com.hush.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud Functions callable protocol over plain HTTPS: POST {"data": ...} with
 * the Firebase ID token. Done by hand (not the Functions SDK) so the app
 * controls the exact request bytes (I1: fixed-size submitPin).
 */
fun interface Api {
    /** Blocking. Throws IOException when the network or server is unavailable (the caller retries). */
    fun post(function: String, body: ByteArray): ByteArray
}

class HttpApi(private val base: String, private val token: () -> String?) : Api {
    override fun post(function: String, body: ByteArray): ByteArray {
        val c = URL(base + function).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json")
            token()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val out = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code >= 500 || code == 429) throw IOException("HTTP $code")
            return out
        } finally {
            c.disconnect()
        }
    }
}

class CallError(val status: String, message: String) : Exception(message)

fun body(data: JSONObject): ByteArray = JSONObject().put("data", data).toString().toByteArray()

/** The callable's `result`, or CallError for an application error (bad input, permission). */
fun result(raw: ByteArray): JSONObject {
    val j = JSONObject(String(raw))
    j.optJSONObject("error")?.let { throw CallError(it.optString("status"), it.optString("message")) }
    return j.getJSONObject("result")
}
