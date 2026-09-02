package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 真响应式引擎测试（hook 变更推送 + 写操作联动）。
 *
 * @author CH
 * @since 4.0.0.43
 */
public class SqliteReactiveChangesTest {

    /**
     * hook 连接打开时，INSERT/UPDATE/DELETE 变更能被实时捕获并推送到 Flux。
     * 注意：需先订阅 changes()，再执行写操作。
     */
    @Test
    public void hook_changes_emits_events() throws Exception {
        Path db = Files.createTempFile("sqlite-rx-chg", ".db");
        db.toFile().deleteOnExit();

        SqliteReactorEngine engine = new SqliteReactorEngine();
        engine.addDataSource("default", db.toString());

        try {
            engine.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)").block();

            /* 先订阅，再写 */
            List<SqliteChangeEvent> received = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            engine.changes().subscribe(e -> {
                received.add(e);
                latch.countDown();
            });

            engine.execute("INSERT INTO users(name) VALUES('Alice')").block();
            engine.execute("UPDATE users SET name='Alice2' WHERE id=1").block();
            engine.execute("DELETE FROM users WHERE id=1").block();

            assertTrue(latch.await(5, TimeUnit.SECONDS), "变更事件未在超时内收到");
            assertEquals(3, received.size());
            assertEquals(SqliteChangeEvent.Type.INSERT, received.get(0).getType());
            assertEquals("users", received.get(0).getTable());
            assertEquals(SqliteChangeEvent.Type.UPDATE, received.get(1).getType());
            assertEquals(SqliteChangeEvent.Type.DELETE, received.get(2).getType());
        } finally {
            engine.close();
        }
    }

    /**
     * 多个订阅者都能收到同一批新事件（实时推送）。
     */
    @Test
    public void changes_multiSubscriber_sees_live_events() throws Exception {
        Path db = Files.createTempFile("sqlite-rx-mul", ".db");
        db.toFile().deleteOnExit();

        SqliteReactorEngine engine = new SqliteReactorEngine();
        engine.addDataSource("default", db.toString());

        try {
            engine.execute("CREATE TABLE t(x INTEGER PRIMARY KEY)").block();

            AtomicInteger sub1 = new AtomicInteger(0);
            AtomicInteger sub2 = new AtomicInteger(0);

            /* 先订阅，再写 */
            engine.changes().subscribe(e -> sub1.incrementAndGet());
            engine.changes().subscribe(e -> sub2.incrementAndGet());

            engine.execute("INSERT INTO t(x) VALUES(1)").block();
            engine.execute("INSERT INTO t(x) VALUES(2)").block();

            Thread.sleep(200);
            assertEquals(2, sub1.get(), "订阅者1 应收到 2 条新事件");
            assertEquals(2, sub2.get(), "订阅者2 应收到 2 条新事件");
        } finally {
            engine.close();
        }
    }

    /**
     * hook 连接不可用时降级到 JDBC，基本 CRUD 仍正常工作。
     */
    @Test
    public void jdbc_fallback_crud_works() throws Exception {
        Path db = Files.createTempFile("sqlite-rx-fb", ".db");
        db.toFile().deleteOnExit();

        SqliteReactorEngine engine = new SqliteReactorEngine();
        engine.addDataSource("default", db.toString());

        try {
            StepVerifier.create(engine.execute("CREATE TABLE fb(id INTEGER PRIMARY KEY, v TEXT)"))
                    .expectNext(1).verifyComplete();

            StepVerifier.create(engine.execute("INSERT INTO fb(id, v) VALUES(1, 'hello')"))
                    .expectNext(1).verifyComplete();

            StepVerifier.create(engine.query("SELECT v FROM fb WHERE id = 1"))
                    .assertNext(m -> assertEquals("hello", m.get("v")))
                    .verifyComplete();
        } finally {
            engine.close();
        }
    }

    /**
     * changes() 在 hook 连接未打开时返回空 Flux 而非报错。
     */
    @Test
    public void changes_empty_when_no_hook() {
        SqliteReactorEngine engine = new SqliteReactorEngine();
        engine.addDataSource("default", ":memory:");

        engine.close(); StepVerifier.create(engine.changes()).verifyComplete();
    }
}




