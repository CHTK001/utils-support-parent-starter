package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.*;
import java.io.*;
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
    void hook_exec_calls_callback() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            assertTrue(hook.isOpen());

            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            PrintStream origErr = System.err;
            System.setErr(new PrintStream(errCapture, true));

            int rc = hook.exec("INSERT INTO t(name) VALUES('hello')");
            System.setErr(origErr);

            String stderrOutput = errCapture.toString();
            System.out.println("STDERR:\n" + stderrOutput);

            assertEquals(0, rc);
            assertTrue(stderrOutput.contains("[HOOK]"), "C callback not fired: " + stderrOutput);
            assertTrue(stderrOutput.contains("INSERT"), "INSERT callback not found in: " + stderrOutput);
        }
    }

    @Test
    void poll_after_exec_returns_event() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            PrintStream origErr = System.err;
            System.setErr(new PrintStream(errCapture, true));

            hook.exec("INSERT INTO t(name) VALUES('x')");
            System.setErr(origErr);

            System.out.println("STDERR: " + errCapture.toString());
            assertNotNull(hook.changes());
        }
    }

    @Test
    void changes_emits_all_events() throws Exception {
        try (SqliteHookConnection hook = SqliteHookConnection.open("test_hook_debug.db")) {
            assertNotNull(hook);
            hook.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");

            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            PrintStream origErr = System.err;
            System.setErr(new PrintStream(errCapture, true));

            hook.exec("INSERT INTO t(name) VALUES('a')");
            hook.exec("INSERT INTO t(name) VALUES('b')");
            hook.exec("UPDATE t SET name='c' WHERE id=1");
            hook.exec("DELETE FROM t WHERE id=2");
            System.setErr(origErr);

            System.out.println("STDERR: " + errCapture.toString());

            StepVerifier.create(hook.changes())
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.INSERT)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.INSERT)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.UPDATE)
                    .expectNextMatches(e -> e.getType() == SqliteChangeEvent.Type.DELETE)
                    .verifyComplete();
        }
    }
}
