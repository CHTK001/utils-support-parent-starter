package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class SqliteHookDebugTest {

    @BeforeEach
    void setUp() throws Exception {
        Files.deleteIfExists(Paths.get("test_hook_debug.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(Paths.get("test_hook_debug.db"));
    }

    @Test
    void hook_true_reactive_works() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            assertTrue(hook.isOpen());
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            // 先订阅，再写操作
            CountDownLatch latch = new CountDownLatch(1);
            List<SqliteChangeEvent> collected = new CopyOnWriteArrayList<>();
            hook.changes().take(4).subscribe(
                    collected::add,
                    err -> fail("unexpected error", err),
                    latch::countDown
            );

            hook.exec("INSERT INTO t(name) VALUES('a')");
            hook.exec("INSERT INTO t(name) VALUES('b')");
            hook.exec("UPDATE t SET name='c' WHERE id=1");
            hook.exec("DELETE FROM t WHERE id=2");

            // 等待订阅完成（所有4个事件已收集）
            latch.await(10, TimeUnit.SECONDS);
            assertEquals(4, collected.size(), "Should collect 4 events");
            assertEquals(SqliteChangeEvent.Type.INSERT, collected.get(0).getType());
            assertEquals(1L, collected.get(0).getRowId());
            assertEquals(SqliteChangeEvent.Type.INSERT, collected.get(1).getType());
            assertEquals(2L, collected.get(1).getRowId());
            assertEquals(SqliteChangeEvent.Type.UPDATE, collected.get(2).getType());
            assertEquals(1L, collected.get(2).getRowId());
            assertEquals(SqliteChangeEvent.Type.DELETE, collected.get(3).getType());
            assertEquals(2L, collected.get(3).getRowId());
        }
    }

    @Test
    void hook_single_insert() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            CountDownLatch latch = new CountDownLatch(1);
            List<SqliteChangeEvent> collected = new CopyOnWriteArrayList<>();
            hook.changes().take(1).subscribe(collected::add, err -> {}, latch::countDown);

            hook.exec("INSERT INTO t(name) VALUES('hello')");
            latch.await(5, TimeUnit.SECONDS);
            assertEquals(1, collected.size());
            assertEquals(SqliteChangeEvent.Type.INSERT, collected.get(0).getType());
            assertEquals("t", collected.get(0).getTable());
            assertEquals(1L, collected.get(0).getRowId());
        }
    }

    @Test
    void multiSubscriber_sees_events() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            List<SqliteChangeEvent> sub1 = new CopyOnWriteArrayList<>();
            List<SqliteChangeEvent> sub2 = new CopyOnWriteArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            hook.changes().take(2).subscribe(sub1::add, err -> {}, latch1::countDown);
            hook.changes().take(2).subscribe(sub2::add, err -> {}, latch2::countDown);

            hook.exec("INSERT INTO t(name) VALUES('a')");
            hook.exec("INSERT INTO t(name) VALUES('b')");

            latch1.await(5, TimeUnit.SECONDS);
            latch2.await(5, TimeUnit.SECONDS);
            assertEquals(2, sub1.size(), "Subscriber 1 should receive 2 events");
            assertEquals(2, sub2.size(), "Subscriber 2 should receive 2 events");
        }
    }
}
