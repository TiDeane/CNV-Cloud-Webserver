// Fires many identical requests through a fixed-size promise pool, like the
// `ab -n N -c C` calls in scripts/tests/*-load.sh.

import { sendRequest, classifyBackend } from './api.js';

export async function runBurst({ base, game, params, total, concurrency, signal, onUpdate }) {
  const rows = Array.from({ length: total }, (_, i) => ({ index: i + 1, state: 'queued' }));
  let next = 0;

  const worker = async () => {
    while (next < total && !signal.aborted) {
      const row = rows[next++];
      row.state = 'running';
      row.startedAt = performance.now();
      onUpdate(rows);
      const result = await sendRequest(base, game, params, { signal });
      row.ms = result.ms;
      row.handledBy = result.handledBy;
      row.error = result.error;
      row.state = result.ok ? 'done' : result.cancelled ? 'cancelled' : 'error';
      onUpdate(rows);
    }
  };

  await Promise.all(Array.from({ length: Math.min(concurrency, total) }, worker));
  for (const row of rows) if (row.state === 'queued') row.state = 'cancelled';
  onUpdate(rows);
  return rows;
}

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

export function summarize(rows) {
  const finished = rows.filter((r) => r.state === 'done' || r.state === 'error' || r.state === 'cancelled');
  const ok = rows.filter((r) => r.state === 'done');
  const latencies = ok.map((r) => r.ms).sort((a, b) => a - b);
  const byBackend = new Map();
  for (const r of ok) {
    const { label, kind } = classifyBackend(r.handledBy);
    const entry = byBackend.get(label) ?? { label, kind, count: 0 };
    entry.count++;
    byBackend.set(label, entry);
  }
  return {
    total: rows.length,
    finished: finished.length,
    ok: ok.length,
    errors: rows.filter((r) => r.state === 'error').length,
    cancelled: rows.filter((r) => r.state === 'cancelled').length,
    running: rows.filter((r) => r.state === 'running').length,
    p50: percentile(latencies, 50),
    p95: percentile(latencies, 95),
    max: latencies.length ? latencies[latencies.length - 1] : null,
    byBackend: [...byBackend.values()].sort((a, b) => b.count - a.count),
  };
}
