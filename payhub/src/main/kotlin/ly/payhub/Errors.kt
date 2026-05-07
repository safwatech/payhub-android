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

        val err = (body as? JsonObject)?.get("error") as? JsonObject
        if (err != null) {
            code = (err["code"] as? JsonPrimitive)?.contentOrNull ?: code
            message = (err["message"] as? JsonPrimitive)?.contentOrNull ?: message
            requestId = (err["request_id"] as? JsonPrimitive)?.contentOrNull
            val d = err["details"] as? JsonObject
            if (d != null) {
                details = d.mapValues { (_, v) -> v.toString() }
            }
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
