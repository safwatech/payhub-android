// Public DTOs. Field names map snake_case JSON via @SerialName so the Kotlin
// surface stays idiomatic camelCase.

package ly.payhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public object Psp {
    public const val SADAD: String = "sadad"
    public const val MOAMALAT: String = "moamalat"
    public const val MOBICASH: String = "mobicash"
    public const val TLYNC: String = "tlync"
    public const val ADFALI: String = "adfali"
}

@Serializable
public data class CreatePaymentRequest(
    val psp: String,
    @SerialName("merchant_order_ref") val merchantOrderRef: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String? = null,
    val customer: Map<String, JsonElement>? = null,
    @SerialName("return_urls") val returnUrls: Map<String, String>? = null,
    val metadata: Map<String, JsonElement>? = null,
    @SerialName("hosted_checkout") val hostedCheckout: Boolean? = null,
)

@Serializable
public data class RefundRequest(
    @SerialName("amount_minor") val amountMinor: Long? = null,
    val reason: String? = null,
)

/**
 * Payment row. nextAction is decoded into the typed [NextAction] sum type
 * separately because @Serializable cannot model a discriminated union with
 * arbitrary key shapes per variant.
 */
public data class Payment(
    val id: String,
    val status: String,
    val psp: String,
    val pspRef: String?,
    val nextAction: NextAction?,
    val amountMinor: Long,
    val currency: String,
    val merchantOrderRef: String,
    val hostedCheckoutUrl: String?,
)

public typealias Refund = Payment

@Serializable
public data class Health(
    val status: String,
    val psps: List<String> = emptyList(),
)
