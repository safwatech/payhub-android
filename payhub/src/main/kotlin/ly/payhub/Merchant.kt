// Merchant-portal client (SDK 1.1.0+).
//
// The existing [PayhubClient] is the API-key client — it talks to `/v1`
// with an `Authorization: Bearer phk_...` and is for backend integrations.
// [PayhubMerchantClient] is the *bearer-token* client the native apps use:
// it talks to `/merchant/*` with a short-lived stateless access token, holds
// a long-lived refresh token, and on a 401 transparently refreshes (once)
// via `/merchant/auth/token/refresh` and retries. Both ride the same OkHttp
// transport machinery; only the credential model differs.
//
// Typical flow:
//   val c = PayhubMerchantClient()                       // anonymous
//   val tp = c.auth.login(merchantCode, username, pw)    // -> sets tokens
//   c.payLinks.create(...)                               // bearer-authed
//   // persist tp via onTokensRefreshed and rebuild the client next launch

package ly.payhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

@Serializable
public data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

/** Either a token pair or an MFA challenge — the shape `/merchant/auth/token`
 *  returns. Inspect [requiresMfa]; if true, call [PayhubMerchantClient.Auth.loginMfa]
 *  with [challengeToken]. */
public data class LoginResult(
    val tokens: TokenPair?,
    val requiresMfa: Boolean,
    val challengeToken: String?,
)

@Serializable
public data class SubMerchantRef(
    val id: String,
    val code: String,
    @SerialName("code_prefix") val codePrefix: String,
    val name: String,
)

@Serializable
public data class Entitlements(
    val aggregator: Boolean = false,
    @SerialName("smart_routing") val smartRouting: Boolean = false,
    @SerialName("pay_link_quota") val payLinkQuota: Int = 0,
)

@Serializable
public data class MerchantRefInfo(
    val id: String,
    val code: String,
    val name: String,
    val type: String = "company",
)

@Serializable
public data class MerchantMe(
    val id: String,
    val username: String,
    val role: String,
    @SerialName("effective_role") val effectiveRole: String,
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val mobile: String? = null,
    @SerialName("mfa_enabled") val mfaEnabled: Boolean = false,
    val merchant: MerchantRefInfo,
    @SerialName("sub_merchant") val subMerchant: SubMerchantRef? = null,
    val entitlements: Entitlements = Entitlements(),
    @SerialName("push_public_key") val pushPublicKey: String? = null,
)

@Serializable
public data class CreatePayLinkRequest(
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String = "LYD",
    @SerialName("merchant_order_ref") val merchantOrderRef: String,
    val description: String? = null,
    @SerialName("allowed_psps") val allowedPsps: List<String>? = null,
    @SerialName("customer_msisdn_hint") val customerMsisdnHint: String? = null,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int? = null,
    val language: String = "both",
)

@Serializable
public data class PayLink(
    val id: String,
    @SerialName("short_token") val shortToken: String,
    val url: String,
    val status: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("merchant_order_ref") val merchantOrderRef: String,
    val description: String? = null,
    @SerialName("allowed_psps") val allowedPsps: List<String>? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val attempts: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("extend_count") val extendCount: Int = 0,
    @SerialName("last_extended_at") val lastExtendedAt: String? = null,
    @SerialName("reshared_count") val resharedCount: Int = 0,
    @SerialName("last_shared_at") val lastSharedAt: String? = null,
    @SerialName("clone_generation") val cloneGeneration: Int = 0,
    @SerialName("cloned_from_id") val clonedFromId: String? = null,
)

