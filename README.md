# Valkey-GLIDE — minimal reproduction of thread-hang under off-heap pressure

Self-contained docker-compose reproduction of a GLIDE 2.2.x hang where Java
`CompletableFuture`s returned by the client stop being completed under memory and
scheduling pressure. No network manipulation required — the trigger is purely a squeezed
off-heap memory budget + concurrent load + `ForkJoinPool.managedBlock()` wrapping a
blocking `future.get()`.

## Layout

```
repro-docker/
├── docker-compose.yml     # valkey (single-node cluster) + Java app
├── Dockerfile             # gradle build → corretto 11.0.18 runtime
├── build.gradle           # GLIDE 2.2.9 + Lettuce 6.5.5 (idle)
├── settings.gradle
└── src/main/java/repro/Repro.java   # ~120 lines total
```

## Run

```bash
cd repro-docker
docker compose up --build              # default — runs GLIDE client
docker compose --profile lettuce up --build app-lettuce valkey   # Lettuce equivalent
```

Both services use the exact same harness (same `ForkJoinPool.managedBlock`, same blocking
`future.get()`, same 500 req/s pacing, same 1–16 GET fan-out) — only the underlying
client implementation differs. The goal is to A/B compare the two under identical load
and memory pressure.

Selector is controlled by the `CLIENT` environment variable (`glide` or `lettuce`).

On Apple Silicon / ARM hosts the images build for `linux/amd64` (required — GLIDE only
publishes `linux-x86_64` native artefacts).

### GLIDE vs Lettuce — what differs

Both services run an identical workload. Expected observations:

- **GLIDE (`app`)** — thread pool grows unbounded, `[STUCK]` tag fires within minutes,
  `avg` latency drifts past the configured 30 ms timeout into the thousands of ms. The
  `CompletableFuture` is never completed by the native callback.
- **Lettuce (`app-lettuce`)** — on the same hardware and memory budget the workload
  should either sustain throughput or fail fast with `RedisCommandTimeoutException` at
  ~30 ms. Thread pool should stay near 50.

Running both gives a controlled before/after demonstrating that the hang is specific to
the GLIDE JNI callback path, not to the general "500 req/s with blocking `get()`"
pattern.

## Expected output

T+0 to ~15 s: silent warm-up (the app is SETing 1000 keys).

At the default rate (~85 GETs/s, fits in 2 CPU / 1.4 GiB cgroup), **both clients** run
cleanly — this is the baseline for fair comparison:

```
[MONITOR] gets=1500(+85/s) errs=0 blocking=0 pool=50 queued=0 threads=58 avg=3.5ms
[MONITOR] gets=3400(+91/s) errs=0 blocking=0 pool=50 queued=0 threads=58 avg=2.9ms
```

To observe the GLIDE thread-hang under memory pressure, raise the rate and the cgroup
limit together (see the Tuning section below). With enough load and off-heap pressure
GLIDE's pool grows unbounded and the `[STUCK]` tag fires:

```
[MONITOR] gets=12000(+100/s) errs=400  blocking=280 pool=400  queued=180000  threads=440  avg=950.0ms
[STUCK]   gets=52000(+0/s)  errs=12000 blocking=725 pool=1100 queued=2000000 threads=1150 avg=14000.0ms
```

With `requestTimeout=30ms` configured on GLIDE, seeing `avg=14 000 ms` means the
`CompletableFuture` isn't being completed by the Rust core and Java's `future.get()`
parks effectively forever. The detector tags `[STUCK]` when 300+ threads are blocked and
zero GETs completed in the 5-second monitor window.

## Column legend

| Field | Meaning |
|---|---|
| `gets` | total completed GETs since start |
| `(+N/s)` | completed GETs in the last 5 s window, divided by 5 |
| `errs` | total `TimeoutException` count |
| `blocking` | threads currently parked inside `future.get()` |
| `pool` | `ForkJoinPool.getPoolSize()` — grows when `managedBlock` spawns compensation threads |
| `queued` | `ForkJoinPool.getQueuedSubmissionCount()` — backlog of unstarted tasks |
| `threads` | total live JVM threads (`Thread.activeCount()`) |
| `avg` | mean latency in ms for GETs that actually completed in the window |

## Tuning the trigger

The trigger is the **ratio** of off-heap budget to in-flight command pressure, not the
absolute size. The defaults (1 GiB heap / 96 MiB MaxDirectMemorySize / 1.4 GiB cgroup
limit, 2 CPUs, `nsPerRequest=100_000_000L` → 10 req/s × ~8.5 fanout ≈ 85 GETs/s) fit a
small Docker VM and give a **healthy baseline on both clients** for comparison.

To observe the GLIDE thread-hang specifically, raise the pressure proportionally — give
the container more CPUs/RAM and lower `nsPerRequest`.

In `Repro.java`:

| `nsPerRequest` value | Incoming req/s | Outgoing GETs/s | Notes |
|---|---|---|---|
| `100_000_000` (default) | 10 | ~85 | healthy on both clients, fair baseline |
| `10_000_000` | 100 | ~850 | needs ≥4 CPU; GLIDE begins to drift |
| `2_000_000` | 500 | ~4 250 | high-load; needs ≥7 CPU + 8 GiB to avoid thread-limit OOM |
| `1_000_000` | 1 000 | ~8 500 | maximum pressure; thread-hang in <1 min under off-heap squeeze |

In `Dockerfile` / `docker-compose.yml`:

- Bigger heap + proportional direct-memory cap + cgroup `memory` limit that leaves
  ~500–700 MiB for off-heap = same trigger, just at larger scale.
- If the JVM fails to start with `Not enough space (errno=12)`, your Docker VM
  (Settings → Resources → Memory) has less RAM than the `-Xmx` value; lower both heap
  and cgroup limit proportionally.

## Stop and clean up

```
Ctrl+C
docker compose down
```

## What is deliberately stripped

To keep the setup minimal, the following are intentionally omitted — none are required
to trigger the bug:

- TLS (`useInsecureTLS=true` production configs reproduce the same bug; TLS is unrelated)
- Multi-shard cluster (single-node cluster is sufficient; the topology-refresh
  amplification is orthogonal)
- Separate reader + writer GLIDE clients (one is enough)
- Any higher-level HTTP service layer

## What is kept because it **is** required

- `amazoncorretto:11.0.18-al2` — different JDK patch versions behave differently
- `io.valkey:valkey-glide:2.2.9:linux-x86_64` (2.2.7 and 2.2.10-rc2-debug reproduce too)
- `requestTimeout=30ms`, `connectionTimeout=1000ms`, `periodicChecks=60s`
- `-Xms=-Xmx` + `-XX:MaxDirectMemorySize=…` + cgroup `memory` limit squeezed so the
  residual off-heap budget is under ~1 GiB
- G1 + `UseStringDeduplication`
- Idle Lettuce client holding Netty direct-memory buffers
- `ForkJoinPool.managedBlock()` wrapping the blocking GET — this is the thread-explosion
  amplifier
- `future.get()` with **no timeout** — the final link. Using `get(N, MS)` would still
  leave abandoned futures in the Rust command table but would prevent the unbounded Java
  thread growth.

## License

MIT.
