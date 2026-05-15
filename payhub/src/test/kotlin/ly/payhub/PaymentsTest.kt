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

class PaymentsTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() { server = MockWebServer().also { it.start() } }
    @AfterEach
    fun stop() { server.shutdown() }

    private fun jsonResp(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun client() = PayhubMerchantClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        accessToken = "acc-token", refreshToken = "ref-token",
    )

    @Test
    fun listPaymentsDecodes() = runTest {
        server.enqueue(jsonResp("""
            [{"id":"p1","psp_code":"moamalat","psp_ref":"M-1","merchant_order_ref":"ord-1",
              "amount_minor":4500,"currency":"LYD","status":"succeeded",
              "created_at":"2026-05-14T10:00:00Z","updated_at":"2026-05-14T10:01:00Z"},
             {"id":"p2","psp_code":"sadad","merchant_order_ref":"ord-2",
              "amount_minor":12000,"currency":"LYD","status":"pending",
              "created_at":"2026-05-14T09:00:00Z","updated_at":"2026-05-14T09:00:30Z"}]
        """.trimIndent()))
        val rows = client().payments.list(psp = "moamalat", status = "succeeded", limit = 10, offset = 5)
        assertEquals(2, rows.size)
        assertEquals("p1", rows[0].id)
        assertEquals("moamalat", rows[0].pspCode)
        assertEquals(4500L, rows[0].amountMinor)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path?.startsWith("/merchant/payments?") == true, "path: ${req.path}")
        val path = req.path!!
        assertTrue(path.contains("psp=moamalat"))
        assertTrue(path.contains("status=succeeded"))
        assertTrue(path.contains("limit=10"))
        assertTrue(path.contains("offset=5"))
    }

    @Test
    fun listPaymentsForwardsSubMerchantIdFilter() = runTest {
        server.enqueue(jsonResp("[]"))
        client().payments.list(subMerchantId = "sm_42")
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("sub_merchant_id=sm_42"), "path: ${req.path}")
    }

    @Test
    fun getPaymentReturnsDetailWithEvents() = runTest {
        server.enqueue(jsonResp("""
            {"id":"p-xyz","psp_code":"moamalat","psp_ref":"M-X","merchant_order_ref":"ord-9",
             "amount_minor":99900,"currency":"LYD","status":"succeeded",
             "created_at":"2026-05-14T10:00:00Z","updated_at":"2026-05-14T10:05:00Z",
             "events":[{"id":"e1","event_type":"webhook","prev_status":"pending","new_status":"succeeded",
                        "source":"psp","created_at":"2026-05-14T10:05:00Z"}],
             "metadata":{"order_id":"ord-9","extra":42}}
        """.trimIndent()))
        val detail = client().payments.get("p-xyz")
        assertEquals("p-xyz", detail.id)
        assertEquals(1, detail.events.size)
        assertEquals("webhook", detail.events[0].eventType)
        assertNotNull(detail.metadata["order_id"])
        val req = server.takeRequest()
        assertEquals("/merchant/payments/p-xyz", req.path)
    }
}