@Serializable
public data class PayLinkList(
    val items: List<PayLink> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
public data class DashboardOutcome(
    val status: String,
    val count: Int = 0,
    @SerialName("volume_minor") val volumeMinor: Long = 0,
)

/** Per-sub row inside the parent dashboard's `?group_by=sub` breakdown.
 *  Mirrors `SubBreakdownRow` in `app/api/merchant/dashboard.py`. */
@Serializable
public data class SubBreakdownRow(
    @SerialName("sub_merchant_id") val subMerchantId: String,
    val code: String,
    val name: String,
    @SerialName("paid_count") val paidCount: Int,
    @SerialName("paid_volume_minor") val paidVolumeMinor: Long,
)

@Serializable
public data class MerchantDashboard(
    @SerialName("window_hours") val windowHours: Int,
    val scope: String = "merchant",
    @SerialName("sub_merchant_id") val subMerchantId: String? = null,
    @SerialName("payments_by_status") val paymentsByStatus: List<DashboardOutcome> = emptyList(),
    val inflight: Int = 0,
    @SerialName("active_pay_links") val activePayLinks: Int = 0,
    @SerialName("needs_followup") val needsFollowup: Int = 0,
    /** Populated only when [Reports.dashboard] is called with `groupBySub=true`
     *  by a parent-scoped user; null on a sub-scoped dashboard. */
    @SerialName("sub_breakdown") val subBreakdown: List<SubBreakdownRow>? = null,
)

// ----- Payments -----

/** A single payment in the list view. Mirrors `PaymentRowOut`. */
@Serializable
public data class PaymentRow(
    val id: String,
    @SerialName("psp_code") val pspCode: String,
    @SerialName("psp_ref") val pspRef: String? = null,
    @SerialName("merchant_order_ref") val merchantOrderRef: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** One row in a payment's status-history timeline. Mirrors `PaymentEventOut`. */
@Serializable
public data class PaymentEvent(
    val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("prev_status") val prevStatus: String? = null,
    @SerialName("new_status") val newStatus: String? = null,
    val source: String,
    @SerialName("created_at") val createdAt: String,
)

/** Full payment detail incl. events + (PSP-specific) metadata. Mirrors `PaymentDetailOut`. */
@Serializable
public data class PaymentDetail(
    val id: String,
    @SerialName("psp_code") val pspCode: String,
    @SerialName("psp_ref") val pspRef: String? = null,
    @SerialName("merchant_order_ref") val merchantOrderRef: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val events: List<PaymentEvent> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

// ----- Account / MFA -----

/** TOTP enrolment payload — the secret + otpauth URI you'd hand to an authenticator app. */
@Serializable
public data class MfaEnrol(
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
    val issuer: String = "",
    val account: String = "",
)

@Serializable
internal data class ChangePasswordBody(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
    val code: String? = null,
)

@Serializable
internal data class CodeBody(val code: String)

@Serializable
internal data class PasswordBody(val password: String)

// ----- Organisation -----

/** Merchant organisation profile. Mirrors `OrgOut`. */
@Serializable
public data class OrgInfo(
    val id: String,
    val code: String,
    val name: String,
    val type: String,
    val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("legal_name") val legalName: String? = null,
    @SerialName("tax_number") val taxNumber: String? = null,
    @SerialName("commercial_register_no") val commercialRegisterNo: String? = null,
    @SerialName("billing_email") val billingEmail: String? = null,
    @SerialName("support_email") val supportEmail: String? = null,
    val phone: String? = null,
    val website: String? = null,
    @SerialName("address_line_1") val addressLine1: String? = null,
    @SerialName("address_line_2") val addressLine2: String? = null,
    val city: String? = null,
    val country: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

/**
 * `PATCH /merchant/org` body. Only non-null fields are serialised
 * (`explicitNulls = false`), so the caller sets a field to `""` to clear it
 * (server coerces `""` → `null`), to a new value to change it, or leaves it
 * `null` to omit it.
 */
@Serializable
public data class OrgPatch(
    val name: String? = null,
    val type: String? = null,
    @SerialName("legal_name") val legalName: String? = null,
    @SerialName("tax_number") val taxNumber: String? = null,
    @SerialName("commercial_register_no") val commercialRegisterNo: String? = null,
    @SerialName("billing_email") val billingEmail: String? = null,
    @SerialName("support_email") val supportEmail: String? = null,
    val phone: String? = null,
    val website: String? = null,
    @SerialName("address_line_1") val addressLine1: String? = null,
    @SerialName("address_line_2") val addressLine2: String? = null,
    val city: String? = null,
    val country: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

// ----- Sub-Merchants -----

@Serializable
public data class SubMerchant(
    val id: String,
    @SerialName("merchant_id") val merchantId: String = "",
    val code: String,
    @SerialName("code_prefix") val codePrefix: String,
    val name: String,
    val status: String,
    @SerialName("external_ref") val externalRef: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("payments_count") val paymentsCount: Int = 0,
)

@Serializable
public data class SubMerchantCreate(
    val code: String,
    @SerialName("code_prefix") val codePrefix: String,
    val name: String,
    val status: String = "active",
    @SerialName("external_ref") val externalRef: String? = null,
)

@Serializable
public data class SubMerchantPatch(
    val name: String? = null,
    val status: String? = null,
    @SerialName("external_ref") val externalRef: String? = null,
)

@Serializable
public data class SubUser(
    val id: String,
    @SerialName("sub_merchant_id") val subMerchantId: String = "",
    val username: String,
    val role: String,
    val status: String,
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val mobile: String? = null,
    val phone: String? = null,
    @SerialName("mfa_enabled") val mfaEnabled: Boolean = false,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
public data class SubUserCreate(
    val username: String,
    @SerialName("full_name") val fullName: String,
    val email: String? = null,
    val mobile: String? = null,
    val phone: String? = null,
    val role: String = "sub_operator",
)

/** POST .../users response — a [SubUser] plus the freshly minted invite link. */
@Serializable
public data class SubUserCreated(
    val id: String,
    @SerialName("sub_merchant_id") val subMerchantId: String = "",
    val username: String,
    val role: String,
    val status: String,
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val mobile: String? = null,
    val phone: String? = null,
    @SerialName("mfa_enabled") val mfaEnabled: Boolean = false,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("invite_url") val inviteUrl: String,
    @SerialName("invite_sent_to_channel") val inviteSentToChannel: String? = null,
    @SerialName("invite_expires_at") val inviteExpiresAt: String = "",
)

@Serializable
public data class SubUserPatch(
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val mobile: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val status: String? = null,
)

/** POST .../users/{uid}/reissue-invite response. */
@Serializable
public data class ReissueInvite(
    @SerialName("sent_to_channel") val sentToChannel: String? = null,
    @SerialName("invite_url") val inviteUrl: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

// ----- Dashboard sub-breakdown response envelope -----
// (SubBreakdownRow itself lives next to MerchantDashboard above — it's also
// embedded in the full dashboard when called with `groupBySub=true`.)

@Serializable
public data class SubBreakdownResponse(
    @SerialName("window_hours") val windowHours: Int = 24,
    @SerialName("sub_breakdown") val subBreakdown: List<SubBreakdownRow> = emptyList(),
)

// ----- Settlements -----

/** A reconciled settlement file from the PSP. Mirrors `SettlementFileOut`. */
@Serializable
public data class SettlementFile(
    val id: String,
    @SerialName("psp_code") val pspCode: String,
    val filename: String,
    @SerialName("file_sha256") val fileSha256: String,
    @SerialName("period_from") val periodFrom: String? = null,
    @SerialName("period_to") val periodTo: String? = null,
    @SerialName("row_count") val rowCount: Int = 0,
    @SerialName("matched_count") val matchedCount: Int = 0,
    @SerialName("mismatch_count") val mismatchCount: Int = 0,
    @SerialName("missing_in_hub_count") val missingInHubCount: Int = 0,
    @SerialName("missing_in_psp_count") val missingInPspCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
)

/** One row from a settlement file. Mirrors `SettlementRowOut`. */
@Serializable
public data class SettlementRow(
    val id: String,
    @SerialName("merchant_order_ref") val merchantOrderRef: String? = null,
    @SerialName("psp_ref") val pspRef: String? = null,
    @SerialName("psp_status") val pspStatus: String? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    val status: String,
    val diff: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
)

/** A single registered push device for the signed-in merchant user. */
@Serializable
public data class DeviceInfo(
    val id: String,
    /** "android", "ios", or "web". */
    val platform: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
)

/** Web Push VAPID subscription keys — required only for `platform="web"`. */
@Serializable
public data class WebPushKeys(
    val p256dh: String,
    val auth: String,
)

@Serializable
internal data class RegisterDeviceBody(
    val platform: String,
    val token: String,
    val keys: WebPushKeys? = null,
)

@Serializable
internal data class UnregisterDeviceBody(val token: String)

// ----- Account profile -----

/** Profile patch body for `PATCH /merchant/auth/me`. Only non-null fields are
 *  sent on the wire (the SDK's PATCH encoder drops nulls); a field omitted on
 *  the server side means "leave untouched". Mobile/email changes clear the
 *  matching `_verified_at` flag — the user must re-verify. */
@Serializable
public data class ProfilePatch(
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    @SerialName("preferred_lang") val preferredLang: String? = null,
)

// ----- Merchant API keys (sub-scoped) -----

/** A sub-merchant API key, masked (no plaintext secret). Mirrors `ApiKeyOut`. */
@Serializable
public data class MerchantApiKey(
    val id: String,
    @SerialName("key_id") val keyId: String,
    val scopes: List<String> = emptyList(),
    val status: String,
    @SerialName("allowed_ips") val allowedIps: List<String> = emptyList(),
    @SerialName("rate_limit_tier") val rateLimitTier: String = "standard",
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sub_merchant_id") val subMerchantId: String? = null,
)

/** Result of `POST /merchant/sub-merchants/{sid}/api-keys` — the plaintext
 *  secret is returned **once** at creation and must be copied immediately; it
 *  is not retrievable later (only an argon2 hash is stored). Format on the
 *  wire is `phk_<key_id>.<secret>`. */
@Serializable
public data class MerchantApiKeyWithSecret(
    val id: String,
    @SerialName("key_id") val keyId: String,
    val scopes: List<String> = emptyList(),
    val status: String,
    @SerialName("allowed_ips") val allowedIps: List<String> = emptyList(),
    @SerialName("rate_limit_tier") val rateLimitTier: String = "standard",
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sub_merchant_id") val subMerchantId: String? = null,
    val secret: String,
)

@Serializable
internal data class ApiKeyCreateBody(
    val scopes: List<String>,
    @SerialName("allowed_ips") val allowedIps: List<String> = emptyList(),
    @SerialName("rate_limit_tier") val rateLimitTier: String = "standard",
)

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

public class PayhubMerchantClient @JvmOverloads constructor(
    baseUrl: String = PayhubClient.DEFAULT_BASE_URL,
    accessToken: String? = null,
    refreshToken: String? = null,
    /** Called whenever the token pair changes (login / refresh) so the host
     *  app can persist it (e.g. to an encrypted DataStore). */
    private val onTokensRefreshed: ((TokenPair) -> Unit)? = null,
    timeoutSeconds: Long = 30L,
    httpClient: OkHttpClient? = null,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    @Volatile private var accessToken: String? = accessToken
    @Volatile private var refreshToken: String? = refreshToken

    // Coalesces concurrent refreshes — when N requests get 401 at once, only
    // one of them runs /auth/token/refresh; the others wait, then retry with
    // the new access token. The double-check inside `request()` makes sure we
    // don't re-refresh just because we won the lock.
    private val refreshMutex = Mutex()

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    public val auth: Auth = Auth(this)
    public val payLinks: PayLinks = PayLinks(this)
    public val reports: Reports = Reports(this)
    public val devices: Devices = Devices(this)
    public val payments: Payments = Payments(this)
    public val settlements: Settlements = Settlements(this)
    public val account: Account = Account(this)
    public val mfa: Mfa = Mfa(this)
    public val org: Org = Org(this)
    public val subMerchants: SubMerchants = SubMerchants(this)

    /** The currently-held token pair, or null if not logged in. */
    public fun currentTokens(): TokenPair? {
        val a = accessToken
        val r = refreshToken
        return if (a != null && r != null) TokenPair(a, r, 0) else null
    }

    private fun setTokens(tp: TokenPair) {
        accessToken = tp.accessToken
        refreshToken = tp.refreshToken
        onTokensRefreshed?.invoke(tp)
    }

    internal suspend fun request(
        method: String,
        path: String,
        body: String?,
        authed: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val tokenAtSend = accessToken
        val first = exec(method, path, body, includeAuth = authed)
        if (first.status != 401 || !authed || refreshToken == null) {
            return@withContext first.successOrThrow()
        }
        // 401 on an authed call → coalesced refresh, then retry once.
        refreshMutex.withLock {
            // Double-check: if the token changed while we waited, another
            // caller already refreshed — don't re-refresh, just retry.
            if (accessToken == tokenAtSend) {
                val rt = refreshToken ?: return@withLock
                val refreshed = runCatching {
                    val raw = exec("POST", "/merchant/auth/token/refresh",
                        json.encodeToString(mapOf("refresh_token" to rt)), includeAuth = false)
                    if (raw.status !in 200..299) error("refresh failed: ${raw.status}")
                    json.decodeFromString(TokenPair.serializer(), raw.bodyText)
                }.getOrNull()
                if (refreshed != null) setTokens(refreshed)
            }
        }
        // If the token still didn't move (refresh failed), surface the 401.
        if (accessToken == tokenAtSend) return@withContext first.successOrThrow()
        exec(method, path, body, includeAuth = true).successOrThrow()
    }

    private data class HttpResult(val status: Int, val bodyText: String, val retryAfter: Int?) {
        fun successOrThrow(): String {
            if (status in 200..299) return bodyText
            val parsed = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject
            throw ErrorMapping.fromEnvelope(status, parsed, retryAfter)
        }
    }

    private suspend fun exec(method: String, path: String, body: String?, includeAuth: Boolean): HttpResult {
        val rb = body?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("User-Agent", "payhub-android/${PayhubClient.VERSION} (merchant)")
        if (includeAuth) accessToken?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(rb ?: byteArrayOf().toRequestBody(JSON_MEDIA))
            "PATCH" -> builder.patch(rb ?: byteArrayOf().toRequestBody(JSON_MEDIA))
            "DELETE" -> builder.delete(rb)
            else -> error("unsupported method: $method")
        }
        try {
            val resp = http.newCall(builder.build()).await()
            try {
                return HttpResult(
                    status = resp.code,
                    bodyText = resp.body?.string().orEmpty(),
                    retryAfter = resp.header("Retry-After")?.toIntOrNull(),
                )
            } finally {
                resp.close()
            }
        } catch (e: SocketTimeoutException) {
            throw TimeoutException(e.message ?: "timed out", e)
        } catch (e: IOException) {
            throw ConnectionException(e.message ?: "io error", e)
        }
    }

    // ----- auth -----

    public class Auth internal constructor(private val c: PayhubMerchantClient) {
        public suspend fun login(
            merchantCode: String,
            username: String,
            password: String,
            subCode: String? = null,
        ): LoginResult {
            val raw = c.request("POST", "/merchant/auth/token", c.json.encodeToString(
                buildMap {
                    put("merchant_code", merchantCode); put("username", username); put("password", password)
                    if (subCode != null) put("sub_code", subCode)
                }
            ), authed = false)
            return c.consumeTokenResponse(raw)
        }

        public suspend fun loginMfa(challengeToken: String, code: String): LoginResult {
            val raw = c.request("POST", "/merchant/auth/token/mfa", c.json.encodeToString(
                mapOf("challenge_token" to challengeToken, "code" to code)
            ), authed = false)
            return c.consumeTokenResponse(raw)
        }

        public suspend fun tokenRefresh(refreshToken: String): TokenPair {
            val raw = c.request("POST", "/merchant/auth/token/refresh",
                c.json.encodeToString(mapOf("refresh_token" to refreshToken)), authed = false)
            val tp = c.json.decodeFromString(TokenPair.serializer(), raw)
            c.setTokens(tp)
            return tp
        }

        public suspend fun logout() {
            val rt = c.refreshToken ?: return
            runCatching {
                c.request("POST", "/merchant/auth/token/revoke",
                    c.json.encodeToString(mapOf("refresh_token" to rt)), authed = false)
            }
            c.accessToken = null
            c.refreshToken = null
        }

        public suspend fun forgotPassword(merchantCode: String, username: String, subCode: String? = null) {
            c.request("POST", "/merchant/auth/forgot-password", c.json.encodeToString(
                buildMap {
                    put("merchant_code", merchantCode); put("username", username)
                    if (subCode != null) put("sub_code", subCode)
                }
            ), authed = false)
        }

        public suspend fun acceptInvite(token: String, newPassword: String) {
            c.request("POST", "/merchant/auth/accept-invite",
                c.json.encodeToString(mapOf("token" to token, "new_password" to newPassword)), authed = false)
        }

        public suspend fun me(): MerchantMe =
            c.json.decodeFromString(MerchantMe.serializer(), c.request("GET", "/merchant/auth/me", null))
    }

    private fun consumeTokenResponse(raw: String): LoginResult {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw DecodeException("login body is not a JSON object")
        val requiresMfa = (obj["requires_mfa"] as? JsonPrimitive)?.content == "true"
        if (requiresMfa) {
            val challenge = (obj["challenge_token"] as? JsonPrimitive)?.content
            return LoginResult(tokens = null, requiresMfa = true, challengeToken = challenge)
        }
        val tp = json.decodeFromString(TokenPair.serializer(), raw)
        setTokens(tp)
        return LoginResult(tokens = tp, requiresMfa = false, challengeToken = null)
    }

    // ----- pay-links -----

    public class PayLinks internal constructor(private val c: PayhubMerchantClient) {
        public suspend fun create(request: CreatePayLinkRequest): PayLink =
            c.json.decodeFromString(
                PayLink.serializer(),
                c.request("POST", "/merchant/pay-links", c.json.encodeToString(request)),
            )

        public suspend fun list(
            status: String? = null,
            expiringBefore: String? = null,
            bucket: String? = null,
            limit: Int? = null,
            cursor: String? = null,
        ): PayLinkList {
            val q = buildList {
                if (status != null) add("status=$status")
                if (expiringBefore != null) add("expiring_before=$expiringBefore")
                if (bucket != null) add("bucket=$bucket")
                if (limit != null) add("limit=$limit")
                if (cursor != null) add("cursor=$cursor")
            }.joinToString("&")
            val path = "/merchant/pay-links" + if (q.isEmpty()) "" else "?$q"
            return c.json.decodeFromString(PayLinkList.serializer(), c.request("GET", path, null))
        }

        public suspend fun get(id: String): PayLink =
            c.json.decodeFromString(PayLink.serializer(), c.request("GET", "/merchant/pay-links/$id", null))

        public suspend fun cancel(id: String): PayLink =
            c.json.decodeFromString(PayLink.serializer(), c.request("POST", "/merchant/pay-links/$id/cancel", null))

        public suspend fun extend(id: String, additionalSeconds: Int): PayLink =
            c.json.decodeFromString(PayLink.serializer(), c.request("POST", "/merchant/pay-links/$id/extend",
                c.json.encodeToString(mapOf("additional_seconds" to additionalSeconds))))

        public suspend fun clone(id: String): PayLink =
            c.json.decodeFromString(PayLink.serializer(), c.request("POST", "/merchant/pay-links/$id/clone", null))

        public suspend fun markShared(id: String): PayLink =
            c.json.decodeFromString(
                PayLink.serializer(),
                c.request("POST", "/merchant/pay-links/$id/mark-shared", null),
            )
    }

    // ----- reports -----

    public class Reports internal constructor(private val c: PayhubMerchantClient) {
        public suspend fun dashboard(windowHours: Int? = null, groupBySub: Boolean = false): MerchantDashboard {
            val q = buildList {
                if (windowHours != null) add("window_hours=$windowHours")
                if (groupBySub) add("group_by=sub")
            }.joinToString("&")
            return c.json.decodeFromString(
                MerchantDashboard.serializer(),
                c.request("GET", "/merchant/dashboard" + if (q.isEmpty()) "" else "?$q", null),
            )
        }

        /** Per-sub paid-volume breakdown for a parent merchant. The full dashboard
         *  has more fields; this is the narrower-result helper for the apps'
         *  "sub-merchant scoreboard" panel. */
        public suspend fun dashboardBySub(windowHours: Int = 24): SubBreakdownResponse =
            c.json.decodeFromString(
                SubBreakdownResponse.serializer(),
                c.request("GET",
                    "/merchant/dashboard?group_by=sub&window_hours=$windowHours", null),
            )
    }

    // ----- payments -----

    public class Payments internal constructor(private val c: PayhubMerchantClient) {
        /** List payments for the signed-in merchant. A parent caller may filter
         *  to one of their subs via [subMerchantId]; a sub-scoped caller's
         *  [subMerchantId] is ignored server-side (forced to the caller's sub). */
        public suspend fun list(
            psp: String? = null,
            status: String? = null,
            subMerchantId: String? = null,
            limit: Int = 50,
            offset: Int = 0,
        ): List<PaymentRow> {
            val q = buildList {
                if (!psp.isNullOrBlank()) add("psp=$psp")
                if (!status.isNullOrBlank()) add("status=$status")
                if (!subMerchantId.isNullOrBlank()) add("sub_merchant_id=$subMerchantId")
                add("limit=$limit")
                add("offset=$offset")
            }.joinToString("&")
            return c.json.decodeFromString(
                ListSerializer(PaymentRow.serializer()),
                c.request("GET", "/merchant/payments?$q", null),
            )
        }

        /** Fetch one payment incl. its event history. */
        public suspend fun get(id: String): PaymentDetail =
            c.json.decodeFromString(
                PaymentDetail.serializer(),
                c.request("GET", "/merchant/payments/$id", null),
            )
    }

    // ----- account / MFA -----

    public class Account internal constructor(private val c: PayhubMerchantClient) {
        /** Fetch the signed-in user's profile (alias for [Auth.me]). */
        public suspend fun me(): MerchantMe = c.auth.me()

        /** Change the signed-in user's password. When MFA is enabled the
         *  server requires a current TOTP code in `code`; without it the
         *  server returns 401 with envelope `code = "hub.merchant.mfa_required"`
         *  which the SDK surfaces as [MfaRequiredError]. */
        public suspend fun changePassword(oldPassword: String, newPassword: String, code: String? = null) {
            c.request("POST", "/merchant/auth/change-password",
                c.json.encodeToString(ChangePasswordBody(oldPassword, newPassword, code)))
        }

        /** Sparse profile update — only non-null fields in [patch] are sent.
         *  A mobile or email change clears the matching `_verified_at` flag
         *  on the server side; the user must re-verify via the verification
         *  endpoints. */
        public suspend fun updateProfile(patch: ProfilePatch): MerchantMe =
            c.json.decodeFromString(MerchantMe.serializer(),
                c.request("PATCH", "/merchant/auth/me", c.json.encodeToString(patch)))

        /** Begin TOTP enrolment. The server returns the secret + otpauth URI;
         *  the caller renders a QR (and a copy-paste secret), then
         *  [mfaConfirm]s with the first 6-digit code. */
        public suspend fun mfaEnrol(): MfaEnrol =
            c.json.decodeFromString(MfaEnrol.serializer(),
                c.request("POST", "/merchant/auth/mfa/enrol", null))

        /** Confirm an in-progress enrolment with the first 6-digit TOTP code. */
        public suspend fun mfaConfirm(code: String) {
            c.request("POST", "/merchant/auth/mfa/confirm",
                c.json.encodeToString(CodeBody(code)))
        }

        /** Disable TOTP — requires re-supplying the password (defence in depth). */
        public suspend fun mfaDisable(password: String) {
            c.request("POST", "/merchant/auth/mfa/disable",
                c.json.encodeToString(PasswordBody(password)))
        }
    }

    /** Alias namespace exposing the MFA endpoints under `client.mfa.*` —
     *  the same routes the [Account] namespace covers. Kept as a separate
     *  surface so app code reads naturally (`client.mfa.enrol()` vs
     *  `client.account.mfaEnrol()`); both reach the same server endpoints. */
    public class Mfa internal constructor(private val c: PayhubMerchantClient) {
        /** Begin TOTP enrolment — see [Account.mfaEnrol]. */
        public suspend fun enrol(): MfaEnrol = c.account.mfaEnrol()

        /** Confirm enrolment with the first 6-digit code — see [Account.mfaConfirm]. */
        public suspend fun confirm(code: String): Unit = c.account.mfaConfirm(code)

        /** Disable MFA, password-gated — see [Account.mfaDisable]. */
        public suspend fun disable(password: String): Unit = c.account.mfaDisable(password)
    }

    // ----- organisation profile -----

    public class Org internal constructor(private val c: PayhubMerchantClient) {
        /** Fetch the merchant organisation's public+private profile. */
        public suspend fun get(): OrgInfo =
            c.json.decodeFromString(OrgInfo.serializer(),
                c.request("GET", "/merchant/org", null))

        /** Sparse PATCH — only non-null fields in [patch] are sent. */
        public suspend fun update(patch: OrgPatch): OrgInfo =
            c.json.decodeFromString(OrgInfo.serializer(),
                c.request("PATCH", "/merchant/org", c.json.encodeToString(patch)))
    }

    // ----- sub-merchants + nested users -----

    public class SubMerchants internal constructor(private val c: PayhubMerchantClient) {
        /** Sub-merchant user / cashier management — nested under
         *  `client.subMerchants.users.{list, create, update, disable,
         *  reissueInvite, clearMfa}`. */
        public val users: Users = Users(c)

        /** Sub-merchant API-key management — nested under
         *  `client.subMerchants.apiKeys.{list, create, revoke}`. The plaintext
         *  secret is only returned at create time; store it immediately. */
        public val apiKeys: ApiKeys = ApiKeys(c)

        public suspend fun list(): List<SubMerchant> =
            c.json.decodeFromString(
                ListSerializer(SubMerchant.serializer()),
                c.request("GET", "/merchant/sub-merchants", null),
            )

        public suspend fun get(id: String): SubMerchant =
            c.json.decodeFromString(SubMerchant.serializer(),
                c.request("GET", "/merchant/sub-merchants/$id", null))

        public suspend fun create(body: SubMerchantCreate): SubMerchant =
            c.json.decodeFromString(SubMerchant.serializer(),
                c.request("POST", "/merchant/sub-merchants", c.json.encodeToString(body)))

        public suspend fun update(id: String, body: SubMerchantPatch): SubMerchant =
            c.json.decodeFromString(SubMerchant.serializer(),
                c.request("PATCH", "/merchant/sub-merchants/$id", c.json.encodeToString(body)))

        public suspend fun delete(id: String) {
            c.request("DELETE", "/merchant/sub-merchants/$id", null)
        }

        public class Users internal constructor(private val c: PayhubMerchantClient) {
            public suspend fun list(subId: String): List<SubUser> =
                c.json.decodeFromString(
                    ListSerializer(SubUser.serializer()),
                    c.request("GET", "/merchant/sub-merchants/$subId/users", null),
                )

            public suspend fun create(subId: String, body: SubUserCreate): SubUserCreated =
                c.json.decodeFromString(SubUserCreated.serializer(),
                    c.request("POST", "/merchant/sub-merchants/$subId/users",
                        c.json.encodeToString(body)))

            public suspend fun update(subId: String, uid: String, body: SubUserPatch): SubUser =
                c.json.decodeFromString(SubUser.serializer(),
                    c.request("PATCH", "/merchant/sub-merchants/$subId/users/$uid",
                        c.json.encodeToString(body)))

            public suspend fun disable(subId: String, uid: String) {
                c.request("DELETE", "/merchant/sub-merchants/$subId/users/$uid", null)
            }

            public suspend fun reissueInvite(subId: String, uid: String): ReissueInvite =
                c.json.decodeFromString(ReissueInvite.serializer(),
                    c.request("POST", "/merchant/sub-merchants/$subId/users/$uid/reissue-invite", null))

            /** Clear the sub-user's MFA — parent owner supplies their own current
             *  TOTP code as a guard against accidental clears. */
            public suspend fun clearMfa(subId: String, uid: String, code: String) {
                c.request("POST", "/merchant/sub-merchants/$subId/users/$uid/clear-mfa",
                    c.json.encodeToString(CodeBody(code)))
            }
        }

        public class ApiKeys internal constructor(private val c: PayhubMerchantClient) {
            /** List the (masked) API keys minted for one sub-merchant. */
            public suspend fun list(subId: String): List<MerchantApiKey> =
                c.json.decodeFromString(
                    ListSerializer(MerchantApiKey.serializer()),
                    c.request("GET", "/merchant/sub-merchants/$subId/api-keys", null),
                )

            /** Mint a new API key for one sub-merchant. The plaintext [secret] on
             *  the returned [MerchantApiKeyWithSecret] is shown **once** —
             *  persist or hand it to the operator immediately. The server stores
             *  only an argon2 hash. */
            public suspend fun create(
                subId: String,
                scopes: List<String>,
                allowedIps: List<String> = emptyList(),
                rateLimitTier: String = "standard",
            ): MerchantApiKeyWithSecret = c.json.decodeFromString(
                MerchantApiKeyWithSecret.serializer(),
                c.request("POST", "/merchant/sub-merchants/$subId/api-keys",
                    c.json.encodeToString(ApiKeyCreateBody(scopes, allowedIps, rateLimitTier))),
            )

            /** Revoke a sub-merchant API key. Returns the now-revoked
             *  (masked) key row. */
            public suspend fun revoke(subId: String, keyId: String): MerchantApiKey =
                c.json.decodeFromString(MerchantApiKey.serializer(),
                    c.request("POST", "/merchant/sub-merchants/$subId/api-keys/$keyId/revoke", null))
        }
    }

    // ----- settlements -----

    public class Settlements internal constructor(private val c: PayhubMerchantClient) {
        /** List settlement files (one per PSP per period). */
        public suspend fun list(
            psp: String? = null,
            limit: Int = 50,
            offset: Int = 0,
        ): List<SettlementFile> {
            val q = buildList {
                if (!psp.isNullOrBlank()) add("psp=$psp")
                add("limit=$limit")
                add("offset=$offset")
            }.joinToString("&")
            return c.json.decodeFromString(
                ListSerializer(SettlementFile.serializer()),
                c.request("GET", "/merchant/settlements?$q", null),
            )
        }

        /** Fetch a settlement file's header / counters. */
        public suspend fun get(id: String): SettlementFile =
            c.json.decodeFromString(
                SettlementFile.serializer(),
                c.request("GET", "/merchant/settlements/$id", null),
            )

        /** Paginated rows for a given settlement file. */
        public suspend fun listRows(
            fileId: String,
            statusFilter: String? = null,
            limit: Int = 100,
            offset: Int = 0,
        ): List<SettlementRow> {
            val q = buildList {
                if (!statusFilter.isNullOrBlank()) add("status=$statusFilter")
                add("limit=$limit")
                add("offset=$offset")
            }.joinToString("&")
            return c.json.decodeFromString(
                ListSerializer(SettlementRow.serializer()),
                c.request("GET", "/merchant/settlements/$fileId/rows?$q", null),
            )
        }
    }

    // ----- devices (FCM / APNs / Web Push registration for this merchant user) -----

    public class Devices internal constructor(private val c: PayhubMerchantClient) {
        /** List the signed-in user's active push device registrations. */
        public suspend fun list(): List<DeviceInfo> =
            c.json.decodeFromString(
                ListSerializer(DeviceInfo.serializer()),
                c.request("GET", "/merchant/devices", null),
            )

        /** Register (or refresh) a push token. `platform` is one of "android",
         *  "ios", or "web". For `web`, `keys` is required (Web Push VAPID
         *  p256dh + auth); for android/ios it must be null. */
        public suspend fun register(
            platform: String,
            token: String,
            keys: WebPushKeys? = null,
        ): DeviceInfo = c.json.decodeFromString(
            DeviceInfo.serializer(),
            c.request("POST", "/merchant/devices",
                c.json.encodeToString(RegisterDeviceBody(platform, token, keys))),
        )

        /** Convenience: register an Android FCM registration token. */
        public suspend fun registerAndroid(fcmToken: String): DeviceInfo =
            register("android", fcmToken, null)

        /** Convenience: register an iOS APNs device token (hex-encoded). */
        public suspend fun registerIos(apnsTokenHex: String): DeviceInfo =
            register("ios", apnsTokenHex, null)

        /** Unregister a push token (no-op if it doesn't exist). */
        public suspend fun unregister(token: String) {
            c.request("DELETE", "/merchant/devices",
                c.json.encodeToString(UnregisterDeviceBody(token)))
        }
    }

    private companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
