package harness;

import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.CachingEngine;
import com.chua.datasource.support.engine.InMemoryEngine;
import com.chua.sqlite.support.engine.SqliteEngine;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded real SQLite cache throughput and all-field index cost measurements. */
public final class EngineCachePerformance {
    @TableName("cache_perf")
    public static final class Row {
        private int id;
        private String name;
        private int status;
        private long amount;
        public Row() { }
        public Row(int id) { this.id = id; this.name = "row-" + id; this.status = id % 16; this.amount = id * 11L; }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }
    }

    private EngineCachePerformance() { }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static void cache(Path directory, int rows, String variant, int round) throws Exception {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "cache-performance-", ".sqlite");
        SqliteEngine delegate = new SqliteEngine();
        try (CachingEngine cached = CachingEngine.create(delegate).addCacheTable("cache_perf")) {
            delegate.addDataSource("default", file.toString());
            delegate.executeSql("CREATE TABLE cache_perf(id INTEGER PRIMARY KEY, name TEXT, status INTEGER, amount INTEGER)");
            List<Object[]> parameters = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) { parameters.add(new Object[]{i, "row-" + i, i % 16, i * 11L}); }
            delegate.batchSql("INSERT INTO cache_perf(id,name,status,amount) VALUES (?,?,?,?)", parameters);
            require(delegate.query(Row.class).count() == rows, "real SQLite seed count");
            long coldStart = System.nanoTime();
            require(cached.query(Row.class).eq(Row::getId, rows - 1).one().getAmount() == (rows - 1L) * 11L,
                    "cold cache value differs from committed SQLite value");
            long coldNanos = System.nanoTime() - coldStart;
            require(cached.stats().cachedRows() == rows, "table must really be cached");
            System.out.printf(Locale.ROOT, "COLD variant=%s round=%d rows=%d millis=%.3f%n",
                    variant, round, rows, coldNanos / 1e6);
            measure(cached, rows, 1, 1000, false, variant, round);
            for (int threads : new int[]{1, 4, 8}) {
                measure(cached, rows, threads, 600, false, variant, round);
                measure(cached, rows, threads, 2000, true, variant, round);
            }
            require(cached.stats().cachedRows() == rows, "warm queries must retain the snapshot");
        } finally {
            delegate.close();
            for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
                Files.deleteIfExists(Path.of(file + suffix));
            }
        }
        require(!Files.exists(file), "SQLite file cleanup");
        System.out.println("RESULT cachePerformance=PASS variant=" + variant + " round=" + round);
    }

    private static void measure(CachingEngine cached, int rows, int threads, long millis,
                                boolean report, String variant, int round) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] counts = new long[threads];
        long[] end = new long[1];
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int worker = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    int id = worker;
                    long count = 0;
                    while (System.nanoTime() < end[0]) {
                        id = (id + 97) % rows;
                        Row row = cached.query(Row.class).eq(Row::getId, id).one();
                        require(row != null && row.getId() == id && row.getAmount() == id * 11L
                                && ("row-" + id).equals(row.getName()), "incorrect hot cache result");
                        count++;
                    }
                    counts[worker] = count;
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "cache-perf-" + i);
            workers.add(thread);
            thread.start();
        }
        require(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "worker readiness timeout");
        long began = System.nanoTime();
        end[0] = began + millis * 1_000_000L;
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15000);
            require(!worker.isAlive(), "bounded worker timeout");
        }
        long elapsed = System.nanoTime() - began;
        if (failure.get() != null) { throw new AssertionError("worker failure", failure.get()); }
        long operations = 0;
        for (long count : counts) { operations += count; }
        require(operations > 0, "zero successful operations");
        if (report) {
            System.out.printf(Locale.ROOT,
                    "PERF variant=%s round=%d rows=%d threads=%d operations=%d seconds=%.6f qps=%.2f errors=0%n",
                    variant, round, rows, threads, operations, elapsed / 1e9, operations * 1e9 / elapsed);
        }
    }

    private static long retainedHeap() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void index(int size) throws Exception {
        try (InMemoryEngine memory = new InMemoryEngine()) {
            List<Row> data = new ArrayList<>(size);
            for (int i = 0; i < size; i++) { data.add(new Row(i)); }
            memory.store("cache_perf", data);
            memory.index();
            memory.store("cache_perf", data);
            long before = retainedHeap();
            long start = System.nanoTime();
            memory.index();
            long elapsed = System.nanoTime() - start;
            long after = retainedHeap();
            require(memory.query(Row.class).eq(Row::getId, size - 1).one().getAmount() == (size - 1L) * 11L,
                    "indexed value");
            require(memory.query(Row.class).eq(Row::getStatus, 0).count() == (size + 15L) / 16L,
                    "low-cardinality field index");
            System.out.printf(Locale.ROOT, "INDEX rows=%d fields=4 buildMillis=%.3f retainedDeltaBytes=%d beforeBytes=%d afterBytes=%d%n",
                    size, elapsed / 1e6, after - before, before, after);
        }
        System.out.println("RESULT indexCost=PASS rows=" + size);
    }

    public static void main(String[] args) throws Exception {
        if ("index".equals(args[0])) { index(Integer.parseInt(args[1])); }
        else { cache(Path.of(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3])); }
    }
}
