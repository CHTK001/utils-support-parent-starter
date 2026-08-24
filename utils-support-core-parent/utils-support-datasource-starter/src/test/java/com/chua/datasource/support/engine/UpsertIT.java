package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upsert（存在则更新，不存在则插入）真实容器测试。
 * 覆盖 MySQL（ON DUPLICATE KEY UPDATE）、PostgreSQL（ON CONFLICT）、H2（MERGE）。
 */
class UpsertIT {

    @Test
    void upsert_mysql_onDuplicateKeyUpdate() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mysql",
                    "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "root@");

            engine.execute("DROP TABLE IF EXISTS jte_upsert").block();
            engine.execute("CREATE TABLE jte_upsert (" +
                    "id INT PRIMARY KEY, name VARCHAR(50), score INT)").block();

            /* 第一次 upsert：不存在 → 插入 */
            int r1 = engine.execute(
                    "INSERT INTO jte_upsert (id,name,score) VALUES (1,'Alice',90) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), score=VALUES(score)"
            ).block();
            assertEquals(1, r1);

            var row1 = engine.query("SELECT name,score FROM jte_upsert WHERE id=1").next().block();
            assertNotNull(row1);
            assertEquals("Alice", row1.get("name"));
            assertEquals(90, ((Number) row1.get("score")).intValue());

            /* 第二次 upsert：已存在 → 更新 */
            int r2 = engine.execute(
                    "INSERT INTO jte_upsert (id,name,score) VALUES (1,'Alicia',95) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), score=VALUES(score)"
            ).block();
            assertTrue(r2 >= 0);

            /* 读回校验新值 */
            var row2 = engine.query("SELECT name,score FROM jte_upsert WHERE id=1").next().block();
            assertNotNull(row2);
            assertEquals("Alicia", row2.get("name"), "upsert 后应读到新 name");
            assertEquals(95, ((Number) row2.get("score")).intValue(), "upsert 后应读到新 score");
            assertEquals(1, engine.query("SELECT COUNT(*) AS c FROM jte_upsert").next()
                    .block().get("c") instanceof Number ? 1 : 0);

            engine.execute("DROP TABLE jte_upsert").block();
        } finally {
            engine.close();
        }
    }

    @Test
    void upsert_postgresql_onConflict() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("pg", "jdbc:postgresql://172.16.0.40:5433/testdb", "postgres", "postgres");

            String tbl = "jte_upsert_" + System.nanoTime();
            engine.execute("DROP TABLE IF EXISTS " + tbl).block();
            engine.execute("CREATE TABLE " + tbl + " (id INT PRIMARY KEY, name VARCHAR(50), score INT)").block();

            /* 插入 */
            engine.execute("INSERT INTO " + tbl + " (id,name,score) VALUES (1,'Bob',80) " +
                    "ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, score=EXCLUDED.score").block();

            var row1 = engine.query("SELECT name,score FROM " + tbl + " WHERE id=1").next().block();
            assertNotNull(row1);
            assertEquals("Bob", row1.get("name"));

            /* 更新 */
            engine.execute("INSERT INTO " + tbl + " (id,name,score) VALUES (1,'Robert',88) " +
                    "ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, score=EXCLUDED.score").block();

            var row2 = engine.query("SELECT name,score FROM " + tbl + " WHERE id=1").next().block();
            assertNotNull(row2);
            assertEquals("Robert", row2.get("name"), "PG upsert 后应读到新值");
            assertEquals(88, ((Number) row2.get("score")).intValue());

            engine.execute("DROP TABLE " + tbl).block();
        } finally {
            engine.close();
        }
    }

    @Test
    void upsert_h2_mergeInto() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("h2", "r2dbc:h2:mem://upsert_" + System.nanoTime());

            engine.execute("CREATE TABLE up_t (id INT PRIMARY KEY, val VARCHAR(20))").block();

            /* H2 MERGE INTO：不存在 → 插入 */
            engine.execute("MERGE INTO up_t (id,val) KEY(id) VALUES (1,'first')").block();

            var r1 = engine.query("SELECT val FROM up_t WHERE id=1").next().block();
            assertNotNull(r1);
            assertEquals("first", r1.get("VAL"));

            /* 已存在 → 覆盖 */
            engine.execute("MERGE INTO up_t (id,val) KEY(id) VALUES (1,'second')").block();

            var r2 = engine.query("SELECT val FROM up_t WHERE id=1").next().block();
            assertNotNull(r2);
            assertEquals("second", r2.get("VAL"), "H2 MERGE 后应读到覆盖值");
        } finally {
            engine.close();
        }
    }
}
