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
docker compose up --build
```

On Apple Silicon / ARM hosts the images build for `linux/amd64` (required — GLIDE only
publishes `linux-x86_64` native artefacts).

## Expected output

T+0 to ~15 s: silent warm-up (the app is SETing 1000 keys).

T+15 to ~60 s (steady state):

```
[MONITOR] gets=1500(+150/s) errs=0 blocking=40 pool=50 queued=200 threads=80 avg=15.0ms
```

T+1–3 min (growth begins):

```
[MONITOR] gets=12000(+100/s) errs=400 blocking=280 pool=400 queued=180000 threads=440 avg=950.0ms
[MONITOR] gets=15000(+40/s)  errs=1800 blocking=300 pool=650 queued=500000 threads=700 avg=3000.0ms
```

T+5 min (stuck):

```
[STUCK] gets=52000(+0/s) errs=12000 blocking=725 pool=1100 queued=2000000 threads=1150 avg=14000.0ms
```

With `requestTimeout=30ms` configured, seeing `avg=14 000 ms` means the
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
limit / 500 req/s × avg 8.5 fanout) fit a small Docker VM and reproduce within a few
minutes.

In `Repro.java`:

| `nsPerRequest` value | Incoming req/s | Outgoing GETs/s | Notes |
|---|---|---|---|
| `10_000_000` | 100 | ~850 | warm-up, won't trigger |
| `2_000_000` (default) | 500 | ~4 250 | triggers in 2–5 min |
| `1_000_000` | 1 000 | ~8 500 | triggers in <1 min |

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
