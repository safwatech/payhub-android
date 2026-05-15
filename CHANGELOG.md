# Changelog

## [1.2.0](https://github.com/safwatech/payhub-android/compare/v1.0.0...v1.2.0) (2026-05-15)

### Features

* **client:** full merchant API surface with namespaces `auth`, `payLinks`, `reports`, `devices`, `payments`, `settlements`, `account`, `org`, `subMerchants` (incl. nested `users` + `apiKeys`).
* **errors:** typed `MerchantValidationError` and `MfaRequiredError`.
* **client:** refresh-mutex coalescing — concurrent 401-retries share a single token refresh.
* **payments:** `payments.list` accepts `subMerchantId` filter.

### Bug Fixes

* **client:** drop bespoke `refreshAccessToken` / `refreshTask` — actor coalesces refresh internally.

### Notes

This release replaces every `RawMerchantApi` shim that the consumer apps were carrying through the 1.0.x line — see the `payhub-merchant-android` app's 0.4.0 release for the corresponding migration.
