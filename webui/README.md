## Web UI

A static browser console for Games@Cloud. It sends requests to the load balancer (or to a single worker), visualizes each game's result, shows whether the request was served by Lambda or EC2, keeps a request history and can fire bursts of requests to exercise load balancing and auto-scaling.

It is plain HTML/CSS/JS (ES modules). There is no build step or dependency.

> **Note:** this web UI was not part of the original university project delivery, and the [report](../final_project_report_CNV_g28.pdf) does not cover it. It was added afterwards, together with the mock load balancer (`mock_lb.py`) and the `X-Handled-By` / `X-Request-Id` response headers in the load balancer handlers.

### How to run

Serve the folder with any static file server.

```
python3 -m http.server -d webui 8080
```

Then open `http://localhost:8080` and set **Target** to:
- the load balancer: `http://<lb-ip>:8001` (default `http://localhost:8001`), or
- a single worker: `http://<worker-ip>:8000`, which bypasses the LB.

All game handlers already send `Access-Control-Allow-Origin: *`, so no proxy is needed.

### Testing the UI without AWS or Java

`mock_lb.py` is a simulated load balancer that needs only the standard Python library. It serves the UI itself and answers the three game endpoints with the same response formats as the real system:

```
python3 webui/mock_lb.py
```

Then open `http://localhost:8001/ui/`. When the UI is served from `/ui/`, it targets that server by default.

What it simulates:
- **Games:** simplified Python versions of the three workloads, with output formatted exactly like the Java handlers. Game of Life follows the Java rules exactly; Capture the Flag and the 15-Puzzle are close but not identical. The puzzle reports its shuffle length as the move count instead of running IDA*.
- **Routing:** cheap requests go to "Lambda", whose responses are JSON-encoded strings with a cold start on the first call per game. Expensive ones go to a small "EC2" pool that boots instances on demand, holding requests while an instance starts, just like the real LB.
- **Errors:** invalid CTF input, missing Game of Life maps, deadlocked simulations and, optionally, failing Lambda attempts with retries and EC2 fallback.

Extra Game of Life maps: besides the files in `gameoflife/src/main/resources`, the mock accepts `random-<width>-<height>-<seed>.json`, for example `random-80-50-3.json`.

Useful options (`--help` lists all of them):

| Option | Default | Effect |
| --- | --- | --- |
| `--speed` | `1` | Multiplier for simulated run times; `0` answers instantly |
| `--boot-seconds` | `8` | Simulated EC2 boot time |
| `--max-instances` | `3` | EC2 pool limit |
| `--lambda-threshold` | `20000` | Cost below which requests go to Lambda |
| `--lambda-fail-rate` | `0` | Probability that a Lambda attempt fails (exercises retries) |
| `--port` / `--host` | `8001` / `127.0.0.1` | Listen address |

### Features

- **Capture the Flag**: the round-0 and final boards (agents, cooldowns, flags, trails, obstacles), score, winner and rounds. A deadlocked simulation returns only `no possible moves`, which is shown as an error.
- **15-Puzzle**: the shuffled board with misplaced tiles highlighted, the solved board, and the IDA* move count.
- **Game of Life**: the initial map and the server's final map, plus a replay player that recomputes every generation in the browser and checks that the last one matches the server's `outputMap`.
- **Served by**: the LB sets `X-Handled-By` (`lambda` or `ec2:<instance-id>`) and `X-Request-Id`. Without that header (e.g. when targeting a worker) the UI shows *Worker (direct)*.
- **History**: the latest 200 requests. Metadata is kept in `localStorage`; responses from the current session can be reopened.
- **Burst test**: sends N copies of the current request with concurrency C (like `scripts/tests/*-load.sh`) and reports a per-backend breakdown and p50/p95/max latency. Browsers allow about 6 connections per host, so higher concurrency is queued in the browser.

### Notes

- The game output is deliberately unchanged. The Javassist agent counts every instruction, so emitting extra frames would distort the metrics stored in `CNVRequestMetrics`. That is why step-by-step Game of Life is replayed client-side.
- Lambda responses reach the LB as a JSON-encoded string. The UI decodes this, so both paths render the same way.
- Requests routed to EC2 can wait minutes while the AutoScaler boots an instance. The UI enforces no timeout; use **Cancel** instead.
