package com.chua.duckdb.support.engine;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDB 引擎测试（阻塞 + 响应式，本地临时文件库）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DuckDbEnginesTest {

    /**
     * 阻塞引擎：建表/写入/查询闭环。
     */
    @Test
    void blocking_engine_roundtrip() throws Exception {
        Path db = Files.createTempFile("duck-eng", ".duckdb");
        Files.deleteIfExists(db); // DuckDB 首连自动创建
        DuckDBEngine e = new DuckDBEngine();
        e.addDataSource("default", "jdbc:duckdb:" + db);

        var ex = e.getExecutor();
        assertNotNull(ex);
        ex.execute("CREATE TABLE IF NOT EXISTS it_duck(id INTEGER PRIMARY KEY, name VARCHAR)");
        ex.execute("INSERT INTO it_duck VALUES(1, 'a'), (2, 'b')");

        List<Map<String, Object>> rows = ex.query("SELECT id, name FROM it_duck ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("b", rows.get(1).get("name"));
    }

    /**
     * 响应式引擎：同一文件库的流式查询。
     */
    @Test
    void reactive_engine_query() throws Exception {
        Path db = Files.createTempFile("duck-rx", ".duckdb");
        Files.deleteIfExists(db);
        DuckDBReactorEngine e = new DuckDBReactorEngine();
        e.addDataSource("default", "jdbc:duckdb:" + db);

        e.execute("CREATE TABLE IF NOT EXISTS rx(id INTEGER PRIMARY KEY, v VARCHAR)").block();
        StepVerifier.create(e.execute("INSERT INTO rx(id, v) VALUES(1, 'x')"))
                .expectNext(1).verifyComplete();

        StepVerifier.create(e.query("SELECT id, v FROM rx WHERE v = 'x'"))
                .assertNext(m -> assertEquals("x", m.get("v")))
                .verifyComplete();
    }
}
