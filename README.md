# PayHub Android SDK

Official PayHub SDK for Android. Kotlin coroutines, OkHttp under the hood,
discriminated `NextAction`, typed `PayhubException` hierarchy, and a
webhook verifier you can run inside a Worker / ViewModel.

## Install (JitPack — available today)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.safwatech:payhub-android:v1.0.5")
}
```

`minSdk = 24`. The SDK does not request any permissions other than
`INTERNET`.

> **Maven Central note** — `ly.payhub:payhub-android:1.0.0`
> coordinates are reserved and will be activated once the Sonatype
> Central Portal setup completes. JitPack is the recommended install
> today; the API surface is identical.

## Quickstart — Sadad OTP

```kotlin
val client = PayhubClient(BuildConfig.PAYHUB_API_KEY)

val payment = client.payments.create(
    CreatePaymentRequest(
        psp = Psp.SADAD,
        merchantOrderRef = "ord-42",
        amountMinor = 4500,
        currency = "LYD",
    )
)

when (val na = payment.nextAction) {
    is NextAction.OtpRequired -> showOtpEntry(masked = na.maskedDestination)
    is NextAction.Redirect    -> launchBrowser(na.url)
    is NextAction.QR          -> displayQr(na.qrPayload)
    is NextAction.Lightbox    -> webView.load(na.params)
    null                      -> finish()
}

val confirmed = client.payments.confirmOtp(payment.id, otpCode)
```

All client methods are `suspend` — call from a coroutine scope. Results are
typed; failures throw a subclass of `PayhubException`.

## Webhook verification

Run on the **server** that receives PayHub webhooks (Cloud Function, Cloud
Run, your backend) — not in the app process. The signed payload must be
the raw request body bytes; re-serializing breaks the HMAC.

```kotlin
val ev = WebhookEvent.verify(
    secret = secretBytes,
    body = rawBodyBytes,
    header = request.getHeader("Hub-Signature")!!,
)
// ev.type ∈ "payment.succeeded" | "payment.failed" | "payment.expired" | "payment.refunded"
```

## Errors

| Class | When |
| --- | --- |
| `AuthenticationException` | 401 |
| `PermissionException` | 403 |
| `NotFoundException` | 404 |
| `IdempotencyConflictException` | 409 |
| `ValidationException` | 422 |
| `RateLimitedException` | 429 (`retryAfter` carried) |
| `GatewayException` | 5xx + `gateway.<psp>.*` |
| `ServerException` | other 5xx |
| `TimeoutException` | request timed out |
| `ConnectionException` | network failure |
| `DecodeException` | malformed response |
| `MalformedHeaderException` | webhook header missing `t=`/`v1=` |
| `TimestampOutOfToleranceException` | webhook clock skew > 300 s |
| `InvalidSignatureException` | webhook HMAC mismatch |

## License

MIT — see `LICENSE`.
