# Lunex deployment

## Render Blueprint

1. Extract the project and push its files to a repository you control.
2. In Render, create a new Blueprint from that repository and review the service settings and current price before deploying.
3. The included Blueprint uses a persistent disk mounted at `/var/data` and configures the expected Render origin. If your service receives a different hostname, update `LUNEX_ALLOWED_ORIGINS` to the exact HTTPS origin shown by Render.
4. When deployment is live, check `/api/health`, then open the same origin in two browsers and create separate accounts to test live community messages.

## Local development

Requires Node.js 20+ and npm:

```bash
npm install
npm run dev
```

Open `http://localhost:5173`. Vite serves the UI and proxies API/Socket.IO to port 3001.

## Docker

```bash
docker compose up --build -d
```

Open `http://localhost:3001`. The named volume keeps JSON data across container restarts.

## Before a public launch

The current release is not an anonymous or end-to-end encrypted messenger. Main community messages are plaintext on the server. Role-based access control, voice/video calls, offline delivery, decentralized routing and independent security review remain unimplemented. The JSON database is suitable only for a small prototype; use a transactional database and tested backups before operating a public service. Use HTTPS, set a strict `LUNEX_ALLOWED_ORIGINS` allowlist, and never advertise untraceability.
