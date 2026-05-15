package ly.payhub

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MerchantClientTest {

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

    private fun jsonResp(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun client(onTokens: ((TokenPair) -> Unit)? = null, access: String? = null, refresh: String? = null) =
        PayhubMerchantClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = access,
            refreshToken = refresh,
            onTokensRefreshed = onTokens,
        )

    @Test
    fun loginStoresTokensAndCallsBack() = runTest {
        server.enqueue(jsonResp("""{"access_token":"acc1","refresh_token":"ref1","expires_in":1800}"""))
        var saved: TokenPair? = null
        val c = client(onTokens = { saved = it })
        val res = c.auth.login("acme", "boss", "pw-1234567890")
        assertFalse(res.requiresMfa)
        assertEquals("acc1", res.tokens?.accessToken)
        assertEquals("ref1", saved?.refreshToken)
        val req = server.takeRequest()
        assertEquals("/merchant/auth/token", req.path)
        assertTrue(req.body.readUtf8().contains("\"merchant_code\":\"acme\""))
    }

    @Test
    fun loginSurfacesMfaChallenge() = runTest {
        server.enqueue(jsonResp("""{"requires_mfa":true,"challenge_token":"chal-xyz"}"""))
        val res = client().auth.login("acme", "boss", "pw-1234567890")
        assertTrue(res.requiresMfa)
        assertEquals("chal-xyz", res.challengeToken)
        assertNull(res.tokens)
    }

    @Test
    fun payLinkCreateSendsBearerAndDecodes() = runTest {
        server.enqueue(
            jsonResp(
                """{"id":"pl_1","short_token":"abc123","url":"https://x/pay/abc123","status":"active",
                   "amount_minor":4500,"currency":"LYD","merchant_order_ref":"ord-1","attempts":0,
                   "extend_count":0,"reshared_count":0,"clone_generation":0}""".trimIndent(),
            ),
        )
        val c = client(access = "acc-token")
        val link = c.payLinks.create(CreatePayLinkRequest(amountMinor = 4500, merchantOrderRef = "ord-1"))
        assertEquals("pl_1", link.id)
        assertEquals("abc123", link.shortToken)
        val req = server.takeRequest()
        assertEquals("/merchant/pay-links", req.path)
        assertEquals("Bearer acc-token", req.getHeader("Authorization"))
    }

    @Test
    fun dashboardDecodes() = runTest {
        server.enqueue(
            jsonResp(
                """{"window_hours":24,"scope":"merchant","payments_by_status":[{"status":"succeeded","count":3,"volume_minor":13500}],
                   "inflight":1,"active_pay_links":4,"needs_followup":2}""".trimIndent(),
            ),
        )
        val d = client(access = "a").reports.dashboard(windowHours = 24)
        assertEquals(24, d.windowHours)
        assertEquals(4, d.activePayLinks)
        assertEquals(3, d.paymentsByStatus.first().count)
        assertTrue(server.takeRequest().path!!.startsWith("/merchant/dashboard"))
    }

    @Test
    fun decodesMerchantDashboardWithSubBreakdown() = runTest {
        server.enqueue(
            jsonResp(
                """{"window_hours":24,"scope":"merchant","payments_by_status":[{"status":"succeeded","count":3,"volume_minor":1500}],
                   "inflight":1,"active_pay_links":2,"needs_followup":0,"recent_payments":[],
                   "sub_breakdown":[{"sub_merchant_id":"sm_1","code":"A","name":"Shop A","paid_count":2,"paid_volume_minor":1000}]}""".trimIndent(),
            ),
        )
        val d = client(access = "tok").reports.dashboard(windowHours = 24, groupBySub = true)
        assertNotNull(d.subBreakdown)
        assertEquals("sm_1", d.subBreakdown?.first()?.subMerchantId)
        assertEquals(2, d.subBreakdown?.first()?.paidCount)
        val path = server.takeRequest().path!!
        assertTrue(path.contains("window_hours=24"))
        assertTrue(path.contains("group_by=sub"))
    }

    @Test
    fun merchantValidationErrorCarriesCodeAndParams() {
        val e = MerchantValidationError("merchant.last_owner", listOf("alice"), "must keep one owner")
        assertEquals("merchant.last_owner", e.code)
        assertEquals(listOf("alice"), e.params)
        assertEquals(422, e.httpStatus)
    }

    @Test
    fun mfaRequiredErrorIsConstructible() {
        val e = MfaRequiredError("need TOTP")
        assertEquals("mfa_required", e.code)
        assertEquals(401, e.httpStatus)
    }

    @Test
    fun mfaRequiredEnvelopeBecomesMfaRequiredError() = runTest {
        server.enqueue(
            jsonResp(
                """{"error":{"code":"hub.merchant.mfa_required","message":"MFA code required"}}""",
                401,
            ),
        )
        try {
            client(access = "tok").account.changePassword("oldpw-1234", "newpw-1234567")
            fail("expected MfaRequiredError")
        } catch (e: MfaRequiredError) {
            assertEquals("hub.merchant.mfa_required", e.code)
            assertEquals(401, e.httpStatus)
        }
    }

    @Test
    fun validationEnvelopeBecomesMerchantValidationError() = runTest {
        // A 409 with the `hub.merchant.last_sub_owner` code + a `params` array
        // surfaces as MerchantValidationError so apps can localise the message
        // via the error catalogue.
        server.enqueue(
            jsonResp(
                """{"error":{"code":"hub.merchant.last_sub_owner","message":"would leave zero owners","params":["alice"]}}""",
                409,
            ),
        )
        try {
            client(access = "tok").subMerchants.users.disable("sm_1", "u_1")
            fail("expected MerchantValidationError")
        } catch (e: MerchantValidationError) {
            assertEquals("hub.merchant.last_sub_owner", e.code)
            assertEquals(listOf("alice"), e.params)
            assertEquals(409, e.httpStatus)
        }
    }

    @Test
    fun refreshesOnceAfter401ThenRetries() = runTest {
        // 1) first /merchant/auth/me → 401
        server.enqueue(jsonResp("""{"error":{"code":"hub.merchant.token_invalid","message":"expired"}}""", 401))
        // 2) /merchant/auth/token/refresh → new pair
        server.enqueue(jsonResp("""{"access_token":"acc2","refresh_token":"ref2","expires_in":1800}"""))
        // 3) retried /merchant/auth/me → 200
        server.enqueue(
            jsonResp(
                """{"id":"u1","username":"boss","role":"owner","effective_role":"owner",
                   "merchant":{"id":"m1","code":"acme","name":"Acme","type":"company"},"entitlements":{}}""".trimIndent(),
            ),
        )
        var saved: TokenPair? = null
        val c = client(onTokens = { saved = it }, access = "acc1", refresh = "ref1")
        val me = c.auth.me()
        assertEquals("boss", me.username)
        assertEquals("ref2", saved?.refreshToken)
        assertEquals("acc2", c.currentTokens()?.accessToken)
        // The three calls in order.
        assertEquals("/merchant/auth/me", server.takeRequest().path)
        assertEquals("/merchant/auth/token/refresh", server.takeRequest().path)
        val retried = server.takeRequest()
        assertEquals("/merchant/auth/me", retried.path)
        assertEquals("Bearer acc2", retried.getHeader("Authorization"))
        assertNotNull(me.merchant)
    }
}
