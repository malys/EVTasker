package com.mg4.tasker.debug

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Uploads a report to a PrivateBin instance.
 *
 * PrivateBin is zero-knowledge: the server stores ciphertext and never sees the key, which
 * travels in the URL fragment and is therefore never sent in an HTTP request. That is what
 * makes pasting a vehicle diagnostic — VIN-free, but full of firmware and rule detail —
 * acceptable at all. The extra password means the link alone is not enough either.
 *
 * The format is PrivateBin's v2 API and every constant here is load-bearing:
 *
 *  - the key is 32 random bytes, base58 in the fragment;
 *  - PBKDF2-HMAC-SHA256 over `key ‖ password`, 100 000 iterations, 8-byte salt;
 *  - AES-256-GCM, 16-byte IV, 128-bit tag;
 *  - the authenticated data is the **exact** JSON text of `adata` as it is sent, because the
 *    browser that later decrypts re-serialises the array it received with `JSON.stringify`.
 *    Reordering it, adding a space, or letting a JSON library format it breaks decryption on
 *    the reader's side while the upload still succeeds — so `adata` is built as a string
 *    here and embedded verbatim;
 *  - the plaintext is raw DEFLATE (no zlib header) of `{"paste":"…"}`.
 *
 * No Android dependency: the whole encoding is unit-testable on the JVM, and [poster] is
 * injectable so a test can assert the wire format without a network.
 */
object PrivateBin {

    /** PrivateBin's own defaults; the instance may shorten [expire], never lengthen it. */
    data class Config(
        val baseUrl: String,
        val password: String,
        val expire: String,
        val formatter: String,
    )

    sealed interface Outcome {
        /** The paste URL, key fragment included — the only form that can be read back. */
        data class Ok(val url: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 16
    private const val SALT_BYTES = 8

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun paste(
        text: String,
        config: Config,
        random: SecureRandom = SecureRandom(),
        poster: (String, String) -> String = ::post,
    ): Outcome {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)

        val adata = adataJson(iv, salt, config.formatter)
        val derived = deriveKey(key, config.password, salt)
        // Built element by element rather than reflected off a map: this app has already
        // been bitten once by R8 stripping the generic signatures Gson's reflective path
        // needs, and a payload that only fails in a minified build is the worst kind.
        val payload = JsonObject().apply { addProperty("paste", text) }.toString()
        val plain = rawDeflate(payload.toByteArray())
        val cipherText = encrypt(derived, iv, plain, adata.toByteArray())

        val body = """{"v":2,"adata":$adata,"ct":"${base64(cipherText)}",""" +
            """"meta":{"expire":"${config.expire}"}}"""

        val response = try {
            poster(config.baseUrl, body)
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }

        val json = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
            ?: return Outcome.Failed("unreadable response")
        if (json.get("status")?.asInt != 0) {
            return Outcome.Failed(json.get("message")?.asString ?: "refused by the server")
        }
        val path = json.get("url")?.asString ?: return Outcome.Failed("no url in the response")
        return Outcome.Ok(
            config.baseUrl.trimEnd('/') + "/" + path.trimStart('/') + "#" + base58(key)
        )
    }

    // -------------------------------------------------------------------------

    /**
     * `JSON.stringify` of the metadata array, byte for byte: no spaces, this field order.
     * Both the server's signature check and the reader's decryption depend on it.
     */
    private fun adataJson(iv: ByteArray, salt: ByteArray, formatter: String): String =
        """[["${base64(iv)}","${base64(salt)}",$ITERATIONS,$KEY_BITS,$TAG_BITS,""" +
            """"aes","gcm","zlib"],"$formatter",0,0]"""

    /**
     * PBKDF2-HMAC-SHA256 over the raw key bytes with the password appended.
     *
     * Hand-rolled rather than `SecretKeyFactory`, which takes a `char[]` and would have to
     * encode 32 random bytes as text to accept them — there is no encoding that survives
     * that round trip, and PrivateBin hashes the bytes.
     */
    private fun deriveKey(key: ByteArray, password: String, salt: ByteArray): ByteArray {
        val secret = key + password.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val out = ByteArray(KEY_BITS / 8)
        var offset = 0
        var block = 1
        while (offset < out.size) {
            var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, block.toByte()))
            val acc = u.copyOf()
            repeat(ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in acc.indices) acc[i] = (acc[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(acc.size, out.size - offset)
            acc.copyInto(out, offset, 0, take)
            offset += take
            block++
        }
        return out
    }

    private fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
            doFinal(plain)
        }

    /** Raw DEFLATE — no zlib wrapper, which is what PrivateBin's "zlib" compression means. */
    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun base64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    /** Bitcoin alphabet, as PrivateBin's fragment encoding uses. */
    private fun base58(data: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger(1, data)
        val fifty8 = BigInteger.valueOf(58)
        val out = StringBuilder()
        while (value.signum() > 0) {
            val (quotient, remainder) = value.divideAndRemainder(fifty8)
            out.append(alphabet[remainder.toInt()])
            value = quotient
        }
        // Leading zero bytes carry no magnitude, so they must be re-added explicitly.
        for (byte in data) {
            if (byte.toInt() != 0) break
            out.append(alphabet[0])
        }
        return out.reverse().toString()
    }

    private fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            // PrivateBin answers HTML to anything that does not claim to be its own client.
            setRequestProperty("X-Requested-With", "JSONHttpRequest")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("HTTP ${connection.responseCode}, empty body")
        } finally {
            connection.disconnect()
        }
    }
}
