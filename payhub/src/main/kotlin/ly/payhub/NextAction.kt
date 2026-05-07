// Discriminated NextAction returned in payment.nextAction.
//
// Sealed class hierarchy so callers can pattern-match exhaustively.

package ly.payhub

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Server-emitted next-action variants. Match exhaustively:
 *
 * ```kotlin
 * when (val na = payment.nextAction) {
 *     is NextAction.OtpRequired -> displayOtpScreen(na.maskedDestination)
 *     is NextAction.Redirect    -> launchBrowser(na.url)
 *     is NextAction.QR          -> displayQr(na.qrPayload)
 *     is NextAction.Lightbox    -> launchMoamalat(na.params)
 *     null                      -> finishWithoutInteraction()
 * }
 * ```
 */
public sealed class NextAction {
    public data class OtpRequired(
        val pspRef: String,
        val maskedDestination: String,
        val expiresAt: String? = null,
    ) : NextAction()

    public data class Redirect(
        val url: String,
        val method: String = "GET",
        val fields: Map<String, String> = emptyMap(),
        val expiresAt: String? = null,
    ) : NextAction()

    public data class QR(
        val reference: String,
        val qrPayload: String,
        val expiresAt: String? = null,
    ) : NextAction()

    public data class Lightbox(
        val params: Map<String, String>,
        val scriptUrl: String? = null,
    ) : NextAction()

    public companion object {
        public fun fromJson(node: JsonElement?): NextAction? {
            if (node == null || node is JsonNull) return null
            require(node is JsonObject) { "next_action must be an object or null" }

            val type = node["type"]?.asStringOrNull()
            return when (type) {
                "otp_required" -> OtpRequired(
                    pspRef = node["psp_ref"].asStringOrEmpty(),
                    maskedDestination = node["masked_destination"].asStringOrEmpty(),
                    expiresAt = node["expires_at"]?.asStringOrNull(),
                )
                "redirect" -> Redirect(
                    url = node["url"].asStringOrEmpty(),
                    method = (node["method"]?.asStringOrNull() ?: "GET").uppercase(),
                    fields = (node["fields"] as? JsonObject)?.toStringMap() ?: emptyMap(),
                    expiresAt = node["expires_at"]?.asStringOrNull(),
                )
                "qr" -> QR(
                    reference = node["reference"].asStringOrEmpty(),
                    qrPayload = node["qr_payload"].asStringOrEmpty(),
                    expiresAt = node["expires_at"]?.asStringOrNull(),
                )
                "lightbox" -> {
                    val params = (node["params"] as? JsonObject)?.toStringMap() ?: emptyMap()
                    Lightbox(params, params["lightbox_js_url"])
                }
                else -> throw IllegalArgumentException("unknown next_action.type: $type")
            }
        }

        private fun JsonElement?.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.contentOrNull

        private fun JsonElement?.asStringOrEmpty(): String =
            (this as? JsonPrimitive)?.contentOrNull ?: ""

        private fun JsonObject.toStringMap(): Map<String, String> =
            this.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
    }
}
