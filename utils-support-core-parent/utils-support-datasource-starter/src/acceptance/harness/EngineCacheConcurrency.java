package harness;

import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.CachingEngine;
import com.chua.datasource.support.engine.InMemoryEngine;
import com.chua.datasource.support.engine.JdbcEngine;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real Engine regression checks; no database, sockets, mock engines or background services. */
public final class EngineCacheConcurrency {
    private static int passed;
    private static int failed;

    @TableName("cache_row")
    public static class Row {
        private final int id;
        private final String name;
        public Row(int id, String name) { this.id = id; this.name = name; }
        public int getId() { return id; }
        public String getName() { return name; }
    }

    public static final class GatedRow extends Row {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        public GatedRow(int id, CountDownLatch entered, CountDownLatch release) {
            super(id, "row" + id);
            this.entered = entered;
            this.release = release;
        }
        @Override public int getId() {
            if (super.getId() == 2 && "index-builder".equals(Thread.currentThread().getName())) {
                entered.countDown();
                await(release);
            }
            return super.getId();
        }
    }

    @TableName("cold_row")
    public static final class ColdRow extends Row {
        public ColdRow(int id) { super(id, "cold"); }
    }

    /** Instruments lifecycle callbacks while all data operations remain in JdbcEngine. */
    private static final class ObservedJdbc extends JdbcEngine {
        private Runnable afterRead = () -> { };
        private Runnable afterWrite = () -> { };
        @Override public List<EngineInterceptor> interceptors() {
            return List.of(new EngineInterceptor() {
                @Override public void afterQuery(String sql, Object[] params, List<?> rows) {
                    afterRead.run();
                }
                @Override public void afterUpdate(String sql, Object[] params, int affected) {
                    afterWrite.run();
                }
            });
        }
    }

