// Board renderers. Each render* call fills `container` and returns a view with
// redraw() (for theme changes) and destroy() (to stop background work).

const MAX_BOARD_CSS_PX = 520;

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else node.setAttribute(k, v);
  }
  for (const child of [].concat(children)) if (child) node.append(child);
  return node;
}

function stat(value, label, cls = '') {
  return el('div', { class: `stat ${cls}` }, [el('b', { text: String(value) }), el('span', { text: label })]);
}

function boardBlock(title, content) {
  return el('div', { class: 'board' }, [el('h3', { text: title }), content]);
}

/** Sizes the canvas backing store to whole-pixel cells; returns the cell size in device px. */
function sizeCanvas(canvas, cols, rows) {
  const dpr = window.devicePixelRatio || 1;
  const cssWidth = Math.min(MAX_BOARD_CSS_PX, canvas.parentElement?.clientWidth || 360);
  const cell = Math.max(1, Math.floor((cssWidth * dpr) / Math.max(cols, rows)));
  canvas.width = cell * cols;
  canvas.height = cell * rows;
  canvas.style.aspectRatio = `${cols} / ${rows}`;
  return cell;
}

function drawGridLines(ctx, cell, cols, rows) {
  if (cell < 10) return;
  ctx.strokeStyle = cssVar('--cell-line');
  ctx.lineWidth = 1;
  ctx.beginPath();
  for (let i = 1; i < cols; i++) { ctx.moveTo(i * cell + 0.5, 0); ctx.lineTo(i * cell + 0.5, rows * cell); }
  for (let j = 1; j < rows; j++) { ctx.moveTo(0, j * cell + 0.5); ctx.lineTo(cols * cell, j * cell + 0.5); }
  ctx.stroke();
}

/* ---------------------------------------------------------------- CTF */

function drawCtfBoard(canvas, board) {
  const n = board.length;
  const c = sizeCanvas(canvas, n, n);
  const ctx = canvas.getContext('2d');
  const color = {
    cell: cssVar('--cell'), obstacle: cssVar('--obstacle'),
    blue: cssVar('--blue'), red: cssVar('--red'),
    blueSoft: cssVar('--blue-soft'), redSoft: cssVar('--red-soft'),
  };

  ctx.fillStyle = color.cell;
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      const type = board[i][j];
      const x = j * c;
      const y = i * c;
      switch (type) {
        case 'obstacle':
          ctx.fillStyle = color.obstacle;
          ctx.fillRect(x, y, c, c);
          break;
        case 'blueTrace':
        case 'redTrace':
          ctx.fillStyle = type === 'blueTrace' ? color.blueSoft : color.redSoft;
          ctx.fillRect(x, y, c, c);
          break;
      }
    }
  }
  drawGridLines(ctx, c, n, n);

  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      const type = board[i][j];
      const x = j * c;
      const y = i * c;
      const team = type.startsWith('blue') ? color.blue : color.red;
      if (type === 'blueFlag' || type === 'redFlag') {
        ctx.fillStyle = team;
        ctx.beginPath();
        ctx.moveTo(x + c * 0.22, y + c * 0.12);
        ctx.lineTo(x + c * 0.88, y + c * 0.42);
        ctx.lineTo(x + c * 0.22, y + c * 0.72);
        ctx.closePath();
        ctx.fill();
        if (c >= 8) ctx.fillRect(x + c * 0.16, y + c * 0.12, Math.max(1, c * 0.08), c * 0.78);
      } else if (type === 'blueAgent' || type === 'redAgent') {
        ctx.fillStyle = team;
        ctx.beginPath();
        ctx.arc(x + c / 2, y + c / 2, c * 0.36, 0, Math.PI * 2);
        ctx.fill();
      } else if (type === 'blueAgentCooldown' || type === 'redAgentCooldown') {
        ctx.strokeStyle = team;
        ctx.lineWidth = Math.max(1, c * 0.12);
        ctx.beginPath();
        ctx.arc(x + c / 2, y + c / 2, c * 0.3, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
  }
}

