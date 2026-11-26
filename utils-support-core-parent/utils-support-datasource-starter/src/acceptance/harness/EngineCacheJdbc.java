package harness;

import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.CachingEngine;
import com.chua.sqlite.support.engine.SqliteEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Real file-backed SQLite/JDBC cache coherence checks; all files remain under temp. */
public final class EngineCacheJdbc {
    private static int passed;

    @TableName("cache_jdbc_row")
    public static final class Row {
        private int id;
        private String name;
        public Row() { }
        public Row(int id, String name) { this.id = id; this.name = name; }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    private static final class ObservedSqlite extends SqliteEngine {
        private Runnable afterWrite = () -> { };
        @Override public List<EngineInterceptor> interceptors() {
            return List.of(new EngineInterceptor() {
                @Override public void afterUpdate(String sql, Object[] params, int affected) {
                    afterWrite.run();
                }
            });
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) { throw new AssertionError(label); }
        passed++;
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Path first = Files.createTempFile(directory, "cache-jdbc-", ".sqlite");
        Path second = Files.createTempFile(directory, "cache-jdbc-other-", ".sqlite");
        ObservedSqlite delegate = new ObservedSqlite();
        try (CachingEngine cache = CachingEngine.create(delegate).addCacheTable("*")) {
            delegate.addDataSource("first", first.toString());
            cache.executeSql("CREATE TABLE cache_jdbc_row (id INTEGER PRIMARY KEY, name TEXT)");
            require(cache.insert(Row.class, new Row(1, "one")) == 1
                    && cache.query(Row.class).eq(Row::getId, 1).list().size() == 1, "insert and indexed cache load");
            require(cache.insertBatch(Row.class, List.of(new Row(2, "two"), new Row(3, "three"))) == 2
                    && cache.query(Row.class).count() == 3, "batch insert invalidates JDBC snapshot");
            require(cache.update(Row.class).set(Row::getName, "changed").eq(Row::getId, 1).update() == 1
                    && cache.query(Row.class).eq(Row::getName, "changed").count() == 1,
                    "ORM update rebuilds field index");
            require(cache.delete(Row.class).eq(Row::getId, 2).remove() == 1
                    && cache.query(Row.class).eq(Row::getId, 2).count() == 0, "ORM delete invalidates snapshot");
            cache.batchSql("INSERT INTO cache_jdbc_row(id,name) VALUES (?,?)",
                    List.of(new Object[]{4, "four"}, new Object[]{5, "five"}));
            require(cache.query(Row.class).count() == 4, "native batch invalidates snapshot");
            cache.executeSql("UPDATE cache_jdbc_row SET name = ? WHERE id = ?", "raw", 4);
            require(cache.query(Row.class).eq(Row::getName, "raw").count() == 1,
                    "native update invalidates snapshot");
            AtomicInteger notices = new AtomicInteger();
            cache.addInvalidator(table -> notices.incrementAndGet());
            delegate.afterWrite = () -> { throw new IllegalStateException("after-write failure"); };
            boolean failed = false;
            try { cache.delete(Row.class).eq(Row::getId, 3).remove(); }
            catch (RuntimeException expected) { failed = true; }
            delegate.afterWrite = () -> { };
            require(failed && delegate.query(Row.class).eq(Row::getId, 3).count() == 0,
                    "post-write callback throws after database commit");
            require(cache.query(Row.class).eq(Row::getId, 3).count() == 0 && notices.get() == 1,
                    "post-write exception invalidates local snapshot and notifies invalidator");
            cache.setCacheEnabled(false);
            cache.executeSql("DELETE FROM cache_jdbc_row WHERE id = ?", 5);
            cache.setCacheEnabled(true);
            require(cache.query(Row.class).count() == 2, "disabled cache does not retain JDBC writes");
            delegate.addDataSource("second", second.toString());
            cache.setDefaultDataSourceName("second");
            // Write through delegate deliberately: the source switch itself must evict the old snapshot.
            delegate.executeSql("CREATE TABLE cache_jdbc_row (id INTEGER PRIMARY KEY, name TEXT)");
            delegate.executeSql("INSERT INTO cache_jdbc_row(id,name) VALUES (99,'other')");
            require(cache.query(Row.class).eq(Row::getId, 99).count() == 1
                    && cache.query(Row.class).count() == 1, "switching default data source evicts old table");
            cache.setDefaultDataSourceName("first");
            require(cache.query(Row.class).count() == 2, "switching back loads original source");
            cache.setMaxRows(1);
            require(cache.query(Row.class).count() == 2 && cache.isBypassed("cache_jdbc_row"),
                    "JDBC count threshold bypasses oversized table");
        } finally {
            delegate.close();
            for (Path file : List.of(first, second)) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(Path.of(file + "-wal"));
                Files.deleteIfExists(Path.of(file + "-shm"));
                Files.deleteIfExists(Path.of(file + "-journal"));
            }
        }
        require(!Files.exists(first) && !Files.exists(second), "database files cleaned");
        System.out.println("RESULT jdbcPass=" + passed + " fail=0");
    }
}
