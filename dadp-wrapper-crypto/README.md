# DADP Wrapper Crypto

`dadp-wrapper-crypto` is the separate local crypto module used by the JDBC wrapper only
when Hub `/refresh` returns `cryptoMode=local`.

It keeps the engine-compatible local crypto contract isolated and testable through
`dadp-crypto-core`, while `dadp-jdbc-wrapper` remains responsible for JDBC interception,
policy mapping, and local/remote routing.

## Contract

- Reuse the DADP 6.0 runtime execution-key resolve contract that Engine uses.
- Do not introduce a wrapper-specific key material format.
- Do not duplicate crypto algorithms in wrapper code; use `dadp-crypto-core`.
- Keep local crypto controlled by Hub `/refresh`; JDBC URL, environment variables,
  and system properties must not enable it.
- Fall back to remote Engine for unsupported providers or algorithms.

## Hub APIs

- `POST /hub/api/v1/runtime/execution-keys/resolve`

The wrapper integration must use the same response fields as Engine:

- `policyCode`
- `policyVersion`
- `keyAlias`
- `keyVersion`
- `providerType`
- `algorithm`
- `executionKeyBase64`
- `expiresAt`

## Current Scope

- HUB/DB-style AES-GCM key material.
- Engine-compatible payload format:
- `hub:{policyCode}:{base64(iv(12 bytes) + ciphertext + tag(16 bytes))}`
- Partial encryption format:
  - `[plainSegment]::ENC::[hub ciphertext]`
- Java 8 compatible implementation.

Unsupported algorithms and providers are deliberately rejected so the caller can fall back to remote Engine.
