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
  const child = spawn(process.execPath, ['server/index.js'], { cwd: root, env: { ...process.env, PORT: String(port), LUNEX_DATA_FILE: path.join(tempDir, 'db.json'), LUNEX_ALLOWED_ORIGINS: '', LUNEX_TURN_URLS: 'turn:turn.example.test:3478?transport=udp', LUNEX_TURN_SECRET: 'test-turn-secret' }, stdio: ['ignore', 'pipe', 'pipe'] });
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
  const callConfigResponse = await fetch(base + '/api/call-config', { headers: { authorization: `Bearer ${alice.token}` } });
  assert.equal(callConfigResponse.status, 200);
  const callConfig = await callConfigResponse.json();
  assert.equal(callConfig.turnConfigured, true);
  assert.equal(callConfig.iceServers.length, 2);
  assert.match(callConfig.iceServers[1].username, /^\d+:.*$/);
  assert.ok(callConfig.iceServers[1].credential);
  const dmRes = await fetch(base + '/api/dms', { method: 'POST', headers: { authorization: `Bearer ${alice.token}`, 'content-type': 'application/json' }, body: JSON.stringify({ username: 'call_bob' }) });
  assert.equal(dmRes.status, 200);
  const dm = await dmRes.json();
  const uploadQuery = new URLSearchParams({ scope: 'dm', id: dm.id, name: 'private-test.txt', type: 'text/plain' });
  const uploadResponse = await fetch(base + '/api/uploads?' + uploadQuery.toString(), { method: 'POST', headers: { authorization: `Bearer ${alice.token}`, 'content-type': 'application/octet-stream' }, body: Buffer.from('private attachment test') });
  assert.equal(uploadResponse.status, 201);
  const uploaded = await uploadResponse.json();
  const aliceDownload = await fetch(base + '/api/uploads/' + uploaded.id, { headers: { authorization: `Bearer ${alice.token}` } });
  assert.equal(aliceDownload.status, 200);
  assert.equal(await aliceDownload.text(), 'private attachment test');
  const bobDownload = await fetch(base + '/api/uploads/' + uploaded.id, { headers: { authorization: `Bearer ${bob.token}` } });
  assert.equal(bobDownload.status, 200);
  const malloryDownload = await fetch(base + '/api/uploads/' + uploaded.id, { headers: { authorization: `Bearer ${mallory.token}` } });
  assert.equal(malloryDownload.status, 403);
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


