package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectionPool 连接池测试。
 *
 * @author CH
 */
class ConnectionPoolTest {

    @Test
    void testGetOrCreateReturnsClient() {
        ConnectionPool pool = new ConnectionPool("tcp", 10, 5000, false, null);
        // SPI 提供 TcpSyncClient 实现
        assertNotNull(pool.getOrCreate("127.0.0.1", 19001));
        pool.closeAll();
    }

    @Test
    void testAcquireCreatesClient() {
        ConnectionPool pool = new ConnectionPool("tcp", 10, 5000, false, client -> {
        });
        assertNotNull(pool.acquire("192.168.1.1", 19001));
        pool.closeAll();
    }

    @Test
    void testSameEndpointReusesConnection() {
        ConnectionPool pool = new ConnectionPool("tcp", 10, 5000, false, null);
        Object c1 = pool.getOrCreate("10.0.0.1", 19001);
        Object c2 = pool.getOrCreate("10.0.0.1", 19001);
        assertSame(c1, c2);
        pool.closeAll();
    }

    @Test
    void testMaxConnections() {
        ConnectionPool pool = new ConnectionPool("tcp", 2, 5000, false, null);
        pool.getOrCreate("10.0.0.1", 19001);
        pool.getOrCreate("10.0.0.2", 19001);
        // 超过最大连接数后不再创建新连接
        assertNull(pool.getOrCreate("10.0.0.3", 19001));
        pool.closeAll();
    }

    @Test
    void testCloseAll() {
        ConnectionPool pool = new ConnectionPool("tcp", 10, 5000, false, null);
        pool.getOrCreate("10.0.0.1", 19001);
        pool.getOrCreate("10.0.0.2", 19001);
        // 关闭不应抛异常
        pool.closeAll();
    }

    @Test
    void testNullResponseSubscriber() {
        ConnectionPool pool = new ConnectionPool("udp", 10, 5000, false, null);
        assertNotNull(pool.getOrCreate("127.0.0.1", 19002));
        pool.closeAll();
    }
}