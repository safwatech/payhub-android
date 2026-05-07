package ly.payhub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.util.Base64

class WebhookSigningVectorsTest {

    private val vectorsPath = File("../../shared/test-vectors/webhook-signing.json")

    @TestFactory
    fun vectors(): List<DynamicTest> {
        val doc = Json.parseToJsonElement(vectorsPath.readText()) as JsonObject
        val cases = (doc["cases"] as kotlinx.serialization.json.JsonArray)
        return cases.map { case ->
            val c = case as JsonObject
            val name = (c["name"] as JsonPrimitive).content
            DynamicTest.dynamicTest(name) { runVector(c) }
        }
    }

    private fun runVector(c: JsonObject) {
        val secret = hex(c.str("secret_hex"))
        val body = Base64.getDecoder().decode(c.str("body_b64"))
        val header = c.str("header")
        val tolerance = (c["tolerance_seconds"] as JsonPrimitive).int()
        val now = (c["now"] as JsonPrimitive).long
        when (val expect = c.str("expect")) {
            "ok" -> WebhookEvent.verify(secret, body, header, tolerance, now)
            "TimestampOutOfTolerance" -> assertThrows<TimestampOutOfToleranceException> {
                WebhookEvent.verify(secret, body, header, tolerance, now)
            }
            "InvalidSignature" -> assertThrows<InvalidSignatureException> {
                WebhookEvent.verify(secret, body, header, tolerance, now)
            }
            "MalformedHeader" -> assertThrows<MalformedHeaderException> {
                WebhookEvent.verify(secret, body, header, tolerance, now)
            }
            else -> error("unknown expect: $expect")
        }
    }

    @org.junit.jupiter.api.Test
    fun validV1ReturnsTypedPayload() {
        val doc = Json.parseToJsonElement(vectorsPath.readText()) as JsonObject
        val cases = doc["cases"] as kotlinx.serialization.json.JsonArray
        val c = cases.map { it as JsonObject }.first { it.str("name") == "valid_v1" }
        val secret = hex(c.str("secret_hex"))
        val body = Base64.getDecoder().decode(c.str("body_b64"))
        val ev = WebhookEvent.verify(secret, body, c.str("header"),
            (c["tolerance_seconds"] as JsonPrimitive).int(),
            (c["now"] as JsonPrimitive).long)
        assertEquals("evt_1", ev.id)
        assertEquals("payment.succeeded", ev.type)
        assertEquals("pay_1", ev.paymentId)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as JsonPrimitive).contentOrNull ?: ""

    private fun JsonPrimitive.int(): Int = intOrNull ?: content.toInt()

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) +
                Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