export function renderCtf(container, data) {
  const winner = { blue: 'Blue', red: 'Red', tie: 'Tie' }[data.winner];
  const stats = el('div', { class: 'stats' }, [
    stat(data.score.blue, 'Blue flags', 'blue'),
    stat(data.score.red, 'Red flags', 'red'),
    stat(winner, 'Winner', data.winner === 'tie' ? '' : data.winner),
    stat(data.rounds ?? '—', 'Rounds'),
    stat(data.flagsPerTeam, 'Flags per team'),
  ]);

  const initialCanvas = el('canvas', { role: 'img', 'aria-label': 'Initial board' });
  const finalCanvas = el('canvas', { role: 'img', 'aria-label': 'Final board' });
  const legend = el('div', { class: 'legend' }, [
    ['lg-agent-blue', 'Blue agent'], ['lg-agent-red', 'Red agent'], ['lg-cool', 'Agent on cooldown'],
    ['lg-flag-blue', 'Flag (blue target)'], ['lg-flag-red', 'Flag (red target)'],
    ['lg-trace', 'Trail'], ['lg-obstacle', 'Obstacle'],
  ].map(([cls, label]) => el('span', {}, [el('i', { class: cls }), label])));

  container.replaceChildren(
    stats,
    el('div', { class: 'boards' }, [
      boardBlock(`Round 0 · ${data.size}×${data.size}, placement ${data.placement}`, initialCanvas),
      boardBlock(`Final · round ${data.rounds ?? '?'}`, finalCanvas),
    ]),
    legend,
  );

  const redraw = () => { drawCtfBoard(initialCanvas, data.initial); drawCtfBoard(finalCanvas, data.final); };
  redraw();
  return { redraw, destroy() {} };
}

/* ---------------------------------------------------------------- 15-Puzzle */

function puzzleGrid(board, highlightMisplaced) {
  const n = board.length;
  const grid = el('div', { class: 'puzzle', role: 'img', 'aria-label': `${n}×${n} puzzle board` });
  grid.style.gridTemplateColumns = `repeat(${n}, minmax(0, 1fr))`;
  grid.style.fontSize = `${Math.max(9, Math.round((320 / n) * 0.36))}px`;
  board.flat().forEach((v, idx) => {
    const expected = idx === n * n - 1 ? 0 : idx + 1;
    const cls = v === 0 ? 't blank' : highlightMisplaced && v !== expected ? 't off' : 't';
    grid.append(el('div', { class: cls, text: v === 0 ? '' : String(v) }));
  });
  return grid;
}

export function renderFifteen(container, data) {
  const n = data.size;
  const misplaced = data.initial.flat().filter((v, idx) => v !== 0 && v !== idx + 1).length;

  const stats = el('div', { class: 'stats' }, [
    stat(data.solvable ? data.moves : '—', data.solvable ? 'Moves (IDA*)' : 'No solution found'),
    stat(`${n}×${n}`, 'Board'),
    stat(misplaced, 'Misplaced tiles'),
  ]);

  container.replaceChildren(
    stats,
    el('div', { class: 'boards' }, [
      boardBlock('Shuffled · misplaced tiles highlighted', puzzleGrid(data.initial, true)),
      data.solved
        ? boardBlock('Solved', puzzleGrid(data.solved, false))
        : boardBlock('Solved', el('p', { class: 'muted', text: 'The solver returned no solution.' })),
    ]),
  );
  return { redraw() {}, destroy() {} };
}

/* ---------------------------------------------------------------- Game of Life */

/**
 * One generation, mirroring GameOfLife.iterate()/isAlive() exactly: neighbours
 * wrap on the flattened 1D index (not a true torus), only cells equal to 1 count
 * as live neighbours, and any non-zero cell takes the "alive" branch.
 */
