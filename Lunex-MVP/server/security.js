export function parseAllowedOrigins(value = '') {
  const defaults = ['http://localhost:5173', 'http://127.0.0.1:5173', 'http://localhost:4173', 'http://127.0.0.1:4173', 'http://localhost:3001', 'http://127.0.0.1:3001'];
  const entries = value.split(',').map(v => v.trim()).filter(Boolean);
  return new Set(entries.length ? entries : defaults);
}

export function isAllowedOrigin(origin, allowedOrigins) {
  // Non-browser clients may omit Origin. Any supplied Origin must match exactly.
  if (!origin) return true;
  return allowedOrigins.has(origin);
}

export function createRateLimiter({ limit, windowMs, now = Date.now }) {
  const events = [];
  return () => {
    const cutoff = now() - windowMs;
    while (events.length && events[0] <= cutoff) events.shift();
    if (events.length >= limit) return false;
    events.push(now());
    return true;
  };
}

export function isValidChannelId(channelId, communities) {
  if (typeof channelId !== 'string' || channelId.length < 1 || channelId.length > 120) return false;
  return communities.some(community => community.channels.some(channel => channel.id === channelId));
}
