// Turns the games' text/JSON responses into plain data for the renderers.
// Formats come from Simulation/Grid (CTF), FifteenPuzzle and GameOfLifeHandler.

const ANSI = /\u001b\[[0-9;]*m/g;

// Two-character cell tokens printed by Grid.printBoard (Grid.java).
export const CTF_CELLS = {
  '. ': 'empty',
  '# ': 'obstacle',
  'B ': 'blueAgent',
  'B*': 'blueAgentCooldown',
  'X ': 'blueFlag',   // flag captured by blue agents
  'b ': 'blueTrace',
  'R ': 'redAgent',
  'R*': 'redAgentCooldown',
  'Y ': 'redFlag',    // flag captured by red agents
  'r ': 'redTrace',
};

function parseCtfBoardLine(line, size) {
  const padded = line.length === 2 * size - 1 ? line + ' ' : line;
  if (padded.length !== 2 * size) return null;
  const row = new Array(size);
  for (let i = 0; i < size; i++) {
    const cell = CTF_CELLS[padded.slice(2 * i, 2 * i + 2)];
    if (!cell) return null;
    row[i] = cell;
  }
  return row;
}

export function parseCtf(text) {
  const lines = text.replace(ANSI, '').split(/\r?\n/);

  const header = text.match(/grid_size = (\d+); flag_placement_type = (\w)/);
  const agents = text.match(/num_blue_agents = (\d+); num_red_agents = (\d+)/);
  const final = text.match(/final score \(blue, red\) = \((\d+), (\d+)\)/);
  const rounds = text.match(/num rounds = (\d+)/);
  if (!header || !final) throw new Error('Unrecognised Capture the Flag response');

  const size = Number(header[1]);

  // Boards are runs of `size` consecutive cell rows; first = round 0, last = final.
  const boards = [];
  let current = [];
  for (const line of lines) {
    const row = parseCtfBoardLine(line, size);
    if (row) {
      current.push(row);
      if (current.length === size) { boards.push(current); current = []; }
    } else {
      current = [];
    }
  }
  if (boards.length < 2) throw new Error('Could not find both boards in the response');

  const blue = Number(final[1]);
  const red = Number(final[2]);
  const flagsPerTeam = Math.floor(size / 2);
  return {
    size,
    placement: header[2],
    numBlueAgents: agents ? Number(agents[1]) : null,
    numRedAgents: agents ? Number(agents[2]) : null,
    flagsPerTeam,
    rounds: rounds ? Number(rounds[1]) : null,
    score: { blue, red },
    winner: blue === red ? 'tie' : blue > red ? 'blue' : 'red',
    initial: boards[0],
    final: boards[boards.length - 1],
  };
}

/** Reads the `| 1 | 2 |   |` table that follows `title`. */
function parsePuzzleTable(lines, title) {
  const start = lines.findIndex((l) => l.trim() === title);
  if (start < 0) return null;
  const rows = [];
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith('|')) {
      rows.push(line.split('|').slice(1, -1).map((s) => (s.trim() === '' ? 0 : Number(s.trim()))));
    } else if (rows.length > 0 && !line.startsWith('-')) {
      break;
    }
  }
  return rows.length ? rows : null;
}

export function parseFifteen(text) {
  const lines = text.split(/\r?\n/);
  const initial = parsePuzzleTable(lines, 'Initial (Shuffled) Board:');
  if (!initial) throw new Error('Unrecognised 15-Puzzle response');
  const solved = parsePuzzleTable(lines, 'Final (Solved) Board:');
  const moves = text.match(/Solution found in (\d+) moves/);
  return {
    size: initial.length,
    initial,
    solved,
    moves: moves ? Number(moves[1]) : null,
    solvable: Boolean(moves),
  };
}

export function parseGol(text) {
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error('Game of Life response is not valid JSON');
  }
  const valid = (m) => Array.isArray(m) && m.length > 0 && m.every((r) => Array.isArray(r) && r.length === m[0].length);
  if (!valid(data.inputMap) || !valid(data.outputMap)) throw new Error('Game of Life response is missing inputMap/outputMap');
  return {
    height: data.inputMap.length,
    width: data.inputMap[0].length,
    inputMap: data.inputMap,
    outputMap: data.outputMap,
  };
}

export const PARSERS = {
  capturetheflag: parseCtf,
  fifteenpuzzle: parseFifteen,
  gameoflife: parseGol,
};
