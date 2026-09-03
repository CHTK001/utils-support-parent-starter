package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteReactorHook 集成测试（需要 native 库）。
 */
class SqliteReactorHookIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteReactorHook hook;
    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test.db").toString();
        hook = new SqliteReactorHook(dbPath);
    }

    @AfterEach
    void tearDown() {
        if (hook != null) {
            hook.close();
        }
    }

    @Test
    void testOpenAndClose() {
        assertTrue(hook.isOpen());
        hook.close();
        assertFalse(hook.isOpen());
        hook = null;
    }

    @Test
    void testExecCreatesTable() {
        int rc = hook.exec("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)").block();
        assertEquals(0, rc);
    }

    @Test
    void testEventStreamReceivesInsert() throws InterruptedException {
        hook.exec("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)").block();

        List<SqliteChangeEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        hook.events()
            .doOnNext(e -> {
                events.add(e);
                latch.countDown();
            })
            .subscribe();

        hook.exec("INSERT INTO users(name) VALUES('Alice')").block();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive event within 5 seconds");
        assertFalse(events.isEmpty());

        SqliteChangeEvent event = events.get(0);
        assertEquals(SqliteChangeEvent.Type.INSERT, event.getType());
        assertEquals("users", event.getTable());
    }

    @Test
    void testEventStreamReceivesMultipleEvents() throws InterruptedException {
        hook.exec("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY, msg TEXT)").block();

        List<SqliteChangeEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(5);

        hook.events()
            .doOnNext(e -> {
                events.add(e);
                latch.countDown();
            })
            .subscribe();

        for (int i = 0; i < 5; i++) {
            hook.exec("INSERT INTO logs(msg) VALUES('msg_" + i + "')").block();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive 5 events");
        assertEquals(5, events.size());
    }

    @Test
    void testEventStreamReceivesUpdate() throws InterruptedException {
        hook.exec("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, val TEXT)").block();
        hook.exec("INSERT INTO t(val) VALUES('old')").block();

        List<SqliteChangeEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        hook.events()
            .filter(e -> e.getType() == SqliteChangeEvent.Type.UPDATE)
            .doOnNext(e -> {
                events.add(e);
                latch.countDown();
            })
            .subscribe();

        hook.exec("UPDATE t SET val='new' WHERE id=1").block();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(events.isEmpty());
        assertEquals(SqliteChangeEvent.Type.UPDATE, events.get(0).getType());
    }

    @Test
    void testEventStreamReceivesDelete() throws InterruptedException {
        hook.exec("CREATE TABLE IF NOT EXISTS del (id INTEGER PRIMARY KEY)").block();
        hook.exec("INSERT INTO del VALUES(1)").block();

        List<SqliteChangeEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        hook.events()
            .filter(e -> e.getType() == SqliteChangeEvent.Type.DELETE)
            .doOnNext(e -> {
                events.add(e);
                latch.countDown();
            })
            .subscribe();

        hook.exec("DELETE FROM del WHERE id=1").block();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(events.isEmpty());
        assertEquals(SqliteChangeEvent.Type.DELETE, events.get(0).getType());
    }

    @Test
    void testMultipleSubscribers() throws InterruptedException {
        hook.exec("CREATE TABLE IF NOT EXISTS multi (id INTEGER PRIMARY KEY)").block();

        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        hook.events()
            .take(3)
            .doOnNext(e -> count1.incrementAndGet())
            .doFinally(s -> latch.countDown())
            .subscribe();

        hook.events()
            .take(3)
            .doOnNext(e -> count2.incrementAndGet())
            .doFinally(s -> latch.countDown())
            .subscribe();

        for (int i = 0; i < 3; i++) {
            hook.exec("INSERT INTO multi VALUES(" + i + ")").block();
        }

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(count1.get() > 0, "Subscriber 1 should receive events");
        assertTrue(count2.get() > 0, "Subscriber 2 should receive events");
    }

    @Test
    void testParseEvent() {
        String json = "{\"type\":\"INSERT\",\"database\":\"main\",\"table\":\"users\",\"rowId\":1}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals(SqliteChangeEvent.Type.INSERT, event.getType());
        assertEquals("users", event.getTable());
        assertEquals(1L, event.getRowId());
    }

    @Test
    void testParseEventNull() {
        assertNull(SqliteReactorHook.parseEvent(null));
        assertNull(SqliteReactorHook.parseEvent(""));
        assertNull(SqliteReactorHook.parseEvent("invalid json"));
    }

    @Test
    void testParseEventMissingFields() {
        String json = "{\"type\":\"INSERT\"}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals("(unknown)", event.getTable());
    }
}
