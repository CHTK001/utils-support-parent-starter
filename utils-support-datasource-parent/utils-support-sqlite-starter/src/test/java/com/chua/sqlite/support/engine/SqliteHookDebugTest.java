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
    void hook_single_insert() throws Exception {
        try (SqliteHookConnection cdc = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(cdc);
            assertTrue(cdc.isOpen());
            cdc.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            List<SqliteChangeEvent> collected = new CopyOnWriteArrayList<>();
            cdc.onEvent(collected::add);

            cdc.exec("INSERT INTO t(name) VALUES('hello')");
            assertEquals(1, collected.size());
            assertEquals(SqliteChangeEvent.Type.INSERT, collected.get(0).getType());
            assertEquals("t", collected.get(0).getTable());
            assertEquals(1L, collected.get(0).getRowId());
        }
    }

    @Test
    void hook_true_reactive_works() throws Exception {
        try (SqliteHookConnection cdc = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(cdc);
            assertTrue(cdc.isOpen());
            cdc.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            List<SqliteChangeEvent> collected = new CopyOnWriteArrayList<>();
            cdc.onEvent(collected::add);

            cdc.exec("INSERT INTO t(name) VALUES('a')");
            cdc.exec("INSERT INTO t(name) VALUES('b')");
            cdc.exec("UPDATE t SET name='c' WHERE id=1");
            cdc.exec("DELETE FROM t WHERE id=2");

            assertEquals(4, collected.size(), "应收集4条事件");
            assertEquals(SqliteChangeEvent.Type.INSERT, collected.get(0).getType());
            assertEquals(SqliteChangeEvent.Type.UPDATE, collected.get(2).getType());
            assertEquals(SqliteChangeEvent.Type.DELETE, collected.get(3).getType());
        }
    }

    @Test
    void drain_returns_buffered_events() throws Exception {
        try (SqliteHookConnection cdc = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(cdc);
            cdc.exec("CREATE TABLE t(x INTEGER PRIMARY KEY)");

            cdc.exec("INSERT INTO t(x) VALUES(1)");
            cdc.exec("INSERT INTO t(x) VALUES(2)");

            List<SqliteChangeEvent> events = cdc.drain();
            assertEquals(2, events.size());
            assertEquals(SqliteChangeEvent.Type.INSERT, events.get(0).getType());
            assertEquals(SqliteChangeEvent.Type.INSERT, events.get(1).getType());
        }
    }
}
