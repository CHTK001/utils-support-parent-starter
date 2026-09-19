package com.chua.greptimedb.support.client;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GreptimeJdbcClient} 单元测试：仅覆盖端点解析、URL 推导与生命周期守卫等纯逻辑，
 * 不需要外部 GreptimeDB 服务即可运行。
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeJdbcClientTest {

    /**
     * 校验 gRPC 端点推导为同主机 4002 端口的 MySQL 协议地址。
     */
    @Test
    void shouldDeriveMysqlUrlFromGrpcEndpoint() {
        String url = GreptimeJdbcClient.jdbcUrlFromEndpoint("172.16.0.40:4001", "public");
        assertEquals("jdbc:mysql://172.16.0.40:4002/public"
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=10000&socketTimeout=60000"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", url);
    }

    /**
     * 校验协议前缀与路径段被剥离，数据库名缺省为 public。
     */
    @Test
    void shouldStripSchemeAndPathAndDefaultDatabase() {
        String url = GreptimeJdbcClient.jdbcUrlFromEndpoint("http://greptime.local:4000/sql", null);
        assertTrue(url.startsWith("jdbc:mysql://greptime.local:4002/public?"), url);
    }

    /**
     * 校验 IPv6 端点保留方括号，端口部分被丢弃。
     */
    @Test
    void shouldKeepIpv6Brackets() {
        String url = GreptimeJdbcClient.jdbcUrlFromEndpoint("[::1]:4001", "public");
        assertTrue(url.startsWith("jdbc:mysql://[::1]:4002/public?"), url);
    }

    /**
     * 校验端点中的额外 URL 参数不会污染推导出的连接串（防参数注入）。
     */
    @Test
    void shouldNotLeakExtraParamsFromEndpoint() {
        String url = GreptimeJdbcClient.jdbcUrlFromEndpoint("evil.example.com:4001/x?allowLoadLocalInfile=true",
                "public");
        assertEquals(1, url.chars().filter(c -> c == '?').count());
        assertTrue(url.contains("useSSL=false"), url);
        assertFalse(url.contains("allowLoadLocalInfile"), url);
    }

    /**
     * 校验非法端点与非法库名显式抛错，而不是拼出坏 URL 延迟到建连时才失败。
     */
    @Test
    void shouldRejectIllegalEndpointAndDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> GreptimeJdbcClient.jdbcUrlFromEndpoint("  ", "public"));
        assertThrows(IllegalArgumentException.class,
                () -> GreptimeJdbcClient.jdbcUrlFromEndpoint("host:4001", "bad-db;drop"));
        assertThrows(IllegalArgumentException.class, () -> GreptimeJdbcClient.hostOf("[::14001"));
    }

    /**
     * 校验构造参数缺失时立即抛错。
     */
    @Test
    void shouldRejectBlankJdbcUrl() {
        assertThrows(IllegalArgumentException.class, () -> new GreptimeJdbcClient(" ", null, null));
    }

    /**
     * 校验 close() 之后拒绝执行 SQL，避免关闭后复活连接造成泄漏。
     *
     * @throws SQLException 预期由已关闭客户端抛出
     */
    @Test
    void shouldRefuseExecutionAfterClose() throws SQLException {
        GreptimeJdbcClient client = new GreptimeJdbcClient(
                "jdbc:mysql://127.0.0.1:4002/public", null, null);
        client.close();
        SQLException ex = assertThrows(SQLException.class, () -> client.query("SELECT 1", List.of()));
        assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
        assertThrows(SQLException.class, () -> client.update("DELETE FROM t WHERE id = 1", List.of()));
        // 重复关闭无副作用
        client.close();
    }

    /**
     * 校验空 SQL 显式拒绝。
     *
     * @throws SQLException 预期抛出
     */
    @Test
    void shouldRejectBlankSql() throws SQLException {
        GreptimeJdbcClient client = new GreptimeJdbcClient(
                "jdbc:mysql://127.0.0.1:4002/public", null, null);
        assertThrows(SQLException.class, () -> client.query("  ", List.of()));
        assertEquals(0, client.batch("INSERT INTO t VALUES(?)", List.of()).length);
    }
}
