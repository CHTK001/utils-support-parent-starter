package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import reactor.test.StepVerifier;

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
    void hook_exec_emits_insert_event() {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            assertTrue(hook.isOpen());
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");
            hook.exec("INSERT INTO t(name) VALUES('hello')");
            StepVerifier.create(hook.changes())
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.INSERT
                            && e.getTable().equals("t") && e.getRowId() == 1)
                    .verifyComplete();
        }
    }

    @Test
    void changes_emits_all_events() {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            hook.exec("INSERT INTO t(name) VALUES('a')");
            hook.exec("INSERT INTO t(name) VALUES('b')");
            hook.exec("UPDATE t SET name='c' WHERE id=1");
            hook.exec("DELETE FROM t WHERE id=2");

            StepVerifier.create(hook.changes())
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.INSERT
                            && e.getTable().equals("t") && e.getRowId() == 1)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.INSERT
                            && e.getTable().equals("t") && e.getRowId() == 2)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.UPDATE
                            && e.getTable().equals("t") && e.getRowId() == 1)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.DELETE
                            && e.getTable().equals("t") && e.getRowId() == 2)
                    .verifyComplete();
        }
    }

    @Test
    void poll_after_exec_returns_event() {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");
            hook.exec("INSERT INTO t(name) VALUES('x')");
            assertNotNull(hook.changes());
        }
    }
}
