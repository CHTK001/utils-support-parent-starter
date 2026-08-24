package com.chua.sqlserver.support.engine;

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
 * SQL Server 引擎真实服务集成测试（172.16.0.40:1434，库 master）。环境不可达自动跳过。
 *
 * @author CH
 * @since 4.0.0.42
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlServerEnginesTest {

    /**
     * 数据库主机地址
     */
    private static final String HOST = "172.16.0.40";

    /**
     * SQL Server 容器端口
     */
    private static final int PORT = 1434;

    /**
     * 登录用户名
     */
    private static final String USER = "sa";

    /**
     * 登录密码
     */
    private static final String PASS = "YourStrong!Passw0rd";

    /**
     * 目标数据库
     */
    private static final String DB = "master";

    /**
     * 环境可达才执行。
     */
    @BeforeAll
    static void assumeEnv() {
        Assumptions.assumeTrue(reachable(HOST, PORT), "SQL Server 不可达，跳过");
    }

    /**
     * TCP 探测目标端口是否可达。
     *
     * @param host 主机
     * @param port 端口
     * @return true 表示可达
     */
    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 阻塞引擎：建表/写入/查询闭环。
     *
     * <p>SQL Server 不支持 {@code CREATE TABLE IF NOT EXISTS}，改用 OBJECT_ID 判断。</p>
     */
    @Test
    @Order(1)
    void blocking_engine_roundtrip() {
        SqlServerEngine e = new SqlServerEngine();
        e.addDataSource("m", HOST, PORT, DB, USER, PASS);

        var ex = e.getExecutor();
        assertNotNull(ex);
        ex.execute("IF OBJECT_ID('dbo.it_mssql_engine', 'U') IS NULL "
                + "CREATE TABLE dbo.it_mssql_engine(id INT PRIMARY KEY, name VARCHAR(64))");
        ex.execute("DELETE FROM dbo.it_mssql_engine WHERE id = ?", 42);
        int n = ex.execute("INSERT INTO dbo.it_mssql_engine(id, name) VALUES(?, ?)", 42, "it-" + System.currentTimeMillis());
        assertEquals(1, n);

        List<Map<String, Object>> rows = ex.query("SELECT name FROM dbo.it_mssql_engine WHERE id = ?", 42);
        assertEquals(1, rows.size());
    }

    /**
     * 响应式引擎：真实 SQL 查询返回非空 Flux。
     *
     * <p>r2dbc-mssql 不支持参数绑定，核心引擎会自动走 JDBC 路径执行。</p>
     */
    @Test
    @Order(2)
    void reactive_engine_query() {
        SqlServerReactorEngine e = new SqlServerReactorEngine();
        e.addDataSource("r", HOST, PORT, DB, USER, PASS);

        StepVerifier.create(e.query("SELECT id, name FROM dbo.it_mssql_engine"))
                .expectNextCount(1)
                .thenConsumeWhile(m -> true)
                .verifyComplete();
    }
}
