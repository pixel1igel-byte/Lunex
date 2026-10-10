import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
test('direct chat page and script are included', () => {
  assert.ok(fs.existsSync(path.join(root, 'public/direct.html')));
  assert.ok(fs.existsSync(path.join(root, 'public/direct.js')));
  const html = fs.readFileSync(path.join(root, 'public/direct.html'), 'utf8');
  const js = fs.readFileSync(path.join(root, 'public/direct.js'), 'utf8');
  assert.match(html, /LUNEX DIRECT/);
  assert.match(js, /RTCPeerConnection/);
  assert.match(js, /AES-GCM/);
  assert.match(js, /iterations:310000/);
  assert.match(js, /additionalData:aad/);
  assert.match(js, /receiveSeq\+1/);
  assert.match(js, /type:'hello'/);
  assert.match(js, /iceServers:\s*\[\]/);
});
test('security documentation clearly states prototype limitations', () => {
  const doc = fs.readFileSync(path.join(root, 'docs/SECURITY-ARCHITECTURE.md'), 'utf8');
  assert.match(doc, /does NOT provide/i);
  assert.match(doc, /IP addresses/i);
  assert.match(doc, /independent security audit/i);
});

test('server origin policy is exact and rejects arbitrary web origins', async () => {
  const { parseAllowedOrigins, isAllowedOrigin } = await import('../server/security.js');
  const allow = parseAllowedOrigins('https://lunex.example,https://test.example');
  assert.equal(isAllowedOrigin('https://lunex.example', allow), true);
  assert.equal(isAllowedOrigin('https://evil.example', allow), false);
  assert.equal(isAllowedOrigin(undefined, allow), true);
});

test('rate limiter rejects bursts and recovers after its window', async () => {
  const { createRateLimiter } = await import('../server/security.js');
  let time = 1000;
  const allow = createRateLimiter({ limit: 2, windowMs: 500, now: () => time });
  assert.equal(allow(), true);
  assert.equal(allow(), true);
  assert.equal(allow(), false);
  time = 1600;
  assert.equal(allow(), true);
});

test('only existing channels may be joined or messaged', async () => {
  const { isValidChannelId } = await import('../server/security.js');
  const communities = [{ channels: [{ id: 'general' }] }];
  assert.equal(isValidChannelId('general', communities), true);
  assert.equal(isValidChannelId('admin', communities), false);
  assert.equal(isValidChannelId('x'.repeat(121), communities), false);
});

