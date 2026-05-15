package ly.payhub

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevicesTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() { server.shutdown() }

    private fun jsonResp(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun client(access: String? = "acc-token") =
        PayhubMerchantClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = access,
            refreshToken = "ref-token",
        )

    @Test
    fun listDevicesDecodes() = runTest {
        server.enqueue(jsonResp("""
            [{"id":"d1","platform":"android","created_at":"2026-05-14T10:00:00Z","last_seen_at":"2026-05-14T10:05:00Z"},
             {"id":"d2","platform":"ios","created_at":"2026-05-13T10:00:00Z","last_seen_at":"2026-05-14T11:00:00Z"}]
        """.trimIndent()))
        val list = client().devices.list()
        assertEquals(2, list.size)
        assertEquals("d1", list[0].id)
        assertEquals("android", list[0].platform)
        assertEquals("ios", list[1].platform)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/merchant/devices", req.path)
        assertEquals("Bearer acc-token", req.getHeader("Authorization"))
    }

    @Test
    fun registerAndroidPostsFcmTokenAndReturnsInfo() = runTest {
        server.enqueue(jsonResp("""
            {"id":"dev-77","platform":"android","created_at":"2026-05-14T10:00:00Z","last_seen_at":"2026-05-14T10:00:00Z"}
        """.trimIndent()))
        val info = client().devices.registerAndroid("fcm-token-abc")
        assertEquals("dev-77", info.id)
        assertEquals("android", info.platform)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/merchant/devices", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"platform\":\"android\""), "body: $body")
        assertTrue(body.contains("\"token\":\"fcm-token-abc\""), "body: $body")
    }

    @Test
    fun registerIosUsesIosPlatform() = runTest {
        server.enqueue(jsonResp("""
            {"id":"dev-88","platform":"ios","created_at":"2026-05-14T10:00:00Z","last_seen_at":"2026-05-14T10:00:00Z"}
        """.trimIndent()))
        client().devices.registerIos("abc123hex")
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"platform\":\"ios\""), "body: $body")
        assertTrue(body.contains("\"token\":\"abc123hex\""), "body: $body")
    }

    @Test
    fun registerWebIncludesKeys() = runTest {
        server.enqueue(jsonResp("""
            {"id":"dev-99","platform":"web","created_at":"2026-05-14T10:00:00Z","last_seen_at":"2026-05-14T10:00:00Z"}
        """.trimIndent()))
        client().devices.register(
            platform = "web",
            token = "https://fcm.googleapis.com/sub-endpoint",
            keys = WebPushKeys(p256dh = "p256dh-value", auth = "auth-value"),
        )
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"platform\":\"web\""), "body: $body")
        assertTrue(body.contains("\"p256dh\":\"p256dh-value\""), "body: $body")
        assertTrue(body.contains("\"auth\":\"auth-value\""), "body: $body")
    }

    @Test
    fun unregisterDeletesWithTokenBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client().devices.unregister("fcm-old")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/merchant/devices", req.path)
        assertEquals("""{"token":"fcm-old"}""", req.body.readUtf8())
    }
}
