// HTTP access to the Games@Cloud load balancer (or a single worker).

export const GAMES = {
  capturetheflag: {
    label: 'Capture the Flag',
    params: ['gridSize', 'numBlueAgents', 'numRedAgents', 'flagPlacementType'],
  },
  fifteenpuzzle: {
    label: '15-Puzzle',
    params: ['size', 'shuffles'],
  },
  gameoflife: {
    label: 'Game of Life',
    params: ['mapFilename', 'iterations'],
  },
};

export function buildUrl(base, game, params) {
  const url = new URL(game, base.endsWith('/') ? base : base + '/');
  for (const key of GAMES[game].params) url.searchParams.set(key, params[key]);
  return url.toString();
}

/**
 * The LB relays EC2 responses verbatim, but Lambda responses arrive as the
 * Lambda payload: a JSON-encoded string ("...\n..."). Undo that encoding.
 */
export function unwrapLambdaPayload(raw) {
  const trimmed = raw.trim();
  if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
    try {
      const decoded = JSON.parse(trimmed);
      if (typeof decoded === 'string') return decoded;
    } catch { /* not a JSON string, keep as-is */ }
  }
  return raw;
}

/** Error bodies the servers return with (or without) a 200 status. */
function detectBodyError(game, body) {
  const text = body.trim();
  if (!text) return 'Empty response';
  if (text.startsWith('Invalid input')) return text;
  if (text.includes('"errorType"')) return 'Lambda error: ' + text;
  if (game === 'capturetheflag' && text === 'no possible moves') {
    return 'Simulation deadlocked: no agent could move (the server discards the board when this happens).';
  }
  if (game === 'gameoflife' && text.startsWith('{')) {
    try {
      const obj = JSON.parse(text);
      // getResourceAsStream() returns null for a missing map, so the NPE message is "null".
      if (obj && obj.error !== undefined) {
        return obj.error === null || obj.error === 'null'
          ? 'Map file not found on the server (it must be in gameoflife/src/main/resources).'
          : 'Server error: ' + obj.error;
      }
    } catch { /* parser reports malformed JSON */ }
  }
  return null;
}

export function classifyBackend(handledBy) {
  if (!handledBy) return { kind: 'direct', label: 'Worker (direct)' };
  if (handledBy === 'lambda') return { kind: 'lambda', label: 'Lambda' };
  if (handledBy.startsWith('ec2:')) return { kind: 'ec2', label: 'EC2 ' + handledBy.slice(4) };
  return { kind: 'direct', label: handledBy };
}

/**
 * Sends one game request. Never throws: failures come back as { ok: false, error }.
 */
export async function sendRequest(base, game, params, { signal } = {}) {
  const url = buildUrl(base, game, params);
  const started = performance.now();
  const result = {
    game, params: { ...params }, url,
    startedAt: Date.now(), ms: 0, status: 0,
    handledBy: null, requestId: null,
    raw: '', body: '', ok: false, error: null,
  };

  let res;
  try {
    res = await fetch(url, { signal, cache: 'no-store' });
    result.raw = await res.text();
  } catch (e) {
    result.ms = performance.now() - started;
    result.error = e.name === 'AbortError'
      ? 'Cancelled'
      : `Network error: ${e.message}. Check the target URL and that the server is running.`;
    result.cancelled = e.name === 'AbortError';
    return result;
  }

  result.ms = performance.now() - started;
  result.status = res.status;
  result.handledBy = res.headers.get('X-Handled-By');
  result.requestId = res.headers.get('X-Request-Id');
  result.body = unwrapLambdaPayload(result.raw);

  if (!res.ok) {
    result.error = `HTTP ${res.status}: ${result.body.trim() || res.statusText}`;
  } else {
    result.error = detectBodyError(game, result.body);
  }
  result.ok = !result.error;
  return result;
}

/** Root handlers send headers right away but never close the body, so resolve on headers. */
export async function checkTarget(base, timeoutMs = 5000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(new URL('/', base), { signal: ctrl.signal, cache: 'no-store' });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
    ctrl.abort();
  }
}
