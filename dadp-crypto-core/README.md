# DADP Crypto Core

`dadp-crypto-core` contains Spring-free, HTTP-free, DB-free crypto primitives shared by Engine and wrapper-side local crypto work.

## Version

- Runtime module line: `6.0.0`
- Java compatibility: Java 8

## Current Contract

- Supported providers: `HUB`, `DB`
- Supported algorithms: `A256GCM`, `AES_256`, `AES-256-GCM`, `A256ECB`, `AES-256-ECB`
- Ciphertext format: `hub:{policyCode}:{base64(iv(12 bytes) + ciphertext + tag(16 bytes))}`
- Partial encryption format: `[plainSegment]::ENC::[hub ciphertext]`

Unsupported providers or algorithms throw `UnsupportedCryptoMaterialException` so callers can fall back to remote Engine.