test('community membership, invite limits, roles and private channel permissions are enforced by the live server', { timeout: 30000 }, async t => {
  const { spawn } = await import('node:child_process');
  const net = await import('node:net');
  const { io: connectSocket } = await import('socket.io-client');
  const os = await import('node:os');
  const { once } = await import('node:events');
  const fsPromises = await import('node:fs/promises');
  const tempDir = await fsPromises.mkdtemp(path.join(os.tmpdir(), 'lunex-community-'));
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
  async function api(token, endpoint, method = 'GET', body) {
    const response = await fetch(base + endpoint, { method, headers: { authorization: 'Bearer ' + token, ...(body === undefined ? {} : { 'content-type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { response, data: await response.json().catch(() => ({})) };
  }
  function connect(token) {
    const socket = connectSocket(base, { auth: { token }, reconnection: false, timeout: 4000 });
    sockets.push(socket);
    return new Promise((resolve, reject) => { socket.once('connect', () => resolve(socket)); socket.once('connect_error', reject); });
  }
  t.after(async () => {
    for (const socket of sockets) socket.disconnect();
    child.kill('SIGTERM');
    if (child.exitCode === null) await Promise.race([once(child, 'exit'), new Promise(resolve => setTimeout(resolve, 1500))]);
    await fsPromises.rm(tempDir, { recursive: true, force: true });
  });
  await waitServer();
  const owner = await register('community_owner');
  const member = await register('community_member');
  const outsider = await register('community_outsider');
  const created = await api(owner.token, '/api/communities', 'POST', { name: 'Private Test Community', description: 'Integration test' });
  assert.equal(created.response.status, 201, JSON.stringify(created.data));
  const community = created.data;
  assert.equal(community.myRole, 'owner');
  assert.equal(community.channels.length, 1);
  assert.deepEqual(community.categories, []);
  assert.equal('invites' in community, false);
  const outsiderBootstrap = await api(outsider.token, '/api/bootstrap');
  assert.equal(outsiderBootstrap.response.status, 200);
  assert.equal(outsiderBootstrap.data.communities.some(item => item.id === community.id), false);
  const inviteResult = await api(owner.token, `/api/communities/${community.id}/invites`, 'POST', { maxUses: 1 });
  assert.equal(inviteResult.response.status, 201, JSON.stringify(inviteResult.data));
  const joined = await api(member.token, `/api/invites/${inviteResult.data.code}/join`, 'POST', {});
  assert.equal(joined.response.status, 200, JSON.stringify(joined.data));
  assert.equal(joined.data.community.myRole, 'member');
  const exhausted = await api(outsider.token, `/api/invites/${inviteResult.data.code}/join`, 'POST', {});
  assert.equal(exhausted.response.status, 410);
  const categoryCreate = await api(owner.token, `/api/communities/${community.id}/categories`, 'POST', { name: 'Staff' });
  assert.equal(categoryCreate.response.status, 201, JSON.stringify(categoryCreate.data));
  const channelCreate = await api(owner.token, `/api/communities/${community.id}/channels`, 'POST', { name: 'staff-only', private: true, categoryId: categoryCreate.data.id });
  assert.equal(channelCreate.response.status, 201, JSON.stringify(channelCreate.data));
  const privateChannel = channelCreate.data;
  assert.equal(privateChannel.categoryId, categoryCreate.data.id);
  const deniedHistory = await api(member.token, `/api/messages/${privateChannel.id}`);
  assert.equal(deniedHistory.response.status, 403);
  const forbiddenChannelCreate = await api(member.token, `/api/communities/${community.id}/channels`, 'POST', { name: 'unauthorized' });
  assert.equal(forbiddenChannelCreate.response.status, 403);
  const deniedMembers = await api(outsider.token, `/api/communities/${community.id}/members`);
  assert.equal(deniedMembers.response.status, 403);
  const memberSocket = await connect(member.token);
  const ownerSocket = await connect(owner.token);
  ownerSocket.emit('channel:join', privateChannel.id);
  await new Promise(resolve => setTimeout(resolve, 100));
  ownerSocket.emit('message:send', { channelId: privateChannel.id, text: 'moderation permission test' });
  await new Promise(resolve => setTimeout(resolve, 100));
  const ownerHistory = await api(owner.token, `/api/messages/${privateChannel.id}`);
  assert.equal(ownerHistory.data.length, 1);
  const messageId = ownerHistory.data[0].id;
  const cannotModerate = await api(member.token, `/api/communities/${community.id}/channels/${privateChannel.id}/messages/${messageId}`, 'DELETE');
  assert.equal(cannotModerate.response.status, 403);
  memberSocket.emit('channel:join', privateChannel.id);
  memberSocket.emit('message:send', { channelId: privateChannel.id, text: 'should not be stored' });
  await new Promise(resolve => setTimeout(resolve, 100));
  const beforePromotion = await api(owner.token, `/api/messages/${privateChannel.id}`);
  assert.equal(beforePromotion.response.status, 200);
  assert.equal(beforePromotion.data.length, 1);
  const promoted = await api(owner.token, `/api/communities/${community.id}/members/${member.user.id}`, 'PATCH', { roleId: 'admin' });
  assert.equal(promoted.response.status, 200, JSON.stringify(promoted.data));
  assert.equal(promoted.data.roleId, 'admin');
  const adminCannotEditRoles = await api(member.token, `/api/communities/${community.id}/roles/member`, 'PATCH', { permissions: ['view','send','manage_community'] });
  assert.equal(adminCannotEditRoles.response.status, 403);
  const nowAllowed = await api(member.token, `/api/messages/${privateChannel.id}`);
  assert.equal(nowAllowed.response.status, 200);
  const canModerate = await api(member.token, `/api/communities/${community.id}/channels/${privateChannel.id}/messages/${messageId}`, 'DELETE');
  assert.equal(canModerate.response.status, 200);
  const deletedHistory = await api(owner.token, `/api/messages/${privateChannel.id}`);
  assert.equal(deletedHistory.data.length, 0);
  memberSocket.emit('channel:join', privateChannel.id);
  await new Promise(resolve => setTimeout(resolve, 100));
  const demoted = await api(owner.token, `/api/communities/${community.id}/members/${member.user.id}`, 'PATCH', { roleId: 'member' });
  assert.equal(demoted.response.status, 200);
  let leakedPrivateMessage = false;
  const onPrivateMessage = () => { leakedPrivateMessage = true; };
  memberSocket.on('message:new', onPrivateMessage);
  ownerSocket.emit('message:send', { channelId: privateChannel.id, text: 'must not reach demoted member' });
  await new Promise(resolve => setTimeout(resolve, 150));
  memberSocket.off('message:new', onPrivateMessage);
  assert.equal(leakedPrivateMessage, false);
  const remainingHistory = await api(owner.token, `/api/messages/${privateChannel.id}`);
  assert.equal(remainingHistory.data.length, 1);
  const ownMessageDelete = await api(owner.token, `/api/communities/${community.id}/channels/${privateChannel.id}/messages/${remainingHistory.data[0].id}`, 'DELETE');
  assert.equal(ownMessageDelete.response.status, 200);
  const rePromoted = await api(owner.token, `/api/communities/${community.id}/members/${member.user.id}`, 'PATCH', { roleId: 'admin' });
  assert.equal(rePromoted.response.status, 200);
  const memberAdminChannel = await api(member.token, `/api/communities/${community.id}/channels`, 'POST', { name: 'admin-created' });
  assert.equal(memberAdminChannel.response.status, 201, JSON.stringify(memberAdminChannel.data));
  const categoryDelete = await api(owner.token, `/api/communities/${community.id}/categories/${categoryCreate.data.id}`, 'DELETE');
  assert.equal(categoryDelete.response.status, 200);
  const bootstrapAfterCategoryDelete = await api(owner.token, '/api/bootstrap');
  const freshCommunity = bootstrapAfterCategoryDelete.data.communities.find(item => item.id === community.id);
  assert.deepEqual(freshCommunity.categories, []);
  assert.equal(freshCommunity.channels.some(item => item.id === privateChannel.id), true);
  assert.equal(freshCommunity.channels.find(item => item.id === privateChannel.id).categoryId, null);
  const cannotPromotePeer = await api(member.token, `/api/communities/${community.id}/members/${owner.user.id}`, 'PATCH', { roleId: 'admin' });
  assert.equal(cannotPromotePeer.response.status, 403);
});


test('background REST message delivery is authenticated and idempotent', { timeout: 30000 }, async t => {
  const { spawn } = await import('node:child_process');
  const net = await import('node:net');
  const os = await import('node:os');
  const { once } = await import('node:events');
  const fsPromises = await import('node:fs/promises');
  const tempDir = await fsPromises.mkdtemp(path.join(os.tmpdir(), 'lunex-outbox-'));
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ['server/index.js'], {
    cwd: root,
    env: { ...process.env, PORT: String(port), LUNEX_DATA_FILE: path.join(tempDir, 'db.json'), LUNEX_ALLOWED_ORIGINS: '' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let logs = '';
  child.stdout.on('data', chunk => { logs += chunk.toString(); });
  child.stderr.on('data', chunk => { logs += chunk.toString(); });
  const base = `http://127.0.0.1:${port}`;
  async function waitServer() {
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      try { if ((await fetch(base + '/api/health')).ok) return; } catch {}
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('server did not start: ' + logs);
  }
  async function register(username) {
    const response = await fetch(base + '/api/auth/register', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, displayName: username, password: 'Lunex-test-password-2026' })
    });
    const body = await response.json();
    assert.equal(response.status, 201, JSON.stringify(body));
    return body;
  }
  t.after(async () => {
    child.kill('SIGTERM');
    await Promise.race([
      new Promise(resolve => child.once('exit', resolve)),
      new Promise(resolve => setTimeout(resolve, 1500))
    ]);
    await fsPromises.rm(tempDir, { recursive: true, force: true });
  });
  await waitServer();
  const alice = await register('sync_alice');
  const bob = await register('sync_bob');
  const mallory = await register('sync_mallory');
  const headers = token => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json' });

  const dmResponse = await fetch(base + '/api/dms', {
    method: 'POST', headers: headers(alice.token), body: JSON.stringify({ username: 'sync_bob' })
  });
  assert.equal(dmResponse.status, 200);
  const dm = await dmResponse.json();
  const dmPayload = { clientMessageId: 'background-dm-message-0001', text: 'queued while offline' };
  const dmSend = await fetch(base + `/api/dms/${dm.id}/messages`, {
    method: 'POST', headers: headers(alice.token), body: JSON.stringify(dmPayload)
  });
  assert.equal(dmSend.status, 201);
  assert.equal((await dmSend.json()).message.text, dmPayload.text);
  const dmRetry = await fetch(base + `/api/dms/${dm.id}/messages`, {
    method: 'POST', headers: headers(alice.token), body: JSON.stringify(dmPayload)
  });
  assert.equal(dmRetry.status, 200);
  assert.equal((await dmRetry.json()).duplicate, true);
  const dmHistory = await fetch(base + `/api/dms/${dm.id}/messages`, { headers: headers(alice.token) });
  assert.equal((await dmHistory.json()).filter(message => message.clientMessageId === dmPayload.clientMessageId).length, 1);

  const denied = await fetch(base + `/api/dms/${dm.id}/messages`, {
    method: 'POST', headers: headers(mallory.token), body: JSON.stringify({ clientMessageId: 'background-denied-0001', text: 'not mine' })
  });
  assert.equal(denied.status, 404);

  const channelPayload = { clientMessageId: 'background-channel-message-01', text: 'background channel delivery' };
  const channelSend = await fetch(base + '/api/messages/general', {
    method: 'POST', headers: headers(alice.token), body: JSON.stringify(channelPayload)
  });
  assert.equal(channelSend.status, 201);
  const channelRetry = await fetch(base + '/api/messages/general', {
    method: 'POST', headers: headers(alice.token), body: JSON.stringify(channelPayload)
  });
  assert.equal(channelRetry.status, 200);
  assert.equal((await channelRetry.json()).duplicate, true);
  const channelHistory = await fetch(base + '/api/messages/general', { headers: headers(alice.token) });
  assert.equal((await channelHistory.json()).filter(message => message.clientMessageId === channelPayload.clientMessageId).length, 1);
});


test('authenticated phone-node relay forwards opaque encrypted frames only to subscribed room hashes', { timeout: 20000 }, async t => {
  const { spawn } = await import('node:child_process');
  const net = await import('node:net');
  const { io: connectSocket } = await import('socket.io-client');
  const os = await import('node:os');
  const { once } = await import('node:events');
  const fsPromises = await import('node:fs/promises');
  const tempDir = await fsPromises.mkdtemp(path.join(os.tmpdir(), 'lunex-node-relay-'));
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ['server/index.js'], {
    cwd: root,
    env: { ...process.env, PORT: String(port), LUNEX_DATA_FILE: path.join(tempDir, 'db.json'), LUNEX_ALLOWED_ORIGINS: '' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
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
    const response = await fetch(base + '/api/auth/register', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, displayName: username, password: 'Lunex-test-password-2026' })
    });
    const body = await response.json();
    assert.equal(response.status, 201, JSON.stringify(body));
    return body;
  }
  async function connect(token) {
    const socket = connectSocket(base, { auth: { token }, reconnection: false, timeout: 4000 });
    sockets.push(socket);
    return new Promise((resolve, reject) => {
      socket.once('connect', () => resolve(socket));
      socket.once('connect_error', reject);
    });
  }
  function waitEvent(socket, event, timeoutMs = 2500) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { socket.off(event, onEvent); reject(new Error('Timed out waiting for ' + event)); }, timeoutMs);
      function onEvent(data) { clearTimeout(timer); resolve(data); }
      socket.once(event, onEvent);
    });
  }
  t.after(async () => {
    for (const socket of sockets) socket.disconnect();
    child.kill('SIGTERM');
    await new Promise(resolve => child.once('exit', resolve).setTimeout?.(1000));
    await fsPromises.rm(tempDir, { recursive: true, force: true });
  });
  await waitServer();
  const sender = await register('relay_sender');
  const receiver = await register('relay_receiver');
  const outsider = await register('relay_outsider');
  const a = await connect(sender.token);
  const b = await connect(receiver.token);
  const c = await connect(outsider.token);
  const tokenHash = 'a'.repeat(64);
  b.emit('node:join', { tokenHashes: [tokenHash] });
  await new Promise(resolve => setTimeout(resolve, 100));
  const delivered = waitEvent(b, 'node:message');
  const notDelivered = waitEvent(c, 'node:message', 450);
  const envelope = { encrypted: true, keyId: tokenHash, nonce: 'abcdefghijklmnop', ciphertext: 'ciphertext-only-test' };
  a.emit('node:message', { tokenHash, envelope });
  const packet = await delivered;
  assert.equal(packet.tokenHash, tokenHash);
  assert.deepEqual(packet.envelope, envelope);
  await assert.rejects(notDelivered, /Timed out waiting/);
  const malformedDelivery = waitEvent(b, 'node:message', 450);
  a.emit('node:message', { tokenHash: 'not-a-hash', envelope });
  await assert.rejects(malformedDelivery, /Timed out waiting/);
});
