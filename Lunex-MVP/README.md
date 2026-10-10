# Lunex Messenger MVP

Lunex is an early-stage web messenger with account registration, password hashing, persistent server-side chat history, communities, channels, direct messages and Socket.IO real-time messaging.

## Voice and video calling

The web client now includes one-to-one WebRTC call controls in direct messages. The server authenticates call signaling and checks that both participants belong to the relevant direct conversation. Media is exchanged peer-to-peer when network conditions allow.

- The web app must be served over HTTPS (or localhost during development) for browser camera/microphone access.
- The current ICE configuration includes a public STUN server. Some networks require a TURN relay; set-up and credentials for a production TURN service are not bundled.
- This implementation has not yet been verified in a live call between two separate devices.
- The native Android client is not yet wired to this WebRTC call flow.

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
