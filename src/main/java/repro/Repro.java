package repro;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import glide.api.GlideClusterClient;
import glide.api.models.configuration.AdvancedGlideClusterClientConfiguration;
import glide.api.models.configuration.GlideClusterClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.PeriodicChecksManualInterval;
import glide.api.models.configuration.ReadFrom;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
/** Minimal reproduction of a client-future thread-hang. Two client implementations are
 *  selectable via env var {@code CLIENT}: {@code glide} (default) or {@code lettuce}.
 *  The rest of the harness — ForkJoinPool.managedBlock + future.get() + 500 req/s —
 *  is identical. */
public class Repro {
  static final AtomicLong totalGets = new AtomicLong();
  static final AtomicLong totalErrs = new AtomicLong();
  static final AtomicInteger blocking = new AtomicInteger();
  static final AtomicLong latencySumNs = new AtomicLong();
  static final AtomicLong latencySamples = new AtomicLong();

  /** Minimal shim so the request loop doesn't care which client is behind it. */
  interface Client { String get(String key) throws Exception; void set(String key, String value) throws Exception; }

  public static void main(String[] args) throws Exception {
    String host = args[0]; int port = Integer.parseInt(args[1]);
    String which = System.getenv().getOrDefault("CLIENT", "glide").toLowerCase();
    System.out.printf("[INIT] client=%s host=%s port=%d%n", which, host, port);

    Client client = null;
    for (int attempt = 1; attempt <= 30 && client == null; attempt++) {
      try { client = "lettuce".equals(which) ? newLettuce(host, port) : newGlide(host, port); }
      catch (Exception e) { System.out.println("waiting for valkey cluster... attempt " + attempt); Thread.sleep(2000); }
    }
    if (client == null) throw new IllegalStateException("cannot connect to " + host + ":" + port);
    final Client c = client;

    // idle Lettuce parity client — only needed when the main client is GLIDE (holds
    // direct-memory Netty buffers for off-heap parity). Skipped when CLIENT=lettuce
    // because the main Lettuce client already provides the same footprint and a
    // second default-configured client would block on handshake.
    if ("glide".equals(which)) {
      RedisClusterClient lettuceIdle = RedisClusterClient.create("redis://" + host + ":" + port);
      try { lettuceIdle.connect().sync().ping(); } catch (Exception ignore) {}
      System.out.println("[INIT] idle Lettuce parity client connected");
    }

    // pre-populate keys so reads have data
    System.out.println("[INIT] pre-populating 1000 keys...");
    for (int i = 0; i < 1000; i++) c.set("k:" + i, "v");
    System.out.println("[INIT] pre-population complete");

    AtomicInteger tid = new AtomicInteger();
    ForkJoinPool dispatcher = new ForkJoinPool(50,
        p -> { ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
               t.setName("blocking-dispatcher-" + tid.incrementAndGet());
               t.setDaemon(true); return t; },
        null, true);

    new Thread(() -> requestLoop(dispatcher, c), "request-gen").start();
    new Thread(() -> monitorLoop(dispatcher), "monitor").start();
    Thread.sleep(Long.MAX_VALUE);
  }

  // ───────── client constructors ─────────

  static Client newGlide(String host, int port) throws Exception {
    GlideClusterClient g = GlideClusterClient.createClient(
        GlideClusterClientConfiguration.builder()
          .address(NodeAddress.builder().host(host).port(port).build())
          .requestTimeout(30)
          .readFrom(ReadFrom.AZ_AFFINITY_REPLICAS_AND_PRIMARY)
          .advancedConfiguration(AdvancedGlideClusterClientConfiguration.builder()
            .connectionTimeout(1000)
            .periodicChecks(PeriodicChecksManualInterval.builder().durationInSec(60).build())
            .build())
          .build()).get();
    return new Client() {
      public String get(String k) throws Exception { return g.get(k).get(); }
      public void set(String k, String v) throws Exception { g.set(k, v).get(); }
    };
  }

