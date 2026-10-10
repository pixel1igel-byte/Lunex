# Lunex MVP 0.7.0

## Implemented in this iteration

- WebSocket/Socket.IO connections now use an explicit origin allowlist (`LUNEX_ALLOWED_ORIGINS`) rather than reflecting every origin.
- Socket.IO payload size is capped at 16 KiB and per-message compression is disabled.
- Server applies basic per-socket burst and sustained message limits.
- Channel joins and message sends validate the channel against the known channel catalog.
- HTTP JSON request bodies are limited to 32 KiB; basic security response headers are set.
- Direct experimental chat now negotiates a random per-session salt and session ID, derives its AES-256-GCM key with PBKDF2-SHA-256 (310,000 iterations), authenticates session ID + sequence number as AEAD additional data, and rejects unexpected/replayed sequence numbers.
- Added tests for origin validation, rate limiting, channel allow-listing and direct-chat protocol invariants.

## Important limitations

This remains a development prototype, not a production-secure messenger. Main community chat messages are still plaintext in the server's data store and are NOT end-to-end encrypted. Direct chat uses a manually shared passphrase and manual SDP exchange; it has no independently authenticated identity/key verification, no automatic signaling, no relay fallback, no onion/mixnet routing, no offline delivery, no account/authorization model and no independent cryptographic audit. The origin allowlist is a browser-origin control, not authentication. Per-socket limits can be bypassed by opening more connections and are not a replacement for edge-level abuse controls.