    private static final class ObservedCache extends CachingEngine {
        private Runnable beforeRead = () -> { };
        ObservedCache(JdbcEngine delegate) { super(delegate, true); addCacheTable("*"); }
        @Override public List<EngineInterceptor> interceptors() {
            return List.of(new EngineInterceptor() {
                @Override public void beforeQuery(String sql, Object[] params) { beforeRead.run(); }
            });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("latch timeout"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void require(boolean condition) {
        if (!condition) { throw new AssertionError("unexpected engine result"); }
    }

    private static void check(String name, Runnable action) {
        try {
            action.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure);
        }
    }

    public static void main(String[] args) {
        check("failed post-write callback still invalidates the cache", () -> {
            ObservedJdbc delegate = new ObservedJdbc();
            try (ObservedCache engine = new ObservedCache(delegate)) {
                engine.store("cache_row", List.of(new Row(1, "one"), new Row(2, "two")));
                require(engine.query(Row.class).list().size() == 2);
                AtomicInteger notices = new AtomicInteger();
                engine.addInvalidator(table -> notices.incrementAndGet());
                delegate.afterWrite = () -> { throw new IllegalStateException("after-write failure"); };
                boolean thrown = false;
                try { engine.delete(Row.class).eq(Row::getId, 1).delete(); }
                catch (IllegalStateException expected) { thrown = true; }
                delegate.afterWrite = () -> { };
                require(thrown && delegate.query(Row.class).list().size() == 1);
                require(engine.query(Row.class).list().size() == 1 && notices.get() == 1);
            }
        });
        check("indexed insert batch update and delete remain visible", () -> {
            try (InMemoryEngine engine = new InMemoryEngine()) {
                engine.store("cache_row", List.of(new Row(1, "one")));
                engine.index();
                require(engine.insert(Row.class, new Row(2, "two")) == 1);
                require(engine.query(Row.class).eq(Row::getId, 2).count() == 1);
                engine.index();
                require(engine.insertBatch(Row.class, List.of(new Row(3, "three"), new Row(4, "four"))) == 2);
                require(engine.query(Row.class).eq(Row::getId, 4).count() == 1);
                engine.index();
                require(engine.delete(Row.class).eq(Row::getId, 2).delete() == 1);
                require(engine.query(Row.class).eq(Row::getId, 2).count() == 0);
                engine.index();
                require(engine.executeSql("DELETE FROM cache_row WHERE id = ?", 3) == 1);
                require(engine.query(Row.class).eq(Row::getId, 3).count() == 0);
            }
        });
        check("concurrent writers and index rebuilds do not lose inserts", () -> {
            try (InMemoryEngine engine = new InMemoryEngine()) {
                engine.store("cache_row", List.of(new Row(0, "zero")));
                AtomicReference<Throwable> error = new AtomicReference<>();
                java.util.ArrayList<Thread> threads = new java.util.ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    final int offset = worker * 100;
                    threads.add(new Thread(() -> {
                        try {
                            for (int i = 1; i <= 100; i++) {
                                engine.insert(Row.class, new Row(offset + i, "row"));
                                if (i % 20 == 0) { engine.index(); }
                            }
                        } catch (Throwable t) { error.set(t); }
                    }));
                }
                threads.forEach(Thread::start);
                threads.forEach(thread -> {
                    try { thread.join(5000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    require(!thread.isAlive());
                });
                require(error.get() == null && engine.query(Row.class).count() == 401);
                engine.index();
                for (int i = 0; i <= 400; i++) {
                    require(engine.query(Row.class).eq(Row::getId, i).count() == 1);
                }
            }
        });
        check("indexed OR retains both branches", () -> {
            try (InMemoryEngine engine = new InMemoryEngine()) {
                engine.store("cache_row", List.of(new Row(1, "one"), new Row(2, "two")));
                engine.index();
                require(engine.query(Row.class).eq(Row::getId, 1)
                        .or(w -> w.eq(Row::getId, 2)).list().size() == 2);
            }
        });
        check("default storage never borrows another table index", () -> {
            try (InMemoryEngine engine = new InMemoryEngine()) {
                engine.store("default", List.of(new Row(1, "default")));
                engine.store("unrelated", List.of(new Row(1, "other")));
                engine.index();
                engine.store("default", List.of(new Row(1, "replacement")));
                require("replacement".equals(engine.query(Row.class).eq(Row::getId, 1).one().getName()));
            }
        });
        check("index is published only after all rows are built", () -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            try (InMemoryEngine engine = new InMemoryEngine()) {
                engine.store("default", List.of(new GatedRow(1, entered, release),
                        new GatedRow(2, entered, release), new GatedRow(3, entered, release)));
                Thread builder = new Thread(() -> {
                    try { engine.index(); } catch (Throwable t) { error.set(t); }
                }, "index-builder");
                builder.start();
                try {
                    await(entered);
                    require(engine.query(GatedRow.class).eq(GatedRow::getId, 3).list().size() == 1);
                } finally {
                    release.countDown();
                    try { builder.join(5000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    require(!builder.isAlive() && error.get() == null);
                }
            }
        });
        check("writes while disabled invalidate retained cache", () -> {
            try (ObservedCache engine = new ObservedCache(new ObservedJdbc())) {
                engine.store("cache_row", List.of(new Row(1, "one")));
                require(engine.query(Row.class).list().size() == 1);
                engine.setCacheEnabled(false);
                engine.insert(Row.class, new Row(2, "two"));
                engine.setCacheEnabled(true);
                require(engine.query(Row.class).list().size() == 2);
            }
        });
        check("eviction during an acquired read preserves its snapshot", () -> {
            try (ObservedCache engine = new ObservedCache(new ObservedJdbc())) {
                engine.store("cache_row", List.of(new Row(1, "one")));
                engine.query(Row.class).list();
                engine.beforeRead = () -> engine.reload("cache_row");
                require(engine.query(Row.class).list().size() == 1);
            }
        });
        check("bypass TTL allows smaller table to enter cache", () -> {
            try (ObservedCache engine = new ObservedCache(new ObservedJdbc())) {
                engine.setMaxRows(1).setTtlSeconds(1);
                engine.store("cache_row", List.of(new Row(1, "one"), new Row(2, "two")));
                engine.query(Row.class).list();
                require(engine.isBypassed("cache_row"));
                engine.getDelegate().store("cache_row", List.of(new Row(1, "one")));
                try { Thread.sleep(1100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new AssertionError(e);
                }
                require(engine.query(Row.class).list().size() == 1);
                require(engine.cachedTables().contains("cache_row"));
            }
        });
        check("reload during load prevents stale publication", () -> {
            ObservedJdbc delegate = new ObservedJdbc();
            try (ObservedCache engine = new ObservedCache(delegate)) {
                engine.store("cache_row", List.of(new Row(1, "old")));
                AtomicInteger reads = new AtomicInteger();
                delegate.afterRead = () -> {
                    if (reads.incrementAndGet() == 2) {
                        engine.store("cache_row", List.of(new Row(2, "new")));
                    }
                };
                engine.query(Row.class).list();
                require("new".equals(engine.query(Row.class).list().getFirst().getName()));
            }
        });
        check("lowering row threshold applies to a warm cache", () -> {
            try (ObservedCache engine = new ObservedCache(new ObservedJdbc())) {
                engine.store("cache_row", List.of(new Row(1, "one"), new Row(2, "two")));
                engine.query(Row.class).list();
                engine.setMaxRows(1);
                require(engine.query(Row.class).list().size() == 2);
                require(engine.isBypassed("cache_row"));
            }
        });
        check("growth after COUNT cannot exceed the single-table threshold", () -> {
            ObservedJdbc delegate = new ObservedJdbc();
            try (ObservedCache engine = new ObservedCache(delegate)) {
                engine.setMaxRows(1);
                engine.store("cache_row", List.of(new Row(1, "one")));
                AtomicBoolean once = new AtomicBoolean();
                delegate.afterRead = () -> {
                    if (once.compareAndSet(false, true)) {
                        delegate.store("cache_row", List.of(new Row(1, "one"), new Row(2, "two")));
                    }
                };
                require(engine.query(Row.class).list().size() == 2);
                require(engine.isBypassed("cache_row") && engine.stats().cachedRows() == 0);
            }
        });
        check("slow cold-table load does not block a warm-table hit", () -> {
            ObservedJdbc delegate = new ObservedJdbc();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch hotDone = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            try (ObservedCache engine = new ObservedCache(delegate)) {
                engine.store("cache_row", List.of(new Row(1, "one")));
                engine.store("cold_row", List.of(new ColdRow(1)));
                engine.query(Row.class).list();
                delegate.afterRead = () -> {
                    if ("cold-reader".equals(Thread.currentThread().getName())) {
                        entered.countDown(); await(release);
                    }
                };
                Thread cold = new Thread(() -> {
                    try { require(engine.query(ColdRow.class).list().size() == 1); }
                    catch (Throwable t) { error.set(t); }
                }, "cold-reader");
                Thread hot = new Thread(() -> {
                    try { require(engine.query(Row.class).list().size() == 1); }
                    catch (Throwable t) { error.set(t); }
                    finally { hotDone.countDown(); }
                }, "hot-reader");
                cold.start();
                try {
                    await(entered);
                    hot.start();
                    require(hotDone.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new AssertionError(e);
                } finally {
                    release.countDown();
                    try { cold.join(5000); hot.join(5000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    require(!cold.isAlive() && !hot.isAlive() && error.get() == null);
                }
            }
        });
        System.out.println("RESULT pass=" + passed + " fail=" + failed);
        if (failed != 0) { throw new AssertionError("engine regression failures=" + failed); }
    }
}
