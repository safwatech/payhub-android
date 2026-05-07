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
import org.junit.jupiter.api.assertThrows

class ClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun client(maxRetries: Int = 0) =
        PayhubClient("phk_a.b", baseUrl = server.url("/").toString().trimEnd('/'),
            maxRetries = maxRetries)

    @Test
    fun rejectsBadApiKey() {
        assertThrows<IllegalArgumentException> { PayhubClient("not-a-key") }
    }

    @Test
    fun createDecodesPaymentAndSetsHeaders() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"pay_1","status":"requires_action","psp":"sadad","psp_ref":"TXN_1",
                       "next_action":{"type":"otp_required","psp_ref":"TXN_1","masked_destination":"2189...12"},
                       "amount_minor":4500,"currency":"LYD","merchant_order_ref":"ord-1"}"""
                )
        )
        val c = client()
        val p = c.payments.create(
            CreatePaymentRequest(psp = "sadad", merchantOrderRef = "ord-1", amountMinor = 4500)
        )
        assertEquals("requires_action", p.status)
        assertTrue(p.nextAction is NextAction.OtpRequired)

        val req = server.takeRequest()
        assertEquals("Bearer phk_a.b", req.getHeader("Authorization"))
        assertNotNull(req.getHeader("Idempotency-Key"))
    }

    @Test
    fun maps401ToAuthentication() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"hub.unauthenticated","message":"no"}}""")
        )
        val c = client()
        assertThrows<AuthenticationException> { c.health.check() }
    }

    @Test
    fun retriesOn503ThenSucceeds() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":{"code":"hub.unavailable","message":"x"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","psps":["sadad"]}""")
        )
        val c = client(maxRetries = 2)
        val h = c.health.check()
        assertEquals("ok", h.status)
        assertEquals(2, server.requestCount)
    }
}
