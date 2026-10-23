## Web UI

A static browser console for Games@Cloud. It sends requests to the load balancer (or to a single worker), visualizes each game's result, shows whether the request was served by Lambda or EC2, keeps a request history and can fire bursts of requests to exercise load balancing and auto-scaling.

It is plain HTML/CSS/JS (ES modules). There is no build step or dependency.

### How to run

Serve the folder with any static file server. Opening `index.html` via `file://` blocks ES modules in some browsers.

```
python3 -m http.server -d webui 8080
```

Then open `http://localhost:8080` and set **Target** to:
- the load balancer: `http://<lb-ip>:8001` (default `http://localhost:8001`), or
- a single worker: `http://<worker-ip>:8000`, which bypasses the LB.

All game handlers already send `Access-Control-Allow-Origin: *`, so no proxy is needed.

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
