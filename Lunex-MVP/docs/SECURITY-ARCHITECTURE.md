# Lunex: target security architecture

## Non-negotiable properties

1. The server/operator must never possess message-decryption keys.
2. End-to-end encryption must be implemented and reviewed independently of transport encryption.
3. Network discovery, relays, storage, and push notifications are separate components; no single component should need plaintext.
4. Minimize retained metadata and make unavoidable metadata explicit.
5. Security claims must match tested behavior. No implementation can promise that a user is "impossible to trace" under every threat model.

## Current main application

The main app now requires accounts. Passwords are stored as scrypt hashes, sessions use random bearer tokens whose SHA-256 hashes are persisted, and Socket.IO/API endpoints require a valid session. This reduces casual impersonation but does not replace a full authorization model. Community and private messages are still plaintext in the JSON data store and are readable by the server operator. Do not use the main app for confidential messages.

## Current experiment: `public/direct.html`

This is a limited browser-to-browser WebRTC data-channel experiment:

- No application server is used to relay chat messages.
- Signaling is manual: the users copy/paste an SDP offer and answer.
- ICE servers are disabled (`iceServers: []`), so there is no STUN/TURN dependency in this demo. This significantly limits connections across NATs/mobile networks and means it will often work only where direct host candidates are reachable.
- WebRTC uses DTLS for transport; the demo additionally encrypts each message using AES-256-GCM via Web Crypto. The key is derived locally from a shared passphrase with PBKDF2-SHA-256 (310,000 iterations).
- The shared passphrase is not transmitted by the application. Exchange it separately and use a long, random passphrase. A weak passphrase may be guessed offline if an attacker captures encrypted traffic.
- The code has not received an independent security audit. Do not use it for sensitive conversations.

## What this experiment does NOT provide

- Anonymous or onion/mixnet routing.
- Hiding IP addresses from the peer; direct P2P can expose network addresses in SDP/ICE candidates.
- Reliable connection across all NATs without STUN/TURN or another relay mechanism.
- Offline delivery, mailbox storage, multi-device sync, groups, calls, or attachments.
- Authentication of the peer identity, protection against a malicious modified client, or protection from a compromised endpoint.
- A guarantee that the existence of the network cannot be detected.

## Proposed next stages

1. Threat model and security protocol review before making anonymity claims.
2. Use a reviewed E2EE protocol/library rather than inventing a production protocol; verify identity keys out-of-band and support key changes safely.
3. Evaluate a mature anonymity transport (for example, a Tor-compatible/onion-routing design or a mixnet) with independent relays. Understand that relays and timing/traffic analysis remain risks.
4. Design offline delivery using encrypted mailboxes or distributed storage; define metadata retention, abuse controls, availability and deletion semantics.
5. Reproducible builds, dependency pinning, automated tests, external audit and security disclosure process.

A network cannot deliver messages while every possible sender, recipient, relay and storage node is offline. A decentralized design can remove a single operator, not the need for some online path for delivery.
