package com.chua.redis.support.engine;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Redis 时序引擎连接串解析。
 *
 * <p>钉住 {@code monitor.timeseries.database} 的语义：对 Redis 是逻辑库下标，
 * 数字生效；influxdb 语义下的 {@code database=monitor} 这类非数字必须被忽略，
 * 否则会把整条时序链路打挂。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("Redis 时序引擎连接串解析")
class RedisSeriesEngineUriTest {

    @Test
    @DisplayName("空配置回退默认地址与 0 号库")
    void defaults() {
        RedisURI uri = RedisSeriesEngine.redisUri(null);
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(6379, uri.getPort());
        assertEquals(0, uri.getDatabase());
    }

    @Test
    @DisplayName("database 为数字时覆盖逻辑库下标")
    void numericDatabaseWins() {
        Properties properties = new Properties();
        properties.setProperty("url", "redis://10.0.0.9:6380");
        properties.setProperty("database", "3");

        RedisURI uri = RedisSeriesEngine.redisUri(properties);
        assertEquals("10.0.0.9", uri.getHost());
        assertEquals(6380, uri.getPort());
        assertEquals(3, uri.getDatabase());
    }

    @Test
    @DisplayName("database 非数字时忽略，保留 url 自带库下标")
    void nonNumericDatabaseIgnored() {
        Properties properties = new Properties();
        properties.setProperty("url", "redis://10.0.0.9:6380/2");
        properties.setProperty("database", "monitor");

        RedisURI uri = RedisSeriesEngine.redisUri(properties);
        assertEquals("10.0.0.9", uri.getHost());
        assertEquals(6380, uri.getPort());
        assertEquals(2, uri.getDatabase());
    }

    @Test
    @DisplayName("url 缺省时 database 仍生效")
    void databaseWithoutUrl() {
        Properties properties = new Properties();
        properties.setProperty("database", "5");

        RedisURI uri = RedisSeriesEngine.redisUri(properties);
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(6379, uri.getPort());
        assertEquals(5, uri.getDatabase());
    }
}
