#!/usr/bin/env python3
"""Simulated Games@Cloud load balancer for testing the web UI without Java or AWS.

Standard library only. Serves the UI at /ui/ and the three game endpoints with
the same response formats as the real LB (including Lambda's JSON-string
payloads and the X-Handled-By header). The games are simplified Python
re-implementations, so results are plausible but not identical to the Java ones.

    python3 webui/mock_lb.py            # then open http://localhost:8001/ui/
"""

import argparse
import collections
import http.server
import json
import random
import threading
import time
import urllib.parse
import uuid
from pathlib import Path

WEBUI_DIR = Path(__file__).resolve().parent
GOL_MAPS_DIR = WEBUI_DIR.parent / 'gameoflife' / 'src' / 'main' / 'resources'
INVALID_CTF = ('Invalid input. Please provide a valid grid size, number of blue agents, '
               'number of red agents and flag placement type (A, B or C).')


class DeadlockError(Exception):
    pass


# --------------------------------------------------------------------------- Capture the Flag

def ctf_workload(size, n_blue, n_red, placement):
    """Same output format as Simulation.getData(); simplified movement rules."""
    rnd = random.Random(size)  # Grid seeds its Random with the grid size
    cells = [['. '] * size for _ in range(size)]
    flags_per_team = size // 2

    def empty(x, y):
        return cells[x][y] == '. '

    # Symmetric obstacles, like Grid.spawnObstacles()
    half = ((size * size // 10) & ~1) // 2
    placed = 0
    while placed < half:
        x, y = rnd.randrange(size), rnd.randrange(size)
        ym = size - 1 - y
        if 0 < x < size - 1 and empty(x, y) and empty(x, ym):
            cells[x][y] = cells[x][ym] = '# '
            placed += 1

    flags = {'blue': set(), 'red': set()}

    def around(x, y):
        return [(x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)
                if (dx or dy) and 0 <= x + dx < size and 0 <= y + dy < size]

    def valid_flag_spot(x, y):  # Grid.isValidFlagSpot: empty, no flag in the 8 neighbours
        return empty(x, y) and all(cells[nx][ny] not in ('X ', 'Y ') for nx, ny in around(x, y))

    def place_flag(x, y, team):  # Grid.placeFlag + clearSurroundings
        cells[x][y] = 'X ' if team == 'blue' else 'Y '
        flags[team].add((x, y))
        for nx, ny in around(x, y):
            if cells[nx][ny] == '# ':
                cells[nx][ny] = '. '

    if placement == 'A':  # red on the top row, blue on the bottom row
        for i in range(flags_per_team):
            place_flag(0, i * size // flags_per_team, 'red')
            place_flag(size - 1, i * size // flags_per_team, 'blue')
    elif placement == 'B':  # alternate outwards from the centre
        queue, seen, red_next = collections.deque([(size // 2, size // 2)]), {(size // 2, size // 2)}, True
        while queue and (len(flags['red']) < flags_per_team or len(flags['blue']) < flags_per_team):
            x, y = queue.popleft()
            if valid_flag_spot(x, y):
                team = 'red' if red_next else 'blue'
                if len(flags[team]) >= flags_per_team:
                    team = 'blue' if team == 'red' else 'red'
                place_flag(x, y, team)
                red_next = not red_next
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < size and 0 <= ny < size and (nx, ny) not in seen:
                    seen.add((nx, ny))
                    queue.append((nx, ny))
    else:  # C: scattered
        for team in ('red', 'blue'):
            while len(flags[team]) < flags_per_team:
                x, y = rnd.randrange(size), rnd.randrange(size)
                if valid_flag_spot(x, y):
                    place_flag(x, y, team)

    agents = []  # [team, x, y, cooldown]

    def spawn(team, count, col):
        queue, seen = collections.deque([(size // 2, col)]), {(size // 2, col)}
        while queue and count > 0:
            x, y = queue.popleft()
            if empty(x, y):
                cells[x][y] = 'B ' if team == 'blue' else 'R '
                agents.append([team, x, y, 0])
                count -= 1
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < size and 0 <= ny < size and (nx, ny) not in seen:
                    seen.add((nx, ny))
                    queue.append((nx, ny))

    spawn('blue', n_blue, 0)
    spawn('red', n_red, size - 1)

    out = [f'[DEBUG] grid_size = {size}; flag_placement_type = {placement}\n',
           f'[DEBUG] num_blue_agents = {n_blue}; num_red_agents = {n_red}\n', '\n']
    score = {'blue': 0, 'red': 0}

    def board():
        return ''.join(''.join(row) + '\n' for row in cells)

    def distance_field(team):
        """Multi-source BFS from the team's remaining flags; agents don't block paths."""
        own_flag, other_flag = ('X ', 'Y ') if team == 'blue' else ('Y ', 'X ')
        dist = [[None] * size for _ in range(size)]
        queue = collections.deque(flags[team])
        for x, y in flags[team]:
            dist[x][y] = 0
        while queue:
            x, y = queue.popleft()
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < size and 0 <= ny < size and dist[nx][ny] is None \
                        and cells[nx][ny] not in ('# ', other_flag):
                    dist[nx][ny] = dist[x][y] + 1
                    queue.append((nx, ny))
        return dist

    out.append(f'[INFO] round = 0; score (blue, red) = (0, 0)\n')
    out.append(board())
    out.append('\n')

    round_no = 0
    max_rounds = 2 * size * size + 100  # one agent may need ~size/2 trips of ~size steps
    while True:
        round_no += 1
        moved = False
        for team in ('blue', 'red'):
            field = distance_field(team)
            for agent in (a for a in agents if a[0] == team):
                _, x, y, cooldown = agent
                symbol = 'B ' if team == 'blue' else 'R '
                if cooldown > 0:  # like Simulation.checkCooldown: may move on the tick it reaches 0
                    agent[3] -= 1
                    if agent[3] > 0:
                        continue
                    cells[x][y] = symbol
                if not flags[team] or field[x][y] is None:
                    continue
                # Step closer if possible; otherwise sidestep (then back off) around blocking agents.
                free = [(nx, ny) for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1))
                        if 0 <= nx < size and 0 <= ny < size and field[nx][ny] is not None
                        and cells[nx][ny][0] not in 'BR']
                options = ([n for n in free if field[n[0]][n[1]] < field[x][y]]
                           or [n for n in free if field[n[0]][n[1]] == field[x][y]]
                           or free)
                if not options:
                    continue
                nx, ny = rnd.choice(options)
                cells[x][y] = 'b ' if team == 'blue' else 'r '
                agent[1], agent[2] = nx, ny
                moved = True
                if (nx, ny) in flags[team]:
                    flags[team].discard((nx, ny))
                    score[team] += 1
                    agent[3] = 5  # Agent.CAPTURE_COOLDOWN
                    cells[nx][ny] = symbol[0] + '*'
                    field = distance_field(team)
                else:
                    cells[nx][ny] = symbol
        if not moved and not any(a[3] > 0 for a in agents):
            raise DeadlockError('no possible moves')
        if score['blue'] == flags_per_team or score['red'] == flags_per_team:
            break
        if round_no >= max_rounds:
            raise DeadlockError('no possible moves')

    out.append(board())
    out.append(f'[INFO] final score (blue, red) = ({score["blue"]}, {score["red"]})\n')
    out.append(f'[INFO] num rounds = {round_no}\n')
    return ''.join(out)


# --------------------------------------------------------------------------- 15-Puzzle

def fifteen_workload(size, shuffles):
    """Same output format as FifteenPuzzleHandler; the move count is the shuffle walk, not an IDA* optimum."""
    rnd = random.Random(f'42-{size}-{shuffles}')
    tiles = list(range(1, size * size)) + [0]
    blank, prev = size * size - 1, None
    opposite = {'up': 'down', 'down': 'up', 'left': 'right', 'right': 'left'}
    for _ in range(shuffles):
        row, col = divmod(blank, size)
        valid = [d for d, ok in (('up', row > 0), ('down', row < size - 1),
                                 ('left', col > 0), ('right', col < size - 1)) if ok and opposite[d] != prev]
        d = rnd.choice(valid)
        new = blank + {'up': -size, 'down': size, 'left': -1, 'right': 1}[d]
        tiles[blank], tiles[new] = tiles[new], 0
        blank, prev = new, d

    width = len(str(size * size))

    def data(board):
        line = '-' * (size * (width + 3) + 1) + '\n'
        rows = ''.join('| ' + ''.join(('' if v == 0 else str(v)).rjust(width) + ' | '
                                      for v in board[r * size:(r + 1) * size]) + '\n' for r in range(size))
        return line + rows + line

    solved = list(range(1, size * size)) + [0]
    return ('\nInitial (Shuffled) Board:\n' + data(tiles) + '\n'
            + '\nFinal (Solved) Board:\n' + data(solved) + '\n'
            + f'\nSolution found in {shuffles} moves.' + '\n')


# --------------------------------------------------------------------------- Game of Life

def load_gol_map(name):
    """Maps from gameoflife/src/main/resources, plus generated random-<w>-<h>-<seed>.json."""
    if name.startswith('random-') and name.endswith('.json'):
        try:
            w, h, seed = (int(p) for p in name[len('random-'):-len('.json')].split('-'))
        except ValueError:
            return None
        if not (0 < w <= 500 and 0 < h <= 500):
            return None
        rnd = random.Random(seed)
        return [[1 if rnd.random() < 0.3 else 0 for _ in range(w)] for _ in range(h)]
    path = GOL_MAPS_DIR / name
    if '/' in name or '\\' in name or not path.is_file():
        return None
    return json.loads(path.read_text())['map']


def gol_workload(input_map, iterations):
    """Same rules as GameOfLife.java, including the 1D-index edge wrapping."""
    h, w = len(input_map), len(input_map[0])
    size = w * h
    data = [v & 0xff for row in input_map for v in row]
    offsets = [(dc, dr) for dc in (-1, 0, 1) for dr in (-1, 0, 1)]
    for _ in range(iterations):
        nxt = [0] * size
        for x in range(w):
            for y in range(h):
                pos = y * w + x
                alive = 0
                for dc, dr in offsets:
                    p = ((y + dr) * w + (x + dc)) % size
                    if p != pos and data[p] == 1:
                        alive += 1
                if data[pos] == 0:
                    nxt[pos] = 1 if alive == 3 else 0
                else:
                    nxt[pos] = 0 if alive < 2 or alive > 3 else 1
        data = nxt
    output = [data[r * w:(r + 1) * w] for r in range(h)]
    return json.dumps({'inputMap': input_map, 'outputMap': output}, separators=(',', ':'))


# --------------------------------------------------------------------------- simulated cluster

class Cluster:
    """Lambda vs EC2 routing with a small auto-scaling EC2 pool."""

    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.instances = {}  # id -> {'ready_at': t, 'active': n}
        self.warm_lambdas = set()

    def _new_instance(self):
        iid = 'i-0mock' + uuid.uuid4().hex[:10]
        self.instances[iid] = {'ready_at': time.monotonic() + self.args.boot_seconds, 'active': 0}
        print(f'[mock] AutoScaler: launching {iid} (ready in {self.args.boot_seconds:g}s)', flush=True)
        return iid

    def acquire_ec2(self):
        """Lowest-load ready instance; scales up when every ready instance is busy."""
        with self.lock:
            now = time.monotonic()
            ready = [i for i, s in self.instances.items() if s['ready_at'] <= now]
            booting = [i for i, s in self.instances.items() if s['ready_at'] > now]
            busy = all(self.instances[i]['active'] >= self.args.per_instance for i in ready)
            if (not ready or busy) and not booting and len(self.instances) < self.args.max_instances:
                booting.append(self._new_instance())
            if ready and not (busy and booting):
                iid = min(ready, key=lambda i: self.instances[i]['active'])
            else:  # wait for the instance that boots first, like the LB holding the request
                iid = min(booting or ready, key=lambda i: self.instances[i]['ready_at'])
            self.instances[iid]['active'] += 1
            wait = max(0.0, self.instances[iid]['ready_at'] - now)
        return iid, wait

    def release_ec2(self, iid):
        with self.lock:
            self.instances[iid]['active'] -= 1

    def lambda_cold_start(self, game):
        with self.lock:
            cold = game not in self.warm_lambdas
            self.warm_lambdas.add(game)
        return 0.8 if cold else 0.0


COST = {
    # rough "complexity" used for routing and simulated run time
    'capturetheflag': lambda p: int(p['gridSize']) ** 2 * (int(p['numBlueAgents']) + int(p['numRedAgents']) + 1),
    'fifteenpuzzle': lambda p: int(p['size']) ** 2 * int(p['shuffles']) ** 1.6,
    'gameoflife': lambda p: 100 * max(1, int(p['iterations'])),
}


def run_game(game, params):
    """Returns (body, status) exactly as the worker's handleWorkload would."""
    try:
        if game == 'capturetheflag':
            size, blue, red = int(params['gridSize']), int(params['numBlueAgents']), int(params['numRedAgents'])
            placement = params['flagPlacementType'].upper()[:1]
            if size < 10 or blue > size or red > size or placement not in ('A', 'B', 'C'):
                return INVALID_CTF, 400
            try:
                return ctf_workload(size, blue, red, placement), 200
            except DeadlockError as e:
                return str(e), 200
        if game == 'fifteenpuzzle':
            return fifteen_workload(int(params['size']), int(params['shuffles'])), 200
        input_map = load_gol_map(params['mapFilename'])
        if input_map is None:
            return '{ "error":"null"}', 400
        return gol_workload(input_map, int(params['iterations'])), 200
    except (KeyError, ValueError) as e:
        return f'Bad request: {e}', 400


class Handler(http.server.SimpleHTTPRequestHandler):
    cluster = None
    args = None
    # Windows can map .js to text/plain via the registry, which breaks ES modules.
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html'}

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(WEBUI_DIR), **kw)

    def log_message(self, fmt, *a):
        if self.args.verbose:
            super().log_message(fmt, *a)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type,Authorization')
        self.end_headers()

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        game = url.path.strip('/')
        if game in COST:
            return self.handle_game(game, dict(urllib.parse.parse_qsl(url.query)))
        if url.path == '/':
            return self.send_text(200, 'Default Load Balancer server (mock)<br>')
        if url.path == '/ui':
            self.send_response(301)
            self.send_header('Location', '/ui/')
            return self.end_headers()
        if url.path.startswith('/ui/'):
            self.path = self.path[len('/ui'):]
            return super().do_GET()
        return self.send_text(404, 'Not found')

    def send_text(self, status, body, headers=None):
        data = body.encode()
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_game(self, game, params):
        args, cluster = self.args, self.cluster
        request_id = str(uuid.uuid4())
        started = time.monotonic()
        try:
            cost = COST[game](params)
        except (KeyError, ValueError):
            cost = 0
        # Simulated execution time grows with cost, capped so the mock stays usable.
        exec_s = min(args.max_exec, 0.05 + cost / args.cost_per_second) * args.speed

        body, _ = run_game(game, params)
        exec_s = max(0.0, exec_s - (time.monotonic() - started))  # computing the result already took time
        handled_by = None

        if cost < args.lambda_threshold:
            for attempt in range(3):  # Constants.RETRIES
                time.sleep(cluster.lambda_cold_start(game) + exec_s)
                if random.random() >= args.lambda_fail_rate:
                    handled_by = 'lambda'
                    break
                print(f'[mock] {game} {request_id[:8]}: Lambda attempt {attempt} failed', flush=True)

        if handled_by is None:
            iid, wait = cluster.acquire_ec2()
            try:
                if wait:
                    print(f'[mock] {game} {request_id[:8]}: no ready instance, waiting {wait:.1f}s for {iid}', flush=True)
                time.sleep(wait + exec_s)
            finally:
                cluster.release_ec2(iid)
            handled_by = 'ec2:' + iid

        # Lambda's String result reaches the LB JSON-encoded; EC2 bodies are relayed verbatim.
        payload = json.dumps(body) if handled_by == 'lambda' else body
        print(f'[mock] {game} {params} -> {handled_by} in {time.monotonic() - started:.2f}s', flush=True)
        # The LB always answers 200 and relays whatever the backend returned.
        self.send_text(200, payload, {
            'X-Handled-By': handled_by,
            'X-Request-Id': request_id,
            'Access-Control-Expose-Headers': 'X-Handled-By, X-Request-Id',
        })


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--host', default='127.0.0.1', help='bind address (default 127.0.0.1)')
    p.add_argument('--port', type=int, default=8001, help='port (default 8001, like the real LB)')
    p.add_argument('--speed', type=float, default=1.0, help='multiplier for simulated run times (0 = instant)')
    p.add_argument('--lambda-threshold', type=float, default=20000, help='cost below which requests go to Lambda')
    p.add_argument('--cost-per-second', type=float, default=40000, help='cost units simulated per second')
    p.add_argument('--max-exec', type=float, default=15.0, help='cap on simulated run time, seconds')
    p.add_argument('--boot-seconds', type=float, default=8.0, help='simulated EC2 boot time')
    p.add_argument('--max-instances', type=int, default=3, help='EC2 pool size limit')
    p.add_argument('--per-instance', type=int, default=2, help='concurrent requests before scaling up')
    p.add_argument('--lambda-fail-rate', type=float, default=0.0, help='probability a Lambda attempt fails (0..1)')
    p.add_argument('--verbose', action='store_true', help='log every HTTP request')
    args = p.parse_args()

    Handler.args = args
    Handler.cluster = Cluster(args)
    server = http.server.ThreadingHTTPServer((args.host, args.port), Handler)
    print(f'Mock Games@Cloud LB on http://{args.host}:{args.port}  (UI: http://localhost:{args.port}/ui/)', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
