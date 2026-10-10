# Lunex release status

## 1.0.0 — functional self-hosted release

Implemented in this release:
- Account registration and login using scrypt password hashing.
- Random bearer sessions; only a SHA-256 token hash is persisted, and sessions expire after 30 days.
- Authentication required for the main API and Socket.IO connection.
- The server uses the authenticated account identity for posted community messages instead of trusting the display name sent by a browser.
- Authentication attempt limiting, exact origin allowlist, message size limits, per-socket message rate limiting, known-channel checks, disabled Socket.IO compression, and basic security headers.
- Persistent community/channel messages and one-to-one private conversations in a JSON data file; responsive UI and local theme import/export.
- Separate experimental WebRTC direct chat.

## Not implemented — do not represent these as finished

- Main-app end-to-end encryption and cryptographically verified identities.
- Voice/video calls, attachments, reactions, offline delivery, community roles/membership, moderation tools.
- Onion routing/mixnet, distributed discovery/storage, or protection against IP/traffic correlation.
- Independent penetration test or cryptographic audit.

The direct-chat page uses manually exchanged WebRTC offer/answer data and a shared passphrase. It has no configured STUN/TURN service, no offline relay, and is not a reviewed secure-messaging protocol. A direct peer may learn network address information. Community and private chats are plaintext at the application layer and server operators can read them.

## Verification

`npm test` runs built-in Node tests without installing dependencies. `npm run build` requires npm dependencies and must be run before deployment. In this environment, dependency installation timed out, so a frontend production build has not been verified here.
