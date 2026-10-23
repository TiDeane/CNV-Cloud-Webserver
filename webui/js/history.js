// Request history. Metadata persists in localStorage; full responses are kept
// in memory only, so rows from earlier sessions can't be re-opened.

const KEY = 'gamesAtCloud.history.v1';
const MAX_ENTRIES = 200;

const results = new Map(); // id -> full result (this session only)
let entries = load();
const listeners = new Set();

function load() {
  try {
    const parsed = JSON.parse(localStorage.getItem(KEY) || '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(entries));
  } catch { /* storage unavailable: history stays in memory */ }
}

function notify() {
  for (const fn of listeners) fn(entries);
}

export function addResult(result) {
  const id = `${result.startedAt}-${Math.random().toString(36).slice(2, 8)}`;
  results.set(id, result);
  entries.unshift({
    id,
    time: result.startedAt,
    game: result.game,
    params: result.params,
    handledBy: result.handledBy,
    ms: Math.round(result.ms),
    status: result.status,
    ok: result.ok,
    error: result.error,
  });
  for (const dropped of entries.splice(MAX_ENTRIES)) results.delete(dropped.id);
  save();
  notify();
  return id;
}

export function getResult(id) {
  return results.get(id) ?? null;
}

export function clearHistory() {
  entries = [];
  results.clear();
  save();
  notify();
}

export function onHistoryChange(fn) {
  listeners.add(fn);
  fn(entries);
}
