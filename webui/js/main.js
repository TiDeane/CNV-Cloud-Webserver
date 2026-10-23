import { GAMES, buildUrl, sendRequest, checkTarget, classifyBackend } from './api.js';
import { PARSERS } from './parsers.js';
import { RENDERERS } from './render.js';
import { addResult, getResult, clearHistory, onHistoryChange } from './history.js';
import { runBurst, summarize } from './burst.js';

const TARGET_KEY = 'gamesAtCloud.target';
const THEME_KEY = 'gamesAtCloud.theme';
const DEFAULT_TARGET = 'http://localhost:8001';

const $ = (sel) => document.querySelector(sel);

const state = {
  game: 'capturetheflag',
  view: null,          // current result renderer view
  inflight: null,      // AbortController of the single request
  burst: null,         // AbortController of the running burst
  selectedHistoryId: null,
};

/* ---------------------------------------------------------------- storage */

function readStored(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}
function writeStored(key, value) {
  try { localStorage.setItem(key, value); } catch { /* ignore */ }
}

/* ---------------------------------------------------------------- target */

function normalizeTarget(value) {
  let v = value.trim();
  if (!v) return null;
  if (!/^https?:\/\//i.test(v)) v = 'http://' + v;
  try {
    const url = new URL(v);
    return url.origin + url.pathname.replace(/\/+$/, '');
  } catch {
    return null;
  }
}

function currentTarget() {
  return normalizeTarget($('#target-url').value);
}

async function onCheckTarget(event) {
  event.preventDefault();
  const dot = $('#target-status');
  const base = currentTarget();
  if (!base) { dot.dataset.state = 'bad'; dot.title = 'Invalid URL'; return; }
  $('#target-url').value = base;
  writeStored(TARGET_KEY, base);
  dot.dataset.state = 'pending';
  dot.title = 'Checking…';
  const ok = await checkTarget(base);
  dot.dataset.state = ok ? 'ok' : 'bad';
  dot.title = ok ? `${base} is reachable` : `${base} did not respond`;
  updatePreview();
}

/* ---------------------------------------------------------------- forms */

const isInt = (v) => v.trim() !== '' && Number.isInteger(Number(v));

function readParams(game) {
  const form = $(`#form-${game}`);
  const values = Object.fromEntries(new FormData(form).entries());
  for (const input of form.querySelectorAll('input')) input.removeAttribute('aria-invalid');
  const fail = (name, message) => ({ error: message, field: form.elements[name] });

  if (game === 'capturetheflag') {
    const { gridSize, numBlueAgents, numRedAgents, flagPlacementType } = values;
    if (!isInt(gridSize) || Number(gridSize) < 10) return fail('gridSize', 'Grid size must be an integer ≥ 10.');
    for (const name of ['numBlueAgents', 'numRedAgents']) {
      const v = values[name];
      if (!isInt(v) || Number(v) < 0 || Number(v) > Number(gridSize)) {
        return fail(name, 'Agents per team must be an integer between 0 and the grid size.');
      }
    }
    if (!['A', 'B', 'C'].includes(flagPlacementType)) return fail('flagPlacementType', 'Placement must be A, B or C.');
    return { params: { gridSize, numBlueAgents, numRedAgents, flagPlacementType } };
  }

  if (game === 'fifteenpuzzle') {
    const { size, shuffles } = values;
    if (!isInt(size) || Number(size) < 2) return fail('size', 'Board size must be an integer ≥ 2.');
    if (!isInt(shuffles) || Number(shuffles) < 0) return fail('shuffles', 'Shuffles must be a non-negative integer.');
    return { params: { size, shuffles } };
  }

  const mapFilename = values.mapFilename.trim();
  const { iterations } = values;
  // The servers don't URL-decode the query, so keep file names to safe characters.
  if (!/^[\w.-]+$/.test(mapFilename)) return fail('mapFilename', 'Map file name may only contain letters, digits, ".", "_" and "-".');
  if (!isInt(iterations) || Number(iterations) < 0) return fail('iterations', 'Iterations must be a non-negative integer.');
  return { params: { mapFilename, iterations } };
}

function describeParams(game, p) {
  if (game === 'capturetheflag') return `${p.gridSize}×${p.gridSize} · ${p.numBlueAgents}v${p.numRedAgents} · ${p.flagPlacementType}`;
  if (game === 'fifteenpuzzle') return `${p.size}×${p.size} · ${p.shuffles} shuffles`;
  return `${p.mapFilename} · ${p.iterations} gen`;
}

function updatePreview() {
  const base = currentTarget();
  const { params } = readParams(state.game);
  $('#url-preview').textContent = base && params ? 'GET ' + buildUrl(base, state.game, params) : '';
  $('#burst-source').textContent = params
    ? `Sends ${GAMES[state.game].label} (${describeParams(state.game, params)}) using the Request panel's parameters.`
    : 'Fix the Request panel parameters first.';
}

function selectGame(game) {
  state.game = game;
  for (const tab of document.querySelectorAll('.request-panel .tab')) {
    tab.setAttribute('aria-selected', String(tab.dataset.game === game));
  }
  for (const form of document.querySelectorAll('.game-form')) form.hidden = form.dataset.game !== game;
  $('#form-error').hidden = true;
  updatePreview();
}

function fillForm(game, params) {
  const form = $(`#form-${game}`);
  for (const [k, v] of Object.entries(params)) if (form.elements[k]) form.elements[k].value = v;
}

/* ---------------------------------------------------------------- result panel */

function badge(kind, text, title) {
  const b = document.createElement('span');
  b.className = 'badge';
  b.dataset.kind = kind;
  b.textContent = text;
  if (title) b.title = title;
  return b;
}

function renderMeta(result) {
  const meta = $('#result-meta');
  meta.replaceChildren();
  if (result.status) {
    const backend = classifyBackend(result.handledBy);
    meta.append(badge(backend.kind, backend.label, backend.kind === 'direct'
      ? 'No X-Handled-By header: the target is a worker, or a load balancer built before this header existed.'
      : 'Reported by the load balancer'));
  }
  const span = (text) => Object.assign(document.createElement('span'), { textContent: text });
  meta.append(span(`${Math.round(result.ms).toLocaleString()} ms`));
  if (result.status) meta.append(span(`HTTP ${result.status}`));
  if (result.requestId) {
    const id = document.createElement('code');
    id.textContent = result.requestId.slice(0, 8);
    id.title = result.requestId;
    meta.append(id);
  }
}

function showError(message) {
  const box = document.createElement('div');
  box.className = 'error-box';
  box.textContent = message;
  $('#result-body').replaceChildren(box);
}

function showResult(result) {
  state.view?.destroy();
  state.view = null;
  renderMeta(result);

  const raw = $('#raw-details');
  raw.hidden = !result.body && !result.raw;
  $('#raw-body').textContent = (result.raw !== result.body ? '(decoded from the Lambda JSON payload)\n\n' : '') + result.body;
  raw.open = false;

  if (!result.ok) {
    showError(result.error);
    return;
  }
  try {
    const data = PARSERS[result.game](result.body);
    state.view = RENDERERS[result.game]($('#result-body'), data, result.params);
  } catch (e) {
    showError(`Could not parse the response: ${e.message}`);
    raw.open = true;
  }
}

function showLoading(game, params) {
  state.view?.destroy();
  state.view = null;
  $('#raw-details').hidden = true;
  $('#result-meta').replaceChildren();
  const started = performance.now();
  const wrap = document.createElement('div');
  wrap.className = 'loading';
  const spinner = document.createElement('span');
  spinner.className = 'spinner';
  const text = document.createElement('span');
  wrap.append(spinner, text);
  $('#result-body').replaceChildren(wrap);
  const tick = () => {
    const seconds = (performance.now() - started) / 1000;
    text.textContent = `${GAMES[game].label} (${describeParams(game, params)}): waiting ${seconds.toFixed(1)}s`
      + (seconds > 20 ? '. The LB may be waiting for the AutoScaler to start an EC2 instance.' : '');
  };
  tick();
  return setInterval(tick, 100);
}

async function onSend() {
  if (state.inflight) return;
  const errorEl = $('#form-error');
  const base = currentTarget();
  if (!base) {
    errorEl.textContent = 'Set a valid target URL first.';
    errorEl.hidden = false;
    return;
  }
  const { params, error, field } = readParams(state.game);
  if (error) {
    errorEl.textContent = error;
    errorEl.hidden = false;
    field?.setAttribute('aria-invalid', 'true');
    field?.focus();
    return;
  }
  errorEl.hidden = true;
  writeStored(TARGET_KEY, base);

  const game = state.game;
  state.inflight = new AbortController();
  $('#send-btn').disabled = true;
  $('#cancel-btn').hidden = false;
  const timer = showLoading(game, params);

  const result = await sendRequest(base, game, params, { signal: state.inflight.signal });

  clearInterval(timer);
  state.inflight = null;
  $('#send-btn').disabled = false;
  $('#cancel-btn').hidden = true;

  state.selectedHistoryId = addResult(result);
  showResult(result);
}

/* ---------------------------------------------------------------- history */

function renderHistory(entries) {
  $('#history-count').textContent = entries.length
    ? `${entries.length} request${entries.length === 1 ? '' : 's'}. Click a row from this session to view it again.`
    : 'No requests yet.';
  const rows = entries.map((e) => {
    const tr = document.createElement('tr');
    const available = Boolean(getResult(e.id));
    if (available) tr.dataset.clickable = '';
    if (e.id === state.selectedHistoryId) tr.setAttribute('aria-current', 'true');
    tr.dataset.id = e.id;

    const backend = classifyBackend(e.handledBy);
    const cells = [
      new Date(e.time).toLocaleTimeString(),
      GAMES[e.game]?.label ?? e.game,
      describeParams(e.game, e.params),
      e.status ? badge(backend.kind, backend.label) : '—',
      `${e.ms.toLocaleString()} ms`,
      e.ok ? badge('ok', 'OK') : badge('error', e.error === 'Cancelled' ? 'Cancelled' : 'Error', e.error ?? ''),
    ];
    cells.forEach((content, i) => {
      const td = document.createElement('td');
      if (i === 2) td.className = 'params';
      if (i === 4) td.className = 'num';
      td.append(content);
      tr.append(td);
    });
    return tr;
  });
  $('#history-table tbody').replaceChildren(...rows);
}

function onHistoryClick(event) {
  const tr = event.target.closest('tr[data-clickable]');
  if (!tr) return;
  const result = getResult(tr.dataset.id);
  if (!result) return;
  state.selectedHistoryId = tr.dataset.id;
  fillForm(result.game, result.params);
  selectGame(result.game);
  showResult(result);
  for (const row of $('#history-table tbody').children) {
    if (row === tr) row.setAttribute('aria-current', 'true');
    else row.removeAttribute('aria-current');
  }
  $('.result-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/* ---------------------------------------------------------------- burst */

function renderBurst(rows) {
  const s = summarize(rows);
  const summary = $('#burst-summary');
  summary.hidden = false;

  const fmt = (ms) => (ms == null ? '—' : `${Math.round(ms).toLocaleString()} ms`);
  const statBox = (value, label) => {
    const d = document.createElement('div');
    d.className = 'stat';
    const b = document.createElement('b');
    b.textContent = value;
    const span = document.createElement('span');
    span.textContent = label;
    d.append(b, span);
    return d;
  };
  const progress = document.createElement('div');
  progress.className = 'progress';
  const bar = document.createElement('div');
  bar.style.width = `${(s.finished / s.total) * 100}%`;
  progress.append(bar);

  const backends = document.createElement('div');
  backends.className = 'meta';
  for (const b of s.byBackend) backends.append(badge(b.kind, `${b.label} × ${b.count}`));

  summary.replaceChildren(
    progress,
    statBox(`${s.finished}/${s.total}`, s.running ? `done · ${s.running} in flight` : 'done'),
    statBox(s.errors, 'errors'),
    statBox(fmt(s.p50), 'p50'),
    statBox(fmt(s.p95), 'p95'),
    statBox(fmt(s.max), 'max'),
    backends,
  );

  const now = performance.now();
  const table = $('#burst-table');
  table.hidden = false;
  table.tBodies[0].replaceChildren(...rows.map((r) => {
    const tr = document.createElement('tr');
    const stateBadge = {
      queued: badge('direct', 'queued'),
      running: badge('warn', 'running'),
      done: badge('ok', 'done'),
      error: badge('error', 'error'),
      cancelled: badge('direct', 'cancelled'),
    }[r.state];
    const backend = r.state === 'done' || r.handledBy ? badge(classifyBackend(r.handledBy).kind, classifyBackend(r.handledBy).label) : '—';
    const ms = r.state === 'running' ? now - r.startedAt : r.ms;
    const cells = [String(r.index), stateBadge, backend, ms == null ? '—' : fmt(ms), r.error && r.state === 'error' ? r.error : ''];
    cells.forEach((c, i) => {
      const td = document.createElement('td');
      if (i === 0 || i === 3) td.className = 'num';
      if (i === 4) { td.className = 'detail'; td.title = c; }
      td.append(c);
      tr.append(td);
    });
    return tr;
  }));
}

async function onBurstStart(event) {
  event.preventDefault();
  if (state.burst) return;
  const form = $('#burst-form');
  const total = Number(form.elements.total.value);
  const concurrency = Number(form.elements.concurrency.value);
  const base = currentTarget();
  const { params, error } = readParams(state.game);
  if (!base || error || !Number.isInteger(total) || total < 1 || !Number.isInteger(concurrency) || concurrency < 1) {
    $('#burst-source').textContent = !base
      ? 'Set a valid target URL first.'
      : error ?? 'Requests and concurrency must be positive integers.';
    return;
  }

  state.burst = new AbortController();
  $('#burst-start').disabled = true;
  $('#burst-cancel').hidden = false;

  let latest = [];
  let scheduled = false;
  const schedule = (rows) => {
    latest = rows;
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; renderBurst(latest); });
  };
  const ticker = setInterval(() => schedule(latest), 250); // keep running timers moving

  await runBurst({
    base, game: state.game, params, total,
    concurrency: Math.min(concurrency, total),
    signal: state.burst.signal,
    onUpdate: schedule,
  });

  clearInterval(ticker);
  renderBurst(latest);
  state.burst = null;
  $('#burst-start').disabled = false;
  $('#burst-cancel').hidden = true;
}

/* ---------------------------------------------------------------- theme */

function applyTheme(theme) {
  if (theme) document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
  state.view?.redraw();
}

function toggleTheme() {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : matchMedia('(prefers-color-scheme: dark)').matches;
  const next = dark ? 'light' : 'dark';
  writeStored(THEME_KEY, next);
  applyTheme(next);
}

/* ---------------------------------------------------------------- init */

function init() {
  $('#target-url').value = readStored(TARGET_KEY) || DEFAULT_TARGET;
  applyTheme(readStored(THEME_KEY));

  $('#target-form').addEventListener('submit', onCheckTarget);
  $('#target-url').addEventListener('input', updatePreview);
  $('#theme-toggle').addEventListener('click', toggleTheme);
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => state.view?.redraw());

  for (const tab of document.querySelectorAll('.request-panel .tab')) {
    tab.addEventListener('click', () => selectGame(tab.dataset.game));
  }
  for (const form of document.querySelectorAll('.game-form')) {
    form.addEventListener('input', updatePreview);
    form.addEventListener('change', updatePreview);
    form.addEventListener('submit', (e) => { e.preventDefault(); onSend(); });
    form.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.target.tagName === 'INPUT') { e.preventDefault(); onSend(); }
    });
  }
  $('#send-btn').addEventListener('click', onSend);
  $('#cancel-btn').addEventListener('click', () => state.inflight?.abort());

  for (const tab of document.querySelectorAll('.bottom-panel .tab')) {
    tab.addEventListener('click', () => {
      for (const t of document.querySelectorAll('.bottom-panel .tab')) t.setAttribute('aria-selected', String(t === tab));
      $('#tool-history').hidden = tab.dataset.tool !== 'history';
      $('#tool-burst').hidden = tab.dataset.tool !== 'burst';
    });
  }

  onHistoryChange(renderHistory);
  $('#history-clear').addEventListener('click', clearHistory);
  $('#history-table').addEventListener('click', onHistoryClick);

  $('#burst-form').addEventListener('submit', onBurstStart);
  $('#burst-cancel').addEventListener('click', () => state.burst?.abort());

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => state.view?.redraw(), 150);
  });

  selectGame(state.game);
}

init();
