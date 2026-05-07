// Webhook signature verification.
//
// Algorithmic reference: app/core/signing.py. Header is
//   Hub-Signature: t=<unix>,v1=<hmac_sha256_hex>
// Signed bytes: "${t}.".toByteArray(UTF-8) + body. Default tolerance ±300 s.
//
// Every PayHub SDK ports the same algorithm; the canonical fixtures at
// sdks/shared/test-vectors/webhook-signing.json are the spec.

package ly.payhub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

public sealed class WebhookSignatureException(message: String) : PayhubException(message)
public class MalformedHeaderException internal constructor(message: String) : WebhookSignatureException(message)
public class TimestampOutOfToleranceException internal constructor(public val skewSeconds: Int) :
    WebhookSignatureException("webhook timestamp out of tolerance: ${skewSeconds}s skew")
public class InvalidSignatureException internal constructor(message: String) : WebhookSignatureException(message)

public data class WebhookEventPayload(
    val id: String = "",
    val type: String = "",
    val paymentId: String = "",
    val prevStatus: String? = null,
    val newStatus: String = "",
    val source: String = "",
    val payload: Map<String, JsonElement> = emptyMap(),
    val createdAt: String = "",
)

public object WebhookEvent {
    public const val DEFAULT_TOLERANCE_SECONDS: Int = 300

    private val parser = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Verify a webhook delivery and return the decoded event.
     * @throws MalformedHeaderException header missing `t=` or `v1=`.
     * @throws TimestampOutOfToleranceException `|now - t|` exceeds tolerance.
     * @throws InvalidSignatureException HMAC mismatch or non-JSON body.
     */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        secret: ByteArray,
        body: ByteArray,
        header: String,
        toleranceSeconds: Int = DEFAULT_TOLERANCE_SECONDS,
        now: Long = System.currentTimeMillis() / 1000L,
    ): WebhookEventPayload {
        val (t, v1) = parseHeader(header)
        val skew = abs(now - t)
        if (skew > toleranceSeconds) throw TimestampOutOfToleranceException(skew.toInt())

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update("$t.".toByteArray(Charsets.UTF_8))
        val digest = mac.doFinal(body)
        val expected = digest.toHexLower().toByteArray(Charsets.US_ASCII)
        val received = v1.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, received)) {
            throw InvalidSignatureException("Hub-Signature v1 does not match")
        }
        return decodePayload(body)
    }

    /** Convenience overload: secret/body as UTF-8 strings. */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        secret: String,
        body: String,
        header: String,
        toleranceSeconds: Int = DEFAULT_TOLERANCE_SECONDS,
        now: Long = System.currentTimeMillis() / 1000L,
    ): WebhookEventPayload =
        verify(secret.toByteArray(Charsets.UTF_8), body.toByteArray(Charsets.UTF_8),
            header, toleranceSeconds, now)

    private fun parseHeader(header: String): Pair<Long, String> {
        var t: String? = null
        var v1: String? = null
        for (seg in header.split(',')) {
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            val k = seg.substring(0, eq).trim()
            val v = seg.substring(eq + 1).trim()
            if (k == "t") t = v
            else if (k == "v1") v1 = v
        }
        if (t == null || v1 == null) {
            throw MalformedHeaderException("Hub-Signature missing t or v1: '$header'")
        }
        val ts = t.toLongOrNull()
            ?: throw MalformedHeaderException("Hub-Signature t is not an integer: '$t'")
        return ts to v1
    }

    private fun decodePayload(body: ByteArray): WebhookEventPayload {
        if (body.isEmpty()) return WebhookEventPayload()
        val root = try {
            parser.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidSignatureException("webhook body is not JSON: ${e.message}")
        }
        if (root !is JsonObject) {
            throw InvalidSignatureException("webhook body is not a JSON object")
        }
        val payload = (root["payload"] as? JsonObject)?.toMap() ?: emptyMap()
        return WebhookEventPayload(
            id = root.str("id"),
            type = root.str("type"),
            paymentId = root.str("payment_id"),
            prevStatus = root.strOrNull("prev_status"),
            newStatus = root.str("new_status"),
            source = root.str("source"),
            payload = payload,
            createdAt = root.str("created_at"),
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        val hex = "0123456789abcdef".toCharArray()
        for (i in indices) {
            val b = this[i].toInt() and 0xff
            out[i * 2] = hex[b ushr 4]
            out[i * 2 + 1] = hex[b and 0x0f]
        }
        return String(out)
    }
}
