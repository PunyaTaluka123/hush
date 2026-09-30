package com.hush.app

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** argon2id parameters, issued by the server (backend/functions/src/pins.ts). */
data class Argon(val iterations: Int, val memoryKiB: Int, val parallelism: Int, val hashLength: Int)

/** Server-side PIN material the phone keeps (no PINs, no labels). */
data class PinParams(val salt: ByteArray, val nonce: ByteArray, val argon: Argon, val length: Int)

/** Keyed hash for the offline PIN check (SPEC §7.2). */
fun interface PinKey {
    fun mac(data: ByteArray): ByteArray
}

/** The server-issued hashing key, imported into Android Keystore (non-exportable). */
object KeystorePinKey : PinKey {
    private const val ALIAS = "hush_pin_key"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun import(raw: ByteArray) = ks().setEntry(
        ALIAS, KeyStore.SecretKeyEntry(SecretKeySpec(raw, "HmacSHA256")), KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
    )

    override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(ks().getKey(ALIAS, null) as SecretKey)
        doFinal(data)
    }
}

object PinCrypto {
    fun argon2id(pin: String, salt: ByteArray, a: Argon): ByteArray {
        val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(a.iterations).withMemoryAsKB(a.memoryKiB).withParallelism(a.parallelism).withSalt(salt).build()
        return ByteArray(a.hashLength).also { out -> Argon2BytesGenerator().apply { init(p) }.generateBytes(pin.toByteArray(), out) }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    /** Fixed-size proof: HMAC(nonce, argon2id(pin) | key | purpose | incident). Only the server can tell which PIN it was. */
    fun proof(pin: String, p: PinParams, key: String, purpose: String, incident: String): String =
        b64(hmac(p.nonce, argon2id(pin, p.salt, p.argon), "|$key|$purpose|$incident".toByteArray()))

    /** Unordered, unlabelled pair for the offline check: the phone cannot tell which PIN is which (I7). */
    fun pair(key: PinKey, a: String, b: String): Set<String> = setOf(hex(key.mac(a.toByteArray())), hex(key.mac(b.toByteArray())))
}

fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
