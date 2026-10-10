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
