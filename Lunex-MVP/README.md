# Lunex Messenger MVP

Lunex is an early-stage web messenger with account registration, password hashing, persistent server-side chat history, communities, channels, direct messages and Socket.IO real-time messaging.

## Voice and video calling

The web client and native Android client now include one-to-one WebRTC call controls in direct messages. The server authenticates call signaling and checks that both participants belong to the relevant direct conversation. Media is exchanged peer-to-peer when network conditions allow. The browser client has an automated two-peer test that completes audio and video calls and verifies live remote audio/video tracks. The Android APK has a successful CI build, but a real call between two Android devices has not yet been verified.

- The web app must be served over HTTPS (or localhost during development) for browser camera/microphone access.
- The default ICE configuration includes a public STUN server. Some networks require a TURN relay. To enable one, configure `LUNEX_TURN_URLS` (comma-separated `turn:`/`turns:` URLs) and `LUNEX_TURN_SECRET` on the server. Lunex then issues short-lived TURN credentials using the TURN REST shared-secret mechanism; the TURN service itself must be operated/configured separately.
- This implementation has not yet been verified in a live call between two separate devices.
- Native Android calls require runtime microphone permission and camera permission for video. Device-to-device runtime verification is still outstanding.

## Phone-hosted node Internet relay

The Android local-node transport can use the authenticated Socket.IO server as an opaque Internet relay for room messages. Each message is encrypted on the phone with AES-GCM using a shared room invitation secret before it is sent; the relay forwards the ciphertext only to sockets subscribed to that secret's SHA-256 identifier. Direct LAN mesh remains the preferred path when peers are reachable locally. This relay is not a general-purpose TURN service and does not relay files, room history requests, or call signaling yet. It requires both devices to be signed in to a server that includes the relay handlers and to have the same valid room invite secret. The node is currently active only while the app is in the foreground.

This first relay path must still be verified on two physical Android devices on different networks before it can be described as production-ready. The relay server can observe connection metadata and message timing, although it does not receive plaintext message bodies in this path.

## Security status

Passwords are stored as scrypt hashes. The main app does **not** provide end-to-end encryption for ordinary messages: messages are stored by the server as plaintext and the server can read them. WebRTC media encryption is provided by the WebRTC transport, but it does not make stored chat messages end-to-end encrypted. Lunex is not a finished privacy/anonymity system; no independent security audit has been completed.

## Development

From this directory:

```sh
npm install
npm test
npm run build
npm run dev
```

The Node.js server defaults to port 3001. For deployment, configure `LUNEX_ALLOWED_ORIGINS` and persistent storage as described in `docs/DEPLOYMENT.md`.
