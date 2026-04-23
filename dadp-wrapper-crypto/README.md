# DADP Wrapper Crypto

`dadp-wrapper-crypto` is a separate local crypto module for wrapper-side performance work.

It is intentionally not part of `dadp-jdbc-wrapper` runtime wiring yet. The first scope is to keep the engine-compatible local crypto contract isolated and testable through `dadp-crypto-core`.

## Contract

- Reuse the same Hub internal key APIs that Engine uses.
- Do not introduce a wrapper-specific key material format.
- Do not duplicate crypto algorithms in wrapper code; use `dadp-crypto-core`.
- Keep local crypto opt-in when integrated later.
- Fall back to remote Engine for unsupported providers or algorithms.

## Hub APIs

- `GET /hub/api/v1/keys/internal/{keyAlias}/{keyVersion}`
- `GET /hub/api/v1/keys/internal-data/{keyAlias}/{keyVersion}`

The wrapper integration must use the same response fields as Engine:

- `provider`
- `configJson`
- `accessInfo`
- `keyData`

## Current Scope

- HUB/DB-style AES-GCM key material.
- Engine-compatible payload format:
- `hub:{policyUid}:{base64(iv(12 bytes) + ciphertext + tag(16 bytes))}`
- Partial encryption format:
  - `[plainSegment]::ENC::[hub ciphertext]`
- Java 8 compatible implementation.

Unsupported algorithms and providers are deliberately rejected so the caller can fall back to remote Engine.
