package ly.payhub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.File

class NextActionTest {
    private val fixturesPath = File("../../shared/test-vectors/next-action-fixtures.json")

    @TestFactory
    fun decodes(): List<DynamicTest> {
        val doc = Json.parseToJsonElement(fixturesPath.readText()) as JsonObject
        val fixtures = doc["fixtures"] as JsonArray
        return fixtures.map { f ->
            val fx = f as JsonObject
            val name = (fx["name"] as JsonPrimitive).content
            val kind = (fx["expect_kind"] as JsonPrimitive).content
            val payload = fx["json"]!!
            DynamicTest.dynamicTest(name) {
                val na = NextAction.fromJson(payload)
                when (kind) {
                    "OtpRequired" -> assertTrue(na is NextAction.OtpRequired)
                    "Redirect" -> assertTrue(na is NextAction.Redirect)
                    "QR" -> assertTrue(na is NextAction.QR)
                    "Lightbox" -> assertTrue(na is NextAction.Lightbox)
                    else -> error("unknown kind: $kind")
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun unknownTypeThrows() {
        val node = Json.parseToJsonElement("""{"type":"new_thing"}""")
        assertThrows<IllegalArgumentException> { NextAction.fromJson(node) }
    }

    @org.junit.jupiter.api.Test
    fun nullReturnsNull() {
        val node = Json.parseToJsonElement("null")
        assertNull(NextAction.fromJson(node))
    }
}