  static Client newLettuce(String host, int port) {
    // default 1 s timeout for connect + commands
    RedisURI uri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(1)).build();
    RedisClusterClient raw = RedisClusterClient.create(uri);
    raw.setOptions(ClusterClientOptions.builder()
        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
            .enablePeriodicRefresh(Duration.ofSeconds(60))
            .enableAllAdaptiveRefreshTriggers()
            .build())
        .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
        .build());
    // force a topology refresh so the partition table is populated after the cluster
    // finished assigning slots (addslots is run AFTER valkey-server boot in compose)
    try (StatefulRedisClusterConnection<String, String> boot = raw.connect()) {
      raw.refreshPartitions();
      System.out.println("[INIT] Lettuce partitions: " + raw.getPartitions());
    }

    // Connection pool — Lettuce cluster uses 1 Netty channel per node by default.
    // Increase parallelism so Lettuce can match GLIDE's internal connection multiplexing
    // under the shared nsPerRequest pacing.
    int poolSize = 16;
    @SuppressWarnings({"unchecked", "rawtypes"})
    StatefulRedisClusterConnection<String, String>[] conns = new StatefulRedisClusterConnection[poolSize];
    for (int i = 0; i < poolSize; i++) {
      conns[i] = raw.connect();
      conns[i].setReadFrom(io.lettuce.core.ReadFrom.ANY);
    }
    System.out.printf("[INIT] Lettuce connection pool size=%d%n", poolSize);
    java.util.concurrent.atomic.AtomicInteger rr = new java.util.concurrent.atomic.AtomicInteger();
    return new Client() {
      private RedisAdvancedClusterCommands<String, String> pick() {
        return conns[Math.floorMod(rr.getAndIncrement(), poolSize)].sync();
      }
      public String get(String k) { return pick().get(k); }
      public void set(String k, String v) { pick().set(k, v); }
    };
  }

  // ───────── request + monitor loops (identical for both clients) ─────────

  static void requestLoop(ForkJoinPool pool, Client c) {
    Random rng = new Random();
    /**
     *   - 1 second = 1,000,000,000 ns
     *   - 2,000,000 ns = 2 ms
     *   - 1,000,000,000 / 2,000,000 = 500 requests per second
     **/
    final long nsPerRequest = 100_000_000L; // 10 incoming req/s → ~85 GETs/s (avg 8.5 fanout)

    long next = System.nanoTime();
    while (true) {
      next += nsPerRequest;
      int fanout = 1 + rng.nextInt(16);
      for (int i = 0; i < fanout; i++) {
        final int k = rng.nextInt(1000);
        pool.execute(() -> {
          try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
              boolean done = false;
              public boolean block() {
                blocking.incrementAndGet();
                long t0 = System.nanoTime();
                try {
                  c.get("k:" + k);   // blocking future.get() inside the client shim
                  long dt = System.nanoTime() - t0;
                  latencySumNs.addAndGet(dt); latencySamples.incrementAndGet();
                  totalGets.incrementAndGet();
                } catch (Exception e) { totalErrs.incrementAndGet(); }
                finally { blocking.decrementAndGet(); done = true; }
                return true;
              }
              public boolean isReleasable() { return done; }
            });
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
      }
      long sleep = next - System.nanoTime();
      if (sleep > 0) LockSupport.parkNanos(sleep);
    }
  }

  static void monitorLoop(ForkJoinPool pool) {
    long lastGets = 0;
    while (true) {
      try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
      long g = totalGets.get(), dg = g - lastGets; lastGets = g;
      long samples = latencySamples.getAndSet(0);
      long sumNs = latencySumNs.getAndSet(0);
      double avgMs = samples == 0 ? 0.0 : (sumNs / samples) / 1e6;
      int poolSize = pool.getPoolSize();
      long queued = pool.getQueuedSubmissionCount();
      int blk = blocking.get();
      long threads = Thread.activeCount();
      String tag = blk > 300 && dg == 0 ? "[STUCK]" : "[MONITOR]";
      System.out.printf("%s gets=%d(+%d/s) errs=%d blocking=%d pool=%d queued=%d threads=%d avg=%.1fms%n",
          tag, g, dg / 5, totalErrs.get(), blk, poolSize, queued, threads, avgMs);
    }
  }
}
