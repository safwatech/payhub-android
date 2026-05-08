"""Generate the canonical webhook-signing test vector file.

This is the spec. Every SDK's webhook verifier loads /sdks/shared/test-vectors/
webhook-signing.json and runs every case. The Python `app/core/signing.py`
also runs against this file (see /sdks/shared/test-vectors/_run_python.py)
so the server cannot drift from the SDKs.

Re-run with:  python3 _generate.py
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
from pathlib import Path

REF_TS = 1730000000  # 2024-10-27, fixed so vectors are stable across regenerations.
SECRET_HEX = "5e8843f7a2c1bd9e0f4e7c5b1a9d2f3e6c8b4a1f7d9e2c3b5a8f1d4e7c9b2a3f"
SECRET = bytes.fromhex(SECRET_HEX)


def sign(secret: bytes, body: bytes, t: int) -> str:
    mac = hmac.new(secret, f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return f"t={t},v1={mac}"


def case(
    *,
    name: str,
    body: bytes,
    t: int,
    now: int,
    header: str | None,
    expect: str,
    tolerance_seconds: int = 300,
    description: str = "",
) -> dict:
    return {
        "name": name,
        "description": description,
        "secret_hex": SECRET_HEX,
        "body_b64": base64.b64encode(body).decode("ascii"),
        "timestamp": t,
        "now": now,
        "tolerance_seconds": tolerance_seconds,
        "header": header,
        "expect": expect,
    }


body_basic = b'{"id":"evt_1","type":"payment.succeeded","payment_id":"pay_1"}'
body_unicode = '{"id":"evt_2","note":"Libya — دينار"}'.encode("utf-8")
body_empty = b""
# Pay-link lifecycle event. The SDK's WebhookEvent.verify must not reject
# unknown event_type values — they decode into the same Event shape so
# servers can add new types without bumping the SDK. This vector locks
# that forward-compatibility property to the wire format.
body_paylink = b'{"id":"evt_3","type":"paylink.paid","payment_id":"pay_2","prev_status":"requires_action","new_status":"succeeded","payload":{"pay_link_id":"pl_1","short_token":"abc123def456","amount_minor":4500,"currency":"LYD"}}'

valid_header = sign(SECRET, body_basic, REF_TS)
unicode_header = sign(SECRET, body_unicode, REF_TS)
empty_header = sign(SECRET, body_empty, REF_TS)
paylink_header = sign(SECRET, body_paylink, REF_TS)

# A "wrong v1" — bit-flip the last hex char of a real header.
broken_v1_header = valid_header[:-1] + ("0" if valid_header[-1] != "0" else "1")

# A header older than the tolerance window.
expired_t = REF_TS - 600
expired_header = sign(SECRET, body_basic, expired_t)

# A header in the future beyond tolerance.
future_t = REF_TS + 600
future_header = sign(SECRET, body_basic, future_t)

# Header with extra unknown comma-separated keys — the verifier MUST ignore
# them (forward compatibility for new schemes).
extras_header = valid_header + ",extra=foo,unused=bar"

cases = [
    case(
        name="valid_v1",
        body=body_basic,
        t=REF_TS,
        now=REF_TS,
        header=valid_header,
        expect="ok",
        description="Vanilla success: HMAC matches, timestamp inside tolerance window.",
    ),
    case(
        name="expired_outside_tolerance",
        body=body_basic,
        t=expired_t,
        now=REF_TS,
        header=expired_header,
        expect="TimestampOutOfTolerance",
        description="Timestamp 600 s old; default tolerance is 300 s; reject.",
    ),
    case(
        name="future_outside_tolerance",
        body=body_basic,
        t=future_t,
        now=REF_TS,
        header=future_header,
        expect="TimestampOutOfTolerance",
        description="Timestamp 600 s in the future; reject.",
    ),
    case(
        name="invalid_signature",
        body=body_basic,
        t=REF_TS,
        now=REF_TS,
        header=broken_v1_header,
        expect="InvalidSignature",
        description="Last hex char of v1 is flipped; HMAC mismatch.",
    ),
    case(
        name="missing_t",
        body=body_basic,
        t=REF_TS,
        now=REF_TS,
        header="v1=" + valid_header.split("v1=")[1],
        expect="MalformedHeader",
        description="Header has only v1=, no t=.",
    ),
    case(
        name="missing_v1",
        body=body_basic,
        t=REF_TS,
        now=REF_TS,
        header=f"t={REF_TS}",
        expect="MalformedHeader",
        description="Header has only t=, no v1=.",
    ),
    case(
        name="extra_unknown_keys",
        body=body_basic,
        t=REF_TS,
        now=REF_TS,
        header=extras_header,
        expect="ok",
        description="Header carries extra unknown comma-separated keys; verifier must ignore.",
    ),
    case(
        name="unicode_body",
        body=body_unicode,
        t=REF_TS,
        now=REF_TS,
        header=unicode_header,
        expect="ok",
        description="UTF-8 body bytes (Arabic) round-trip; SDKs must hash raw bytes, not re-encoded strings.",
    ),
    case(
        name="empty_body",
        body=body_empty,
        t=REF_TS,
        now=REF_TS,
        header=empty_header,
        expect="ok",
        description="Zero-length body still signs and verifies.",
    ),
    case(
        name="paylink_paid_body",
        body=body_paylink,
        t=REF_TS,
        now=REF_TS,
        header=paylink_header,
        expect="ok",
        description=(
            "Pay-link event body (`paylink.paid`). Locks the forward-"
            "compatibility property: SDK verifiers must accept unknown "
            "event_type strings and produce a typed Event for them, not "
            "reject the delivery."
        ),
    ),
]

doc = {
    "$schema": "https://payhub.ly/schemas/webhook-signing-vectors-v1.json",
    "version": 1,
    "algorithm": "HMAC-SHA256",
    "header_name": "Hub-Signature",
    "header_format": "t=<unix_seconds>,v1=<hmac_sha256_hex>",
    "signed_bytes_template": "f\"{t}.\".encode() + raw_body_bytes",
    "default_tolerance_seconds": 300,
    "notes": [
        "secret_hex is the hex-encoded raw secret bytes the merchant configured.",
        "body_b64 is base64-encoded raw request body bytes; SDKs decode and hash these bytes verbatim.",
        "now is the wall-clock the verifier should treat as 'now' for the tolerance check.",
        "expect is one of: ok, TimestampOutOfTolerance, InvalidSignature, MalformedHeader.",
        "Forward compatibility: headers carrying additional comma-separated key=value pairs MUST verify if t and v1 are valid.",
    ],
    "cases": cases,
}

out = Path(__file__).with_name("webhook-signing.json")
out.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"wrote {out} with {len(cases)} cases")