export function golStep(cur, width, height) {
  const size = width * height;
  const next = new Uint8Array(size);
  for (let x = 0; x < width; x++) {
    for (let y = 0; y < height; y++) {
      const pos = y * width + x;
      let alive = 0;
      for (let col = x - 1; col <= x + 1; col++) {
        for (let row = y - 1; row <= y + 1; row++) {
          const p = (((row * width + col) % size) + size) % size;
          if (p !== pos && cur[p] === 1) alive++;
        }
      }
      next[pos] = cur[pos] === 0 ? (alive === 3 ? 1 : 0) : (alive < 2 || alive > 3 ? 0 : 1);
    }
  }
  return next;
}

function flatten(map) {
  const out = new Uint8Array(map.length * map[0].length);
  let i = 0;
  for (const row of map) for (const v of row) out[i++] = v & 0xff;
  return out;
}

function countAlive(cells) {
  let n = 0;
  for (const v of cells) if (v !== 0) n++;
  return n;
}

function rgbOf(ctx, cssColor) {
  ctx.fillStyle = cssColor;
  const hex = ctx.fillStyle; // canvas normalises opaque colours to #rrggbb
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [0, 0, 0];
}

function drawGolBoard(canvas, cells, width, height) {
  const c = sizeCanvas(canvas, width, height);
  const ctx = canvas.getContext('2d');
  const alive = rgbOf(ctx, cssVar('--alive'));
  const dead = rgbOf(ctx, cssVar('--cell'));

  const image = new ImageData(width, height);
  for (let i = 0; i < cells.length; i++) {
    const rgb = cells[i] !== 0 ? alive : dead;
    image.data[i * 4] = rgb[0];
    image.data[i * 4 + 1] = rgb[1];
    image.data[i * 4 + 2] = rgb[2];
    image.data[i * 4 + 3] = 255;
  }
  const off = typeof OffscreenCanvas === 'function'
    ? new OffscreenCanvas(width, height)
    : Object.assign(document.createElement('canvas'), { width, height });
  off.getContext('2d').putImageData(image, 0, 0);
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(off, 0, 0, canvas.width, canvas.height);
  drawGridLines(ctx, c, width, height);
}

const MAX_CHECKPOINT_BYTES = 64e6;
const MAX_CHECKPOINTS = 2000;

