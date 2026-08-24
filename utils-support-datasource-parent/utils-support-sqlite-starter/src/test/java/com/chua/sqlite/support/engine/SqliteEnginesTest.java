package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 引擎测试（阻塞 + 响应式，本地临时文件库）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class SqliteEnginesTest {

    /**
     * 阻塞引擎：建表/写入/查询闭环。
     */
    @Test
    void blocking_engine_roundtrip() throws Exception {
        Path db = Files.createTempFile("sqlite-eng", ".db");
        db.toFile().deleteOnExit();
        SqliteEngine e = new SqliteEngine();
        e.addDataSource("default", db.toString());

        var ex = e.getExecutor();
        assertNotNull(ex);
        ex.execute("CREATE TABLE IF NOT EXISTS it_sqlite(id INTEGER PRIMARY KEY, name TEXT)");
        ex.execute("INSERT INTO it_sqlite(id, name) VALUES(1, 'a')");
        ex.execute("INSERT INTO it_sqlite(id, name) VALUES(2, 'b')");

        List<Map<String, Object>> rows = ex.query("SELECT id, name FROM it_sqlite ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("b", rows.get(1).get("name"));
    }

    /**
     * 响应式引擎：同一文件库的流式查询。
     */
    @Test
    void reactive_engine_query() throws Exception {
        Path db = Files.createTempFile("sqlite-rx", ".db");
        db.toFile().deleteOnExit();
        SqliteReactorEngine e = new SqliteReactorEngine();
        e.addDataSource("default", db.toString());

        // 先用响应式 execute 建表插入
        e.execute("CREATE TABLE IF NOT EXISTS rx(id INTEGER PRIMARY KEY, v TEXT)").block();
        StepVerifier.create(e.execute("INSERT INTO rx(id, v) VALUES(1, 'x')"))
                .expectNext(1).verifyComplete();

        StepVerifier.create(e.query("SELECT id, v FROM rx WHERE v = 'x'"))
                .assertNext(m -> assertEquals("x", m.get("v")))
                .verifyComplete();
    }
}