test('server does not enable wildcard origin reflection or websocket compression', () => {
  const source = fs.readFileSync(path.join(root, 'server/index.js'), 'utf8');
  assert.match(source, /perMessageDeflate:\s*false/);
  assert.match(source, /isAllowedOrigin\(req\.headers\.origin, allowedOrigins\)/);
  assert.doesNotMatch(source, /cors:\s*\{\s*origin:\s*true/);
});

test('main application requires authenticated accounts rather than local display-name login', () => {
  const server = fs.readFileSync(path.join(root, 'server/index.js'), 'utf8');
  const app = fs.readFileSync(path.join(root, 'src/main.jsx'), 'utf8');
  assert.match(server, /api\/auth\/register/);
  assert.match(server, /api\/auth\/login/);
  assert.match(server, /app\.post\('\/api\/dms'/);
  assert.match(server, /socket\.on\('dm:send'/);
  assert.match(server, /d\.userIds\.includes\(req\.user\.id\)/);
  assert.match(server, /promisify\(scryptCb\)/);
  assert.match(server, /tokenHash: tokenHash\(token\)/);
  assert.match(server, /io\.use\(\(socket, next\)/);
  assert.match(server, /user: socket\.data\.user\.displayName/);
  assert.match(app, /lunex-token/);
  assert.match(app, /api\/auth\/\$\{authMode\}/);
  assert.doesNotMatch(app, /localStorage\.setItem\('lunex-user'/);
});

test('release documentation distinguishes implemented features from missing privacy guarantees', () => {
  const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
  const status = fs.readFileSync(path.join(root, 'docs/STATUS.md'), 'utf8');
  assert.match(readme, /passwords are stored as scrypt hashes/i);
  assert.match(readme, /not a finished privacy\/anonymity system/i);
  assert.match(status, /Main-app end-to-end encryption/i);
  assert.match(status, /not implemented/i);
});

test('WebRTC call signaling is authenticated and restricted to DM participants', () => {
  const server = fs.readFileSync(path.join(root, 'server/index.js'), 'utf8');
  const app = fs.readFileSync(path.join(root, 'src/main.jsx'), 'utf8');
  assert.match(server, /socket\.on\('call:invite'/);
  assert.match(server, /socket\.on\('call:accept'/);
  assert.match(server, /socket\.on\('call:signal'/);
  assert.match(server, /socket\.on\('call:reject'/);
  assert.match(server, /socket\.on\('call:end'/);
  assert.match(server, /conversation\.userIds\.includes\(targetId\)/);
  assert.match(server, /callForUser\(payload\.callId, socket\.data\.user\.id\)/);
  assert.match(server, /camera=\(self\), microphone=\(self\)/);
  assert.match(app, /new RTCPeerConnection/);
  assert.match(app, /getUserMedia/);
  assert.match(app, /call:incoming/);
  assert.match(app, /call:accepted/);
  assert.match(app, /call:signal/);
});


test('live server relays authenticated WebRTC signaling only to direct-chat participants', { timeout: 30000 }, async t => {
  const { spawn } = await import('node:child_process');
  const net = await import('node:net');
  const { io: connectSocket } = await import('socket.io-client');
  const tmp = await import('node:os');
  const { once } = await import('node:events');
  const dir = await import('node:fs/promises');
  const tempDir = await dir.mkdtemp(path.join(tmp.tmpdir(), 'lunex-calls-'));
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ['server/index.js'], { cwd: root, env: { ...process.env, PORT: String(port), LUNEX_DATA_FILE: path.join(tempDir, 'db.json'), LUNEX_ALLOWED_ORIGINS: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let logs = '';
  child.stdout.on('data', chunk => { logs += chunk.toString(); });
  child.stderr.on('data', chunk => { logs += chunk.toString(); });
  const base = `http://127.0.0.1:${port}`;
  const sockets = [];
  async function waitServer() {
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      try { if ((await fetch(base + '/api/health')).ok) return; } catch {}
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('server did not start: ' + logs);
  }
  async function register(username) {
    const response = await fetch(base + '/api/auth/register', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username, displayName: username, password: 'Lunex-test-password-2026' }) });
    const body = await response.json();
    assert.equal(response.status, 201, JSON.stringify(body));
    return body;
  }
  function waitEvent(socket, event) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { socket.off(event, onEvent); reject(new Error('Timed out waiting for ' + event)); }, 4000);
      function onEvent(data) { clearTimeout(timer); resolve(data); }
      socket.once(event, onEvent);
    });
  }
  function makeSocket(token) {
    const socket = connectSocket(base, { auth: { token }, reconnection: false, timeout: 4000 });
    sockets.push(socket);
    return new Promise((resolve, reject) => {
      socket.once('connect', () => resolve(socket));
      socket.once('connect_error', reject);
    });
  }
  t.after(async () => {
    for (const socket of sockets) socket.disconnect();
    child.kill('SIGTERM');
    await new Promise(resolve => child.once('exit', resolve).setTimeout?.(1000));
    await dir.rm(tempDir, { recursive: true, force: true });
  });
  await waitServer();
  const alice = await register('call_alice');
  const bob = await register('call_bob');
  const mallory = await register('call_mallory');
  const dmRes = await fetch(base + '/api/dms', { method: 'POST', headers: { authorization: `Bearer ${alice.token}`, 'content-type': 'application/json' }, body: JSON.stringify({ username: 'call_bob' }) });
  assert.equal(dmRes.status, 200);
  const dm = await dmRes.json();
  const a = await makeSocket(alice.token);
  const b = await makeSocket(bob.token);
  const m = await makeSocket(mallory.token);
  const incomingPromise = waitEvent(b, 'call:incoming');
  a.emit('call:invite', { callId: 'test-call-1', conversationId: dm.id, toUserId: bob.user.id, mode: 'audio', offer: { type: 'offer', sdp: 'test-sdp' } });
  const incoming = await incomingPromise;
  assert.equal(incoming.caller.id, alice.user.id);
  assert.equal(incoming.offer.sdp, 'test-sdp');
  const acceptedPromise = waitEvent(a, 'call:accepted');
  b.emit('call:accept', { callId: incoming.callId, conversationId: dm.id, answer: { type: 'answer', sdp: 'answer-sdp' } });
  assert.equal((await acceptedPromise).answer.sdp, 'answer-sdp');
  const signalPromise = waitEvent(a, 'call:signal');
  b.emit('call:signal', { callId: incoming.callId, conversationId: dm.id, signal: { candidate: { candidate: 'candidate:unit-test' } } });
  assert.equal((await signalPromise).signal.candidate.candidate, 'candidate:unit-test');
  const endedPromise = waitEvent(b, 'call:ended');
  a.emit('call:end', { callId: incoming.callId, conversationId: dm.id });
  assert.equal((await endedPromise).callId, 'test-call-1');
  const forgedPromise = waitEvent(m, 'call:incoming');
  a.emit('call:invite', { callId: 'forged-call', conversationId: dm.id, toUserId: mallory.user.id, mode: 'audio', offer: { type: 'offer', sdp: 'forged' } });
  await assert.rejects(forgedPromise, /Timed out waiting/);
});