function createGolPlayer(data, iterations, setStatus) {
  const { width, height } = data;
  const size = width * height;
  const start = flatten(data.inputMap);
  const expected = flatten(data.outputMap);

  // Keep every k-th generation so scrubbing stays cheap without storing them all.
  const budget = Math.max(2, Math.min(MAX_CHECKPOINTS, Math.floor(MAX_CHECKPOINT_BYTES / size)));
  const k = Math.max(1, Math.ceil((iterations + 1) / budget));
  const checkpoints = new Map([[0, start]]);
  let computed = 0;
  let destroyed = false;

  const canvas = el('canvas', { role: 'img', 'aria-label': 'Replay frame' });
  const playBtn = el('button', { class: 'btn', type: 'button', text: '▶ Play' });
  const stepBtn = el('button', { class: 'btn btn-quiet', type: 'button', text: 'Step', title: 'Next generation' });
  const resetBtn = el('button', { class: 'btn btn-quiet', type: 'button', text: 'Reset', title: 'Back to generation 0' });
  const slider = el('input', { type: 'range', min: '0', max: String(iterations), value: '0', 'aria-label': 'Generation' });
  const speed = el('select', { 'aria-label': 'Speed' },
    [2, 5, 10, 30, 60].map((fps) => el('option', { value: String(fps), text: `${fps} gen/s` })));
  speed.value = '10';
  const label = el('span', { class: 'frame-label' });

  let frame = 0;
  let cells = start;
  let timer = null;

  const draw = () => {
    drawGolBoard(canvas, cells, width, height);
    slider.value = String(frame);
    label.textContent = `gen ${frame} / ${iterations}`;
  };

  const seek = (target) => {
    target = Math.max(0, Math.min(target, computed));
    const base = Math.floor(target / k) * k;
    let buf = checkpoints.get(base);
    for (let i = base; i < target; i++) buf = golStep(buf, width, height);
    frame = target;
    cells = buf;
    draw();
  };

  const advance = () => {
    if (frame >= iterations) { pause(); return; }
    if (frame >= computed) return; // wait for background replay to catch up
    cells = golStep(cells, width, height);
    frame++;
    draw();
  };

  const pause = () => {
    clearInterval(timer);
    timer = null;
    playBtn.textContent = '▶ Play';
  };
  const play = () => {
    if (frame >= iterations) seek(0);
    clearInterval(timer);
    timer = setInterval(advance, 1000 / Number(speed.value));
    playBtn.textContent = '❚❚ Pause';
  };

  playBtn.addEventListener('click', () => (timer ? pause() : play()));
  stepBtn.addEventListener('click', () => { pause(); advance(); });
  resetBtn.addEventListener('click', () => { pause(); seek(0); });
  slider.addEventListener('input', () => { pause(); seek(Number(slider.value)); });
  speed.addEventListener('change', () => { if (timer) play(); });

  // Background replay of every generation, time-sliced to keep the page responsive.
  let bgIndex = 0;
  let bgCells = start;
  const slice = () => {
    if (destroyed) return;
    const deadline = performance.now() + 12;
    while (bgIndex < iterations && performance.now() < deadline) {
      bgCells = golStep(bgCells, width, height);
      bgIndex++;
      if (bgIndex % k === 0 || bgIndex === iterations) checkpoints.set(bgIndex, bgCells);
    }
    computed = bgIndex;
    if (bgIndex < iterations) {
      setStatus('warn', `Replaying in browser… ${Math.floor((bgIndex / iterations) * 100)}%`);
      setTimeout(slice, 0);
      return;
    }
    const matches = bgCells.length === expected.length && bgCells.every((v, i) => v === expected[i]);
    setStatus(matches ? 'ok' : 'error', matches
      ? 'Browser replay matches server output ✓'
      : 'Browser replay differs from server output ✗');
  };
  slice();

  const node = el('div', { class: 'player' }, [
    el('h3', { class: 'muted', text: 'Replay (computed in the browser from the initial map)' }),
    el('div', { class: 'player-controls' }, [playBtn, stepBtn, resetBtn, speed, label]),
    slider,
    el('div', { class: 'board' }, canvas),
  ]);

  return {
    node,
    redraw: draw,
    destroy() { destroyed = true; pause(); },
  };
}

export function renderGol(container, data, params) {
  const iterations = Number(params.iterations) || 0;
  const initial = flatten(data.inputMap);
  const output = flatten(data.outputMap);
  const verify = el('span', { class: 'badge' });
  const setStatus = (kind, text) => { verify.dataset.kind = kind; verify.textContent = text; };

  const initialCanvas = el('canvas', { role: 'img', 'aria-label': 'Initial map' });
  const finalCanvas = el('canvas', { role: 'img', 'aria-label': 'Final map from server' });
  const player = createGolPlayer(data, iterations, setStatus);

  container.replaceChildren(
    el('div', { class: 'stats' }, [
      stat(`${data.width}×${data.height}`, 'Map'),
      stat(iterations, 'Generations'),
      stat(countAlive(initial), 'Alive at start'),
      stat(countAlive(output), 'Alive at end'),
    ]),
    el('div', { class: 'stats' }, verify),
    el('div', { class: 'boards' }, [
      boardBlock('Initial map', initialCanvas),
      boardBlock(`Server result · generation ${iterations}`, finalCanvas),
    ]),
    player.node,
  );

  const redraw = () => {
    drawGolBoard(initialCanvas, initial, data.width, data.height);
    drawGolBoard(finalCanvas, output, data.width, data.height);
    player.redraw();
  };
  redraw();
  return { redraw, destroy: player.destroy };
}

export const RENDERERS = {
  capturetheflag: renderCtf,
  fifteenpuzzle: renderFifteen,
  gameoflife: renderGol,
};
