// Typed exception hierarchy mirroring app/core/errors.py. Maps the server's
// {error: {code, message, details, request_id}} envelope plus HTTP status to
// a precise sealed PayhubException subclass.

package ly.payhub

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Base for every error this SDK raises. */
public sealed class PayhubException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Server returned a non-2xx with a parseable error envelope. */
public open class PayhubApiException internal constructor(
    message: String,
    public val code: String,
    public val httpStatus: Int,
    public val details: Map<String, Any?> = emptyMap(),
    public val requestId: String? = null,
) : PayhubException(if (requestId != null) "$message [request_id=$requestId]" else message)

public class AuthenticationException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

/** A 401 with `code = "mfa_required"` (or `hub.merchant.mfa_required`).
 *  The caller's auth state is unchanged; re-prompt for TOTP and retry the
 *  same request with the code supplied. Distinct from [AuthenticationException]
 *  so the UI can route this branch to the MFA prompt instead of the login
 *  screen. */
public class MfaRequiredError internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId) {
    public constructor(message: String) : this(message, "mfa_required", 401, emptyMap(), null)
}

/** A 4xx with an envelope carrying a stable [code] plus optional [params]
 *  for variable interpolation (e.g. `merchant.last_owner` + `["alice"]`).
 *  Apps key off [code] for localised rendering; [message] is the server's
 *  English fallback. */
public class MerchantValidationError internal constructor(
    message: String,
    code: String,
    httpStatus: Int,
    public val params: List<String>,
    details: Map<String, Any?>,
    requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId) {
    public constructor(code: String, params: List<String>, message: String) :
        this(message, code, 422, params, emptyMap(), null)
}

public class PermissionException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class NotFoundException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class ValidationException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class IdempotencyConflictException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class RateLimitedException internal constructor(
    message: String,
    code: String,
    httpStatus: Int,
    details: Map<String, Any?>,
    requestId: String?,
    public val retryAfter: Int?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class GatewayException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

public class ServerException internal constructor(
    message: String, code: String, httpStatus: Int, details: Map<String, Any?>, requestId: String?,
) : PayhubApiException(message, code, httpStatus, details, requestId)

/** Network / serialization problem — never reached the server cleanly. */
public sealed class PayhubTransportException(message: String, cause: Throwable? = null) : PayhubException(message, cause)
public class TimeoutException internal constructor(message: String, cause: Throwable? = null) : PayhubTransportException("payhub: timeout: $message", cause)
public class ConnectionException internal constructor(message: String, cause: Throwable? = null) : PayhubTransportException("payhub: connection: $message", cause)
public class DecodeException internal constructor(message: String, cause: Throwable? = null) : PayhubTransportException("payhub: decode: $message", cause)

internal object ErrorMapping {
    fun fromEnvelope(httpStatus: Int, body: JsonElement?, retryAfter: Int?): PayhubApiException {
        var code = "hub.unknown"
        var message = "HTTP $httpStatus"
        var details: Map<String, Any?> = emptyMap()
        var requestId: String? = null
        var params: List<String> = emptyList()

        val obj = body as? JsonObject
        val err = obj?.get("error") as? JsonObject
        if (err != null) {
            code = (err["code"] as? JsonPrimitive)?.contentOrNull ?: code
            message = (err["message"] as? JsonPrimitive)?.contentOrNull ?: message
            requestId = (err["request_id"] as? JsonPrimitive)?.contentOrNull
            val d = err["details"] as? JsonObject
            if (d != null) {
                details = d.mapValues { (_, v) -> v.toString() }
            }
            params = (err["params"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        } else if (obj != null) {
            // Flat envelope `{code, message, params?}` — the SDK-1.2 contract
            // for merchant-side endpoints. Keeps backwards-compat with the
            // wrapped `{error: {...}}` shape above.
            code = (obj["code"] as? JsonPrimitive)?.contentOrNull ?: code
            message = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: message
            requestId = (obj["request_id"] as? JsonPrimitive)?.contentOrNull
            params = (obj["params"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }

        // Sniff MFA-required first: a 401 with the catalogue code routes to
        // the dedicated MFA branch rather than the generic AuthenticationException.
        if (httpStatus == 401 && (code == "mfa_required" || code == "hub.merchant.mfa_required")) {
            return MfaRequiredError(message, code, httpStatus, details, requestId)
        }

        // A 4xx body that carried a `params` array, or a `merchant.*`-style
        // catalogue code, surfaces as MerchantValidationError so apps can
        // localise it via the catalogue. 500+ stays in the server bucket.
        if (httpStatus in 400..499 && (params.isNotEmpty() || code.startsWith("merchant.") ||
                code.startsWith("hub.merchant."))) {
            return MerchantValidationError(message, code, httpStatus, params, details, requestId)
        }

        return when (httpStatus) {
            401 -> AuthenticationException(message, code, httpStatus, details, requestId)
            403 -> PermissionException(message, code, httpStatus, details, requestId)
            404 -> NotFoundException(message, code, httpStatus, details, requestId)
            409 -> IdempotencyConflictException(message, code, httpStatus, details, requestId)
            422 -> ValidationException(message, code, httpStatus, details, requestId)
            429 -> RateLimitedException(message, code, httpStatus, details, requestId, retryAfter)
            else -> if (httpStatus in 500..599) {
                if (code.startsWith("gateway.")) {
                    GatewayException(message, code, httpStatus, details, requestId)
                } else {
                    ServerException(message, code, httpStatus, details, requestId)
                }
            } else {
                PayhubApiException(message, code, httpStatus, details, requestId)
            }
        }
    }

}
