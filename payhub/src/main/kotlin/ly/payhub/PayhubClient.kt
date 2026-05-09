// Async-only PayHub client built on OkHttp + coroutines. Construct one per
// process and reuse — OkHttpClient holds a connection pool internally.

package ly.payhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

public class PayhubClient @JvmOverloads constructor(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    timeoutSeconds: Long = 30L,
    private val maxRetries: Int = 2,
    httpClient: OkHttpClient? = null,
    userAgentSuffix: String? = null,
) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://app.payhub.ly"
        public const val VERSION: String = "1.0.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    init {
        require(apiKey.startsWith("phk_")) { "PayHub API key must start with 'phk_'" }
    }

    private val apiKey = apiKey
    private val baseUrl = baseUrl.trimEnd('/')
    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val userAgent: String = run {
        // Build.VERSION isn't available in JVM unit tests (no Robolectric); fall back gracefully.
        val sdk = runCatching { android.os.Build.VERSION.SDK_INT.toString() }.getOrDefault("jvm")
        val base = "payhub-android/$VERSION (android/$sdk)"
        if (userAgentSuffix == null) base else "$base $userAgentSuffix"
    }

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    public val payments: Payments = Payments(this)
    public val health: HealthResource = HealthResource(this)

    internal suspend fun requestRaw(
        method: String,
        path: String,
        body: String?,
        idempotencyKey: String?,
        retriable: Boolean,
    ): String {
        val url = baseUrl + path
        val attempts = if (retriable) maxOf(1, maxRetries + 1) else 1
        var lastErr: PayhubException? = null
        for (attempt in 0 until attempts) {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
            if (idempotencyKey != null) reqBuilder.header("Idempotency-Key", idempotencyKey)

            val rb = body?.toRequestBody(JSON_MEDIA)
            when (method) {
                "GET" -> reqBuilder.get()
                "POST" -> reqBuilder.post(rb ?: byteArrayOf().toRequestBody(JSON_MEDIA))
                "DELETE" -> reqBuilder.delete(rb)
                else -> error("unsupported method: $method")
            }

            try {
                val resp = http.newCall(reqBuilder.build()).await()
                // try/finally instead of resp.use { } — `continue` from
                // inside an inline lambda is non-local and requires
                // Kotlin 2.1; we want this code to build on older
                // Kotlin compilers (e.g. JitPack's SDKMAN-installed
                // gradle bundle).
                try {
                    val status = resp.code
                    val raw = resp.body?.string().orEmpty()
                    if (status in 200..299) return raw
                    val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                    val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                    val apiErr = ErrorMapping.fromEnvelope(status, parsed, retryAfter)
                    if (retriable && (status >= 500 || status == 429) && attempt + 1 < attempts) {
                        delay(((retryAfter?.toLong() ?: 0L) * 1000L).coerceAtLeast(backoffMs(attempt)))
                        lastErr = apiErr
                        continue
                    }
                    throw apiErr
                } finally {
                    resp.close()
                }
            } catch (e: SocketTimeoutException) {
                lastErr = TimeoutException(e.message ?: "timed out", e)
            } catch (e: IOException) {
                lastErr = ConnectionException(e.message ?: "io error", e)
            }
            if (retriable && attempt + 1 < attempts) {
                delay(backoffMs(attempt))
                continue
            }
            throw lastErr ?: ConnectionException("payhub: unreachable retry loop")
        }
        throw lastErr ?: ConnectionException("payhub: unreachable retry loop")
    }

    private fun backoffMs(attempt: Int): Long {
        val base = 500L * (1L shl attempt)
        val jitter = 0.8 + Random.nextDouble() * 0.4
        return (base * jitter).toLong()
    }

    public class Payments internal constructor(private val c: PayhubClient) {
        public suspend fun create(
            request: CreatePaymentRequest,
            idempotencyKey: String? = null,
        ): Payment = withContext(Dispatchers.IO) {
            val key = idempotencyKey ?: UUID.randomUUID().toString()
            val raw = c.requestRaw("POST", "/v1/payments",
                c.json.encodeToString(request), key, retriable = true)
            decodePayment(c.json, raw)
        }

        public suspend fun confirmOtp(
            paymentId: String,
            code: String,
            idempotencyKey: String? = null,
        ): Payment = withContext(Dispatchers.IO) {
            val key = idempotencyKey ?: UUID.randomUUID().toString()
            val raw = c.requestRaw("POST", "/v1/payments/$paymentId/otp",
                c.json.encodeToString(mapOf("code" to code)), key, retriable = true)
            decodePayment(c.json, raw)
        }

        public suspend fun refund(
            paymentId: String,
            amountMinor: Long? = null,
            reason: String? = null,
            idempotencyKey: String? = null,
        ): Payment = withContext(Dispatchers.IO) {
            val key = idempotencyKey ?: UUID.randomUUID().toString()
            val raw = c.requestRaw("POST", "/v1/payments/$paymentId/refund",
                c.json.encodeToString(RefundRequest(amountMinor, reason)), key, retriable = true)
            decodePayment(c.json, raw)
        }

        public suspend fun retrieve(paymentId: String): Payment = withContext(Dispatchers.IO) {
            decodePayment(c.json, c.requestRaw("GET", "/v1/payments/$paymentId",
                null, null, retriable = true))
        }
    }

    public class HealthResource internal constructor(private val c: PayhubClient) {
        public suspend fun check(): Health = withContext(Dispatchers.IO) {
            val raw = c.requestRaw("GET", "/v1/health", null, null, retriable = true)
            c.json.decodeFromString(Health.serializer(), raw)
        }
    }
}

private fun decodePayment(json: Json, raw: String): Payment {
    val node = json.parseToJsonElement(raw) as? JsonObject
        ?: throw DecodeException("payment body is not a JSON object")

    fun str(key: String): String = (node[key] as? JsonPrimitive)?.contentOrNull ?: ""
    fun strOrNull(key: String): String? = (node[key] as? JsonPrimitive)?.contentOrNull
    fun longVal(key: String): Long =
        (node[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    val nextActionEl: JsonElement? = node["next_action"]
    val nextAction = NextAction.fromJson(nextActionEl)

    return Payment(
        id = str("id"),
        status = str("status"),
        psp = str("psp"),
        pspRef = strOrNull("psp_ref"),
        nextAction = nextAction,
        amountMinor = longVal("amount_minor"),
        currency = str("currency"),
        merchantOrderRef = str("merchant_order_ref"),
        hostedCheckoutUrl = strOrNull("hosted_checkout_url"),
    )
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
