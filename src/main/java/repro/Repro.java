package repro;

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
import io.lettuce.core.cluster.RedisClusterClient;

/** Minimal reproduction of a GLIDE thread-hang. No network manipulation; relies on
 *  off-heap memory squeeze + concurrent load + ForkJoinPool.managedBlock + future.get(). */
public class Repro {
  static final AtomicLong totalGets = new AtomicLong();
  static final AtomicLong totalErrs = new AtomicLong();
  static final AtomicInteger blocking = new AtomicInteger();
  static final AtomicLong latencySumNs = new AtomicLong();
  static final AtomicLong latencySamples = new AtomicLong();

  public static void main(String[] args) throws Exception {
    String host = args[0]; int port = Integer.parseInt(args[1]);

    GlideClusterClient client = null;
    for (int attempt = 1; attempt <= 30 && client == null; attempt++) {
      try {
        client = GlideClusterClient.createClient(
            GlideClusterClientConfiguration.builder()
              .address(NodeAddress.builder().host(host).port(port).build())
              .requestTimeout(30)
              .readFrom(ReadFrom.AZ_AFFINITY_REPLICAS_AND_PRIMARY)
              .advancedConfiguration(AdvancedGlideClusterClientConfiguration.builder()
                .connectionTimeout(1000)
                .periodicChecks(PeriodicChecksManualInterval.builder().durationInSec(60).build())
                .build())
              .build()).get();
      } catch (Exception e) {
        System.out.println("waiting for valkey cluster... attempt " + attempt);
        Thread.sleep(2000);
      }
    }
    if (client == null) throw new IllegalStateException("cannot connect to " + host + ":" + port);
    final GlideClusterClient c = client;

    // idle Lettuce holds direct-memory buffers — simulates a co-located fallback client.
    RedisClusterClient lettuceIdle = RedisClusterClient.create("redis://" + host + ":" + port);
    try { lettuceIdle.connect().sync().ping(); } catch (Exception ignore) {}

    // pre-populate keys so reads have data
    for (int i = 0; i < 1000; i++) c.set("k:" + i, "v").get();

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

  static void requestLoop(ForkJoinPool pool, GlideClusterClient c) {
    Random rng = new Random();
     /**
     *   - 1 second = 1,000,000,000 ns                                                                                                                                                       
     *   - 2,000,000 ns = 2 ms                                                                                                                                                               
     *   - 1,000,000,000 / 2,000,000 = 500 requests per second 
     **/
    final long nsPerRequest = 10_000_000L; // 100 req/s

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
                  c.get("k:" + k).get();   // blocking future.get() — no timeout
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
