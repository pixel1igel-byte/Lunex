import express from 'express';
import http from 'node:http';
import { Server } from 'socket.io';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, scrypt as scryptCb, timingSafeEqual, createHash, createHmac } from 'node:crypto';
import { promisify } from 'node:util';
import { createRateLimiter, isAllowedOrigin, isValidChannelId, parseAllowedOrigins } from './security.js';

const scrypt = promisify(scryptCb);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const server = http.createServer(app);
const allowedOrigins = parseAllowedOrigins(process.env.LUNEX_ALLOWED_ORIGINS || '');
const io = new Server(server, {
  cors: { origin: [...allowedOrigins], credentials: false },
  allowRequest: (req, callback) => callback(null, isAllowedOrigin(req.headers.origin, allowedOrigins)),
  maxHttpBufferSize: 16 * 1024, perMessageDeflate: false, pingInterval: 25000, pingTimeout: 20000
});
const PORT = Number(process.env.PORT || 3001);
const MAX_MESSAGE_LENGTH = 4000;
const MAX_HISTORY = 500;
const SESSION_DAYS = 30;
const DATA = process.env.LUNEX_DATA_FILE || path.join(__dirname, 'data.json');
const seed = {
  users: [], sessions: [], uploads: [], communities: [
    { id: 'void', name: 'The Void', description: 'Игры, аниме и общение', channels: [
      { id: 'general', name: 'общение', type: 'text' }, { id: 'memes', name: 'мемы', type: 'text' },
      { id: 'anime', name: 'аниме', type: 'text' }, { id: 'voice', name: 'Общий голосовой', type: 'voice' }
    ]},
    { id: 'friends', name: 'Друзья', description: 'Наше личное пространство', channels: [
      { id: 'hangout', name: 'болталка', type: 'text' }, { id: 'plans', name: 'планы', type: 'text' }
    ]}
  ], messages: { general: [
    { id: 'welcome1', user: 'Lunex', text: 'Добро пожаловать в Lunex 🌙', time: new Date().toISOString(), system: true },
    { id: 'welcome2', user: 'Lunex', text: 'Создай аккаунт и начни общаться. Настрой внешний вид через палитру.', time: new Date().toISOString(), system: true }
  ] }, dms: []
};
function loadDb() {
  try {
    const parsed = JSON.parse(fs.readFileSync(DATA, 'utf8'));
    return { ...structuredClone(seed), ...parsed, users: parsed.users || [], sessions: parsed.sessions || [], uploads: parsed.uploads || [], dms: parsed.dms || [] };
  } catch {
    fs.mkdirSync(path.dirname(DATA), { recursive: true });
    fs.writeFileSync(DATA, JSON.stringify(seed, null, 2));
    return structuredClone(seed);
  }
}
let db = loadDb();
function saveDb() {
  fs.mkdirSync(path.dirname(DATA), { recursive: true });
  const tmp = `${DATA}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, DATA);
}
function publicUser(user) { return { id: user.id, username: user.username, displayName: user.displayName, createdAt: user.createdAt }; }
function tokenHash(token) { return createHash('sha256').update(token).digest('hex'); }
function bearer(req) { const h = req.headers.authorization || ''; return h.startsWith('Bearer ') ? h.slice(7) : ''; }
function getSession(token) {
  if (!token || token.length > 256) return null;
  const hash = tokenHash(token), now = Date.now();
  const session = db.sessions.find(s => s.tokenHash === hash && s.expiresAt > now);
  if (!session) return null;
  const user = db.users.find(u => u.id === session.userId);
  return user ? { session, user } : null;
}
function authHttp(req, res, next) {
  const auth = getSession(bearer(req));
  if (!auth) return res.status(401).json({ error: 'Сессия истекла. Войди снова.' });
  req.user = auth.user; req.session = auth.session; next();
}
function cleanUsername(value) { return String(value || '').trim().toLowerCase(); }
function validUsername(value) { return /^[a-z0-9_]{3,24}$/.test(value); }
function validPassword(value) { return typeof value === 'string' && value.length >= 10 && value.length <= 128; }
async function makePassword(password) {
  const salt = randomBytes(16);
  const derived = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return { salt: salt.toString('hex'), hash: derived.toString('hex') };
}
async function checkPassword(password, record) {
  try {
    const derived = await scrypt(password, Buffer.from(record.salt, 'hex'), 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
    const expected = Buffer.from(record.hash, 'hex');
    return expected.length === derived.length && timingSafeEqual(expected, derived);
  } catch { return false; }
}
function createSession(user) {
  const token = randomBytes(32).toString('base64url');
  const session = { tokenHash: tokenHash(token), userId: user.id, createdAt: Date.now(), expiresAt: Date.now() + SESSION_DAYS * 86400000 };
  db.sessions = db.sessions.filter(s => s.expiresAt > Date.now());
  db.sessions.push(session); saveDb();
  return token;
}
app.disable('x-powered-by');
app.use(express.json({ limit: '32kb', strict: true }));
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Frame-Options', 'DENY'); res.setHeader('Permissions-Policy', 'camera=(self), microphone=(self), geolocation=()');
  next();
});
const authLimiters = new Map();
function allowAuthAttempt(req) {
  const key = String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown').split(',').at(-1).trim();
  if (!authLimiters.has(key)) authLimiters.set(key, createRateLimiter({ limit: 12, windowMs: 60_000 }));
  if (authLimiters.size > 5000) authLimiters.delete(authLimiters.keys().next().value);
  return authLimiters.get(key)();
}
app.get('/api/call-config', authHttp, (req, res) => {
  const iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
  const turnUrls = String(process.env.LUNEX_TURN_URLS || '').split(',').map(value => value.trim()).filter(Boolean);
  const turnSecret = process.env.LUNEX_TURN_SECRET || '';
  if (turnUrls.length && turnSecret) {
    const expiry = Math.floor(Date.now() / 1000) + 3600;
    const username = `${expiry}:${req.user.id}`;
    const credential = createHmac('sha1', turnSecret).update(username).digest('base64');
    iceServers.push({ urls: turnUrls, username, credential });
  }
  res.setHeader('Cache-Control', 'no-store');
  res.json({ iceServers, turnConfigured: iceServers.length > 1 });
});
app.get('/api/health', (_req, res) => res.json({ ok: true, app: 'Lunex', version: '1.0.0' }));
app.post('/api/auth/register', async (req, res) => {
  if (!allowAuthAttempt(req)) return res.status(429).json({ error: 'Слишком много попыток. Подожди минуту.' });
  const username = cleanUsername(req.body?.username);
  const displayName = String(req.body?.displayName || '').trim().slice(0, 32);
  const password = req.body?.password;
  if (!validUsername(username)) return res.status(400).json({ error: 'Логин: 3–24 символа, только a–z, цифры и _.' });
  if (!displayName) return res.status(400).json({ error: 'Укажи отображаемое имя.' });
  if (!validPassword(password)) return res.status(400).json({ error: 'Пароль должен содержать от 10 до 128 символов.' });
  if (db.users.some(u => u.username === username)) return res.status(409).json({ error: 'Этот логин уже занят.' });
  const secret = await makePassword(password);
  const user = { id: randomBytes(16).toString('hex'), username, displayName, passwordSalt: secret.salt, passwordHash: secret.hash, createdAt: new Date().toISOString() };
  db.users.push(user); const token = createSession(user);
  res.status(201).json({ token, user: publicUser(user) });
});
app.post('/api/auth/login', async (req, res) => {
  if (!allowAuthAttempt(req)) return res.status(429).json({ error: 'Слишком много попыток. Подожди минуту.' });
  const username = cleanUsername(req.body?.username), password = req.body?.password;
  const user = db.users.find(u => u.username === username);
  if (!user || typeof password !== 'string' || !(await checkPassword(password, { salt: user.passwordSalt, hash: user.passwordHash })))
    return res.status(401).json({ error: 'Неверный логин или пароль.' });
  const token = createSession(user);
  res.json({ token, user: publicUser(user) });
});
app.get('/api/me', authHttp, (req, res) => res.json({ user: publicUser(req.user) }));
app.post('/api/auth/logout', authHttp, (req, res) => { db.sessions = db.sessions.filter(s => s.tokenHash !== req.session.tokenHash); saveDb(); io.in(`user:${req.user.id}`).disconnectSockets(true); res.json({ ok: true }); });
app.get('/api/users/search', authHttp, (req, res) => {
  const q = cleanUsername(req.query.q).replace(/^@/, '').slice(0, 24);
  if (q.length < 2) return res.json([]);
  res.json(db.users.filter(u => u.id !== req.user.id && (u.username.includes(q) || u.displayName.toLowerCase().includes(q))).slice(0, 20).map(publicUser));
});
function dmSummary(conversation, viewerId) {
  const otherId = conversation.userIds.find(id => id !== viewerId);
  const other = db.users.find(u => u.id === otherId);
  const last = conversation.messages.at(-1);
  return { id: conversation.id, other: other ? publicUser(other) : { id: otherId, username: 'unknown', displayName: 'Удалённый аккаунт' }, lastMessage: last?.text || (last?.attachment ? '📎 ' + last.attachment.name : ''), time: last?.time || conversation.createdAt };
}
function communityMembership(community, userId) {
  return (community.members || []).find(member => member.userId === userId) || null;
}
function roleFor(community, membership) {
  return (community.roles || []).find(role => role.id === membership?.roleId) || null;
}
function hasPermission(community, userId, permission, channel = null) {
  // Legacy seeded communities remain open so existing accounts keep working.
  if (!community.ownerId) return permission === 'view' || permission === 'send';
  const member = communityMembership(community, userId);
  if (!member) return false;
  const role = roleFor(community, member);
  if (!role || !(role.permissions || []).some(value => value === '*' || value === permission)) return false;
  if (channel?.private && !(channel.allowedRoleIds || []).includes(member.roleId) && member.roleId !== 'owner') return false;
  return true;
}
function publicCommunity(community, userId) {
  const member = communityMembership(community, userId);
  return {
    id: community.id, name: community.name, description: community.description || '',
    ownerId: community.ownerId || null, createdAt: community.createdAt || null,
    memberCount: community.ownerId ? (community.members || []).length : null,
    categories: (community.categories || []).map(category => ({ id: category.id, name: category.name })),
    roles: (community.roles || []).map(role => ({ id: role.id, name: role.name, permissions: role.id === 'owner' ? [] : role.permissions })),
    isMember: !community.ownerId || Boolean(member),
    myRole: member?.roleId || (!community.ownerId ? 'member' : null),
    channels: (community.channels || []).filter(channel => hasPermission(community, userId, 'view', channel)).map(channel => ({ id: channel.id, name: channel.name, type: channel.type, private: Boolean(channel.private), categoryId: channel.categoryId || null }))
  };
}
function canManageCommunity(community, userId) {
  return hasPermission(community, userId, 'manage_community');
}
function canManageMembers(community, userId) {
  return hasPermission(community, userId, 'manage_members');
}
app.get('/api/bootstrap', authHttp, (req, res) => res.json({
  communities: db.communities.filter(community => !community.ownerId || communityMembership(community, req.user.id)).map(community => publicCommunity(community, req.user.id))
}));
app.get('/api/communities/:id/members', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'view')) return res.status(403).json({ error: 'Ты не состоишь в этом сообществе.' });
  res.json((community.members || []).map(member => {
    const user = db.users.find(item => item.id === member.userId);
    return { user: user ? publicUser(user) : { id: member.userId, username: 'unknown', displayName: 'Удалённый аккаунт' }, roleId: member.roleId, joinedAt: member.joinedAt };
  }));
});
app.post('/api/communities/:id/invites', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!canManageCommunity(community, req.user.id)) return res.status(403).json({ error: 'Создавать приглашения может только владелец или администратор.' });
  community.invites ||= [];
  const code = randomBytes(9).toString('base64url');
  const maxUses = Number.isInteger(req.body?.maxUses) ? Math.max(1, Math.min(1000, req.body.maxUses)) : 0;
  const invite = { code, createdBy: req.user.id, createdAt: new Date().toISOString(), uses: 0, maxUses };
  community.invites.push(invite); saveDb();
  res.status(201).json({ code, communityId: community.id, communityName: community.name, uses: invite.uses, maxUses: invite.maxUses });
});
app.post('/api/invites/:code/join', authHttp, (req, res) => {
  const community = db.communities.find(item => (item.invites || []).some(invite => invite.code === req.params.code));
  if (!community) return res.status(404).json({ error: 'Приглашение не найдено или отключено.' });
  const invite = community.invites.find(item => item.code === req.params.code);
  if (invite.maxUses && invite.uses >= invite.maxUses) return res.status(410).json({ error: 'Лимит использований приглашения исчерпан.' });
  community.members ||= [];
  let member = communityMembership(community, req.user.id);
  if (!member) {
    member = { userId: req.user.id, roleId: 'member', joinedAt: new Date().toISOString() };
    community.members.push(member);
    invite.uses += 1; saveDb();
  }
  res.json({ community: publicCommunity(community, req.user.id) });
});
app.patch('/api/communities/:id/members/:userId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!canManageMembers(community, req.user.id)) return res.status(403).json({ error: 'Недостаточно прав для управления участниками.' });
  const member = communityMembership(community, req.params.userId);
  if (!member) return res.status(404).json({ error: 'Участник не найден.' });
  if (member.roleId === 'owner') return res.status(403).json({ error: 'Нельзя менять роль владельца.' });
  const role = (community.roles || []).find(item => item.id === req.body?.roleId && item.id !== 'owner');
  if (!role) return res.status(400).json({ error: 'Укажи существующую роль.' });
  const actor = communityMembership(community, req.user.id);
  if (actor?.roleId !== 'owner' && role.id !== 'member') return res.status(403).json({ error: 'Назначать административные роли может только владелец.' });
  member.roleId = role.id; saveDb();
  res.json({ userId: member.userId, roleId: member.roleId });
});
app.delete('/api/communities/:id/members/:userId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  const actor = communityMembership(community, req.user.id);
  const target = communityMembership(community, req.params.userId);
  if (!target) return res.status(404).json({ error: 'Участник не найден.' });
  if (target.roleId === 'owner') return res.status(403).json({ error: 'Владельца нельзя удалить из сообщества.' });
  if (req.user.id !== target.userId && !canManageMembers(community, req.user.id)) return res.status(403).json({ error: 'Недостаточно прав для удаления участника.' });
  if (req.user.id === target.userId && target.roleId === 'owner') return res.status(403).json({ error: 'Передай владение другому участнику перед выходом.' });
  community.members = community.members.filter(item => item.userId !== target.userId);
  saveDb();
  for (const socket of io.sockets.sockets.values()) {
    if (socket.data.user.id === target.userId) {
      for (const channel of community.channels || []) socket.leave(channel.id);
    }
  }
  res.json({ ok: true });
});
app.patch('/api/communities/:id/roles/:roleId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (community.ownerId !== req.user.id) return res.status(403).json({ error: 'Изменять права ролей может только владелец сообщества.' });
  const role = (community.roles || []).find(item => item.id === req.params.roleId && item.id !== 'owner');
  if (!role) return res.status(404).json({ error: 'Роль не найдена.' });
  const name = typeof req.body?.name === 'string' ? req.body.name.trim().slice(0, 32) : role.name;
  const allowedPermissions = new Set(['view','send','manage_channels','manage_members','manage_community','manage_messages']);
  const permissions = Array.isArray(req.body?.permissions) ? [...new Set(req.body.permissions.filter(value => allowedPermissions.has(value)))].slice(0, allowedPermissions.size) : role.permissions;
  if (name) role.name = name;
  role.permissions = permissions.filter(value => value !== '*');
  saveDb();
  res.json(role);
});
app.get('/api/dms', authHttp, (req, res) => res.json(db.dms.filter(d => d.userIds.includes(req.user.id)).map(d => dmSummary(d, req.user.id))));
app.post('/api/dms', authHttp, (req, res) => {
  const username = cleanUsername(req.body?.username).replace(/^@/, '');
  const other = db.users.find(u => u.username === username);
  if (!other) return res.status(404).json({ error: 'Пользователь не найден.' });
  if (other.id === req.user.id) return res.status(400).json({ error: 'Нельзя создать чат с самим собой.' });
  let conversation = db.dms.find(d => d.userIds.includes(req.user.id) && d.userIds.includes(other.id));
  if (!conversation) {
    conversation = { id: randomBytes(16).toString('hex'), userIds: [req.user.id, other.id], messages: [], createdAt: new Date().toISOString() };
    db.dms.push(conversation); saveDb();
  }
  res.status(200).json(dmSummary(conversation, req.user.id));
});
app.get('/api/dms/:id/messages', authHttp, (req, res) => {
  const conversation = db.dms.find(d => d.id === req.params.id && d.userIds.includes(req.user.id));
  if (!conversation) return res.status(404).json({ error: 'Личный чат не найден.' });
  res.json(conversation.messages.slice(-MAX_HISTORY));
});

const uploadRoot = path.join(path.dirname(DATA), 'uploads');
const MAX_UPLOAD_SIZE = 4 * 1024 * 1024;
const allowedUploadTypes = new Set(['image/jpeg','image/png','image/gif','image/webp','application/pdf','text/plain']);
app.post('/api/uploads', authHttp, express.raw({ type: 'application/octet-stream', limit: MAX_UPLOAD_SIZE }), (req, res) => {
  const scope = String(req.query.scope || '');
  const scopeId = String(req.query.id || '');
  const name = String(req.query.name || 'attachment').replace(/[\\/\r\n\0]/g, '_').slice(0, 120);
  const type = String(req.query.type || '').toLowerCase();
  if (!['dm','channel'].includes(scope) || !scopeId || scopeId.length > 120) return res.status(400).json({ error: 'Некорректное место отправки файла.' });
  if (!allowedUploadTypes.has(type)) return res.status(415).json({ error: 'Поддерживаются изображения, PDF и текстовые файлы.' });
  if (!Buffer.isBuffer(req.body) || req.body.length < 1 || req.body.length > MAX_UPLOAD_SIZE) return res.status(400).json({ error: 'Файл пустой или превышает 4 МБ.' });
  if (scope === 'dm' && !db.dms.some(d => d.id === scopeId && d.userIds.includes(req.user.id))) return res.status(404).json({ error: 'Личный чат не найден.' });
  if (scope === 'channel') {
    const community = db.communities.find(item => (item.channels || []).some(channel => channel.id === scopeId));
    const channel = community?.channels.find(item => item.id === scopeId);
    if (!community || !channel) return res.status(404).json({ error: 'Канал не найден.' });
    if (!hasPermission(community, req.user.id, 'send', channel)) return res.status(403).json({ error: 'Нет прав отправлять файлы в этот канал.' });
  }
  const id = randomBytes(18).toString('hex');
  fs.mkdirSync(uploadRoot, { recursive: true });
  const filePath = path.join(uploadRoot, id);
  fs.writeFileSync(filePath, req.body, { mode: 0o600, flag: 'wx' });
  const attachment = { id, ownerId: req.user.id, name, type, size: req.body.length, scope, scopeId, filePath, createdAt: new Date().toISOString() };
  db.uploads.push(attachment); saveDb();
  res.status(201).json({ id, name, type, size: attachment.size });
});
app.get('/api/uploads/:id', authHttp, (req, res) => {
  const attachment = db.uploads.find(item => item.id === req.params.id);
  if (!attachment) return res.status(404).json({ error: 'Файл не найден.' });
  const allowed = attachment.scope === 'dm'
    ? db.dms.some(d => d.id === attachment.scopeId && d.userIds.includes(req.user.id))
    : (() => { const community = db.communities.find(item => (item.channels || []).some(channel => channel.id === attachment.scopeId)); const channel = community?.channels.find(item => item.id === attachment.scopeId); return Boolean(community && channel && hasPermission(community, req.user.id, 'view', channel)); })();
  if (!allowed) return res.status(403).json({ error: 'Нет доступа к этому файлу.' });
  if (!fs.existsSync(attachment.filePath)) return res.status(410).json({ error: 'Файл больше не хранится на сервере.' });
  res.setHeader('Content-Type', attachment.type);
  res.setHeader('Content-Length', attachment.size);
  res.setHeader('Content-Disposition', "attachment; filename*=UTF-8''" + encodeURIComponent(attachment.name));
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'private, no-store');
  fs.createReadStream(attachment.filePath).pipe(res);
});

app.get('/api/messages/:channelId', authHttp, (req, res) => {
  const community = db.communities.find(item => (item.channels || []).some(channel => channel.id === req.params.channelId));
  const channel = community?.channels.find(item => item.id === req.params.channelId);
  if (!community || !channel) return res.status(404).json({ error: 'Канал не найден.' });
  if (!hasPermission(community, req.user.id, 'view', channel)) return res.status(403).json({ error: 'Нет доступа к этому каналу.' });
  res.json(db.messages[req.params.channelId] || []);
});
app.post('/api/communities', authHttp, (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 48);
  if (!name) return res.status(400).json({ error: 'Введите название сообщества.' });
  const id = `community-${randomBytes(8).toString('hex')}`;
  const community = {
    id, name, description: String(req.body?.description || '').trim().slice(0, 240) || 'Новое сообщество',
    ownerId: req.user.id, createdAt: new Date().toISOString(),
    roles: [
      { id: 'owner', name: 'Владелец', permissions: ['*'] },
      { id: 'admin', name: 'Администратор', permissions: ['view','send','manage_channels','manage_members','manage_community','manage_messages'] },
      { id: 'member', name: 'Участник', permissions: ['view','send'] }
    ],
    members: [{ userId: req.user.id, roleId: 'owner', joinedAt: new Date().toISOString() }],
    invites: [], categories: [],
    channels: [{ id: `${id}-general`, name: 'общение', type: 'text', private: false, allowedRoleIds: [], categoryId: null }]
  };
  db.communities.push(community); saveDb(); io.emit('communities:changed', { communityId: community.id }); res.status(201).json(publicCommunity(community, req.user.id));
});
app.post('/api/communities/:id/channels', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для создания каналов.' });
  const name = String(req.body?.name || '').trim().slice(0, 48);
  const type = req.body?.type === 'voice' ? 'voice' : 'text';
  const isPrivate = req.body?.private === true;
  if (!name) return res.status(400).json({ error: 'Введите название канала.' });
  const categoryId = typeof req.body?.categoryId === 'string' && (community.categories || []).some(category => category.id === req.body.categoryId) ? req.body.categoryId : null;
  const channel = { id: `${community.id}-${randomBytes(6).toString('hex')}`, name, type, private: isPrivate, allowedRoleIds: isPrivate ? ['owner','admin'] : [], categoryId };
  community.channels.push(channel); saveDb(); io.emit('communities:changed', { communityId: community.id }); res.status(201).json(channel);
});
app.post('/api/communities/:id/categories', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для создания категорий.' });
  const name = String(req.body?.name || '').trim().slice(0, 48);
  if (!name) return res.status(400).json({ error: 'Введите название категории.' });
  community.categories ||= [];
  if (community.categories.length >= 50) return res.status(409).json({ error: 'В сообществе уже максимум 50 категорий.' });
  const category = { id: `${community.id}-category-${randomBytes(5).toString('hex')}`, name };
  community.categories.push(category); saveDb(); io.emit('communities:changed', { communityId: community.id }); res.status(201).json(category);
});
app.patch('/api/communities/:id/categories/:categoryId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для изменения категорий.' });
  const category = (community.categories || []).find(item => item.id === req.params.categoryId);
  if (!category) return res.status(404).json({ error: 'Категория не найдена.' });
  const name = String(req.body?.name || '').trim().slice(0, 48);
  if (!name) return res.status(400).json({ error: 'Введите название категории.' });
  category.name = name; saveDb(); io.emit('communities:changed', { communityId: community.id }); res.json(category);
});
app.delete('/api/communities/:id/categories/:categoryId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для удаления категорий.' });
  if (!(community.categories || []).some(item => item.id === req.params.categoryId)) return res.status(404).json({ error: 'Категория не найдена.' });
  community.categories = community.categories.filter(item => item.id !== req.params.categoryId);
  for (const channel of community.channels || []) if (channel.categoryId === req.params.categoryId) channel.categoryId = null;
  saveDb(); io.emit('communities:changed', { communityId: community.id }); res.json({ ok: true });
});
app.patch('/api/communities/:id/channels/:channelId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для изменения каналов.' });
  const channel = community.channels.find(item => item.id === req.params.channelId);
  if (!channel) return res.status(404).json({ error: 'Канал не найден.' });
  if (typeof req.body?.name === 'string' && req.body.name.trim()) channel.name = req.body.name.trim().slice(0, 48);
  if (req.body?.categoryId === null) channel.categoryId = null;
  else if (typeof req.body?.categoryId === 'string' && (community.categories || []).some(category => category.id === req.body.categoryId)) channel.categoryId = req.body.categoryId;
  if (typeof req.body?.private === 'boolean') {
    channel.private = req.body.private;
    channel.allowedRoleIds = channel.private ? ['owner','admin'] : [];
  }
  if (Array.isArray(req.body?.allowedRoleIds)) {
    const known = new Set((community.roles || []).map(role => role.id));
    channel.allowedRoleIds = [...new Set(req.body.allowedRoleIds.filter(id => known.has(id)))];
  }
  saveDb(); io.emit('communities:changed', { communityId: community.id }); res.json(channel);
});
app.delete('/api/communities/:id/channels/:channelId', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_channels')) return res.status(403).json({ error: 'Недостаточно прав для удаления каналов.' });
  if (!community.channels.some(item => item.id === req.params.channelId)) return res.status(404).json({ error: 'Канал не найден.' });
  community.channels = community.channels.filter(item => item.id !== req.params.channelId);
  delete db.messages[req.params.channelId];
  saveDb();
  for (const socket of io.sockets.sockets.values()) socket.leave(req.params.channelId);
  io.emit('communities:changed', { communityId: community.id }); res.json({ ok: true });
});
app.delete('/api/communities/:id', authHttp, (req, res) => {
  const community = db.communities.find(item => item.id === req.params.id);
  if (!community) return res.status(404).json({ error: 'Сообщество не найдено.' });
  if (!hasPermission(community, req.user.id, 'manage_community')) return res.status(403).json({ error: 'Только владелец или администратор может удалить сообщество.' });
  if (community.ownerId !== req.user.id) return res.status(403).json({ error: 'Удалить сообщество может только владелец.' });
  db.communities = db.communities.filter(item => item.id !== community.id);
  for (const channel of community.channels || []) delete db.messages[channel.id];
  saveDb();
  for (const socket of io.sockets.sockets.values()) for (const channel of community.channels || []) socket.leave(channel.id);
  io.emit('communities:changed', { communityId: community.id }); res.json({ ok: true });
});

if (fs.existsSync(path.resolve(__dirname, '../dist'))) {
  const clientDist = path.resolve(__dirname, '../dist'); app.use(express.static(clientDist));
  app.get('*', (_req, res) => res.sendFile(path.join(clientDist, 'index.html')));
}
io.use((socket, next) => {
  const auth = getSession(socket.handshake.auth?.token);
  if (!auth) return next(new Error('unauthorized'));
  socket.data.user = auth.user;
  socket.data.sessionTokenHash = auth.session.tokenHash;
  next();
});
function socketSessionIsValid(socket) {
  return db.sessions.some(s => s.tokenHash === socket.data.sessionTokenHash && s.expiresAt > Date.now());
}
const activeCalls = new Map();
function callPayloadIsValid(payload) {
  return payload && typeof payload.callId === 'string' && payload.callId.length <= 80 &&
    typeof payload.conversationId === 'string' && payload.conversationId.length <= 64;
}
function callForUser(callId, userId) {
  const call = activeCalls.get(callId);
  return call && call.userIds.includes(userId) ? call : null;
}

io.on('connection', socket => {
  socket.join(`user:${socket.data.user.id}`);
  socket.on('call:invite', payload => {
    if (!socketSessionIsValid(socket)) { socket.disconnect(true); return; }
    if (!callPayloadIsValid(payload) || !['audio', 'video'].includes(payload.mode)) return;
    const conversation = db.dms.find(d => d.id === payload.conversationId && d.userIds.includes(socket.data.user.id));
    const targetId = String(payload.toUserId || '');
    if (!conversation || !conversation.userIds.includes(targetId) || targetId === socket.data.user.id) return;
    if (activeCalls.has(payload.callId)) return;
    const call = { callId: payload.callId, conversationId: conversation.id, userIds: [...conversation.userIds], callerId: socket.data.user.id, mode: payload.mode, createdAt: Date.now() };
    activeCalls.set(call.callId, call);
    io.to(`user:${targetId}`).emit('call:incoming', { callId: call.callId, conversationId: call.conversationId, caller: publicUser(socket.data.user), mode: call.mode, offer: payload.offer });
  });
  socket.on('call:accept', payload => {
    if (!socketSessionIsValid(socket) || !callPayloadIsValid(payload)) return;
    const call = callForUser(payload.callId, socket.data.user.id);
    if (!call || call.conversationId !== payload.conversationId || call.callerId === socket.data.user.id) return;
    io.to(`user:${call.callerId}`).emit('call:accepted', { callId: call.callId, conversationId: call.conversationId, answer: payload.answer });
  });
  socket.on('call:signal', payload => {
    if (!socketSessionIsValid(socket) || !callPayloadIsValid(payload)) return;
    const call = callForUser(payload.callId, socket.data.user.id);
    if (!call || call.conversationId !== payload.conversationId || !payload.signal || typeof payload.signal !== 'object') return;
    const targetId = call.userIds.find(id => id !== socket.data.user.id);
    io.to(`user:${targetId}`).emit('call:signal', { callId: call.callId, conversationId: call.conversationId, signal: payload.signal });
  });
  socket.on('call:reject', payload => {
    if (!socketSessionIsValid(socket) || !callPayloadIsValid(payload)) return;
    const call = callForUser(payload.callId, socket.data.user.id);
    if (!call || call.conversationId !== payload.conversationId) return;
    const targetId = call.userIds.find(id => id !== socket.data.user.id);
    activeCalls.delete(call.callId);
    io.to(`user:${targetId}`).emit('call:rejected', { callId: call.callId, conversationId: call.conversationId });
  });
  socket.on('call:end', payload => {
    if (!socketSessionIsValid(socket) || !callPayloadIsValid(payload)) return;
    const call = callForUser(payload.callId, socket.data.user.id);
    if (!call || call.conversationId !== payload.conversationId) return;
    const targetId = call.userIds.find(id => id !== socket.data.user.id);
    activeCalls.delete(call.callId);
    io.to(`user:${targetId}`).emit('call:ended', { callId: call.callId, conversationId: call.conversationId });
  });
  socket.on('disconnect', () => {
    for (const [callId, call] of activeCalls) {
      if (!call.userIds.includes(socket.data.user.id)) continue;
      activeCalls.delete(callId);
      const targetId = call.userIds.find(id => id !== socket.data.user.id);
      io.to(`user:${targetId}`).emit('call:ended', { callId, conversationId: call.conversationId });
    }
  });
  const messageBurst = createRateLimiter({ limit: 20, windowMs: 10_000 });
  const messageSustained = createRateLimiter({ limit: 120, windowMs: 60_000 });
  socket.on('channel:join', channelId => {
    if (!socketSessionIsValid(socket)) { socket.disconnect(true); return; }
    const community = db.communities.find(item => (item.channels || []).some(channel => channel.id === channelId));
    const channel = community?.channels.find(item => item.id === channelId);
    if (!community || !channel || !hasPermission(community, socket.data.user.id, 'view', channel)) return;
    for (const room of socket.rooms) if (room !== socket.id && room !== `user:${socket.data.user.id}`) socket.leave(room);
    socket.join(channelId);
  });
  socket.on('dm:join', conversationId => {
    if (!socketSessionIsValid(socket)) { socket.disconnect(true); return; }
    if (typeof conversationId !== 'string' || conversationId.length > 64) return;
    const conversation = db.dms.find(d => d.id === conversationId && d.userIds.includes(socket.data.user.id));
    if (!conversation) return;
    for (const room of socket.rooms) if (room !== socket.id && room !== `user:${socket.data.user.id}`) socket.leave(room);
    socket.join(`dm:${conversation.id}`);
  });
  socket.on('dm:send', payload => {
    if (!socketSessionIsValid(socket)) { socket.disconnect(true); return; }
    if (!messageBurst() || !messageSustained()) { socket.emit('message:error', { error: 'Слишком много сообщений. Подождите немного.' }); return; }
    const conversationId = String(payload?.conversationId || '').slice(0, 64);
    const text = typeof payload?.text === 'string' ? payload.text.trim().slice(0, MAX_MESSAGE_LENGTH) : '';
    const conversation = db.dms.find(d => d.id === conversationId && d.userIds.includes(socket.data.user.id));
    const upload = payload?.attachmentId ? db.uploads.find(item => item.id === payload.attachmentId && item.scope === 'dm' && item.scopeId === conversationId) : null;
    if (!conversation || !socket.rooms.has(`dm:${conversationId}`) || (!text && !upload)) return;
    const message = { id: randomBytes(12).toString('hex'), userId: socket.data.user.id, user: socket.data.user.displayName, text, time: new Date().toISOString(), ...(upload ? { attachment: { id: upload.id, name: upload.name, type: upload.type, size: upload.size } } : {}) };
    conversation.messages.push(message); conversation.messages = conversation.messages.slice(-MAX_HISTORY); saveDb();
    const event = { conversationId, message };
    for (const userId of conversation.userIds) io.to(`user:${userId}`).emit('dm:new', event);
  });
  socket.on('message:send', payload => {
    if (!socketSessionIsValid(socket)) { socket.disconnect(true); return; }
    if (!messageBurst() || !messageSustained()) { socket.emit('message:error', { error: 'Слишком много сообщений. Подождите немного.' }); return; }
    const channelId = String(payload?.channelId || '').slice(0, 120);
    const text = typeof payload?.text === 'string' ? payload.text.trim().slice(0, MAX_MESSAGE_LENGTH) : '';
    const upload = payload?.attachmentId ? db.uploads.find(item => item.id === payload.attachmentId && item.scope === 'channel' && item.scopeId === channelId) : null;
    const community = db.communities.find(item => (item.channels || []).some(channel => channel.id === channelId));
    const channel = community?.channels.find(item => item.id === channelId);
    if (!channelId || (!text && !upload) || !community || !channel || !hasPermission(community, socket.data.user.id, 'send', channel) || !socket.rooms.has(channelId)) return;
    const message = { id: randomBytes(12).toString('hex'), userId: socket.data.user.id, user: socket.data.user.displayName, text, time: new Date().toISOString(), ...(upload ? { attachment: { id: upload.id, name: upload.name, type: upload.type, size: upload.size } } : {}) };
    db.messages[channelId] ||= []; db.messages[channelId].push(message); db.messages[channelId] = db.messages[channelId].slice(-MAX_HISTORY); saveDb();
    io.to(channelId).emit('message:new', { channelId, message });
  });
});
server.listen(PORT, '0.0.0.0', () => console.log(`Lunex server listening on port ${PORT}`));
