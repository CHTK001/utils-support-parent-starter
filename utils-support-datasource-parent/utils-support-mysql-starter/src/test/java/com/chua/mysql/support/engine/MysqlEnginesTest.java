package com.chua.mysql.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL 引擎真实服务集成测试，连接配置来自 .env.mysql。
 * 环境不可达自动跳过。
 *
 * @author CH
 * @since 4.0.0.42
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlEnginesTest {

    private static final String HOST   = EnvLoader.get("ADMIN_HOST",  EnvLoader.Defaults.HOST);
    private static final int    PORT   = EnvLoader.getInt("ADMIN_PORT", EnvLoader.Defaults.PORT);
    private static final String USER   = EnvLoader.get("ADMIN_USER",  EnvLoader.Defaults.ADMIN_USER);
    private static final String PASS   = EnvLoader.get("ADMIN_PASS",  EnvLoader.Defaults.ADMIN_PASS);
    private static final String DB     = "testdb";

    @BeforeAll
    static void assumeEnv() {
        Assumptions.assumeTrue(reachable(HOST, PORT), "MySQL 不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test @Order(1)
    void blocking_engine_roundtrip() {
        MysqlEngine e = new MysqlEngine();
        e.addDataSource("m", HOST, PORT, DB, USER, PASS);
        var ex = e.getExecutor();
        assertNotNull(ex);
        ex.execute("CREATE TABLE IF NOT EXISTS it_mysql_engine(id INT PRIMARY KEY, name VARCHAR(64))");
        ex.execute("DELETE FROM it_mysql_engine WHERE id = ?", 42);
        int n = ex.execute("INSERT INTO it_mysql_engine(id, name) VALUES(?, ?)", 42, "it-" + System.currentTimeMillis());
        assertEquals(1, n);
        List<Map<String, Object>> rows = ex.query("SELECT name FROM it_mysql_engine WHERE id = ?", 42);
        assertEquals(1, rows.size());
    }

    @Test @Order(2)
    void reactive_engine_query() {
        MysqlReactorEngine e = new MysqlReactorEngine();
        e.addDataSource("r", HOST, PORT, DB, USER, PASS);
        StepVerifier.create(e.query("SELECT id, name FROM it_mysql_engine"))
                .expectNextCount(1)
                .thenConsumeWhile(m -> true)
                .verifyComplete();
    }
}
