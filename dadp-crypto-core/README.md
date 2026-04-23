# DADP Crypto Core

`dadp-crypto-core` contains Spring-free, HTTP-free, DB-free crypto primitives shared by Engine and wrapper-side local crypto work.

## Version

- Initial module line: `5.8.15-SNAPSHOT`
- Java compatibility: Java 8

## Current Contract

- Supported providers: `HUB`, `DB`
- Supported algorithms: `A256GCM`, `AES-256-GCM`
- Ciphertext format: `hub:{policyUid}:{base64(iv(12 bytes) + ciphertext + tag(16 bytes))}`
- Partial encryption format: `[plainSegment]::ENC::[hub ciphertext]`

Unsupported providers or algorithms throw `UnsupportedCryptoMaterialException` so callers can fall back to remote Engine.
