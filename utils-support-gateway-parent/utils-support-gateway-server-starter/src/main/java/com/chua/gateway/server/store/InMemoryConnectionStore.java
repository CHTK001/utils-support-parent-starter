package com.chua.gateway.server.store;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存连接存储（fallback：无 sqlite 驱动或 sqlite 初始化失败时使用）。
 *
 * <p>数据存在 JVM 堆内存中，进程重启后丢失。适合单机临时场景。</p>
 * <p>通过 {@code @Spi} 默认值 "memory" 注册，优先级低于 {@link SqliteConnectionStore}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "memory", order = -100)
public final class InMemoryConnectionStore implements ConnectionStore {

    /**
     * 自定义客户端模式：已有同 (protocol,host,port) 连接时复用
     */
    private final java.util.Map<String, Connection> byTarget = new ConcurrentHashMap<>();

    /**
     * 按 key 索引
     */
    private final java.util.Map<String, Connection> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<Connection> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public Connection upsertByTarget(String protocol, String host, int port, String user, String password) {
        String tkey = protocol + "@" + host + ":" + port;
        Connection existing = byTarget.get(tkey);
        if (existing != null) {
            // 同 target 复用已有连接，不重新插入，避免密码被覆盖导致会话对象漂移
            log.debug("[gateway-server] InMemory 复用连接: {}", tkey);
            return existing;
        }
        Connection conn = new Connection(protocol, host, port, user, password, null);
        byTarget.put(tkey, conn);
        log.info("[gateway-server] InMemory 新建连接: {}", tkey);
        return conn;
    }

    @Override
    public List<String> listKeys() {
        return new ArrayList<>(byKey.keySet());
    }

    @Override
    public void init() {
        // 内存存储无需初始化
        log.info("[gateway-server] InMemoryConnectionStore 初始化完成");
    }

    /**
     * 编程式注入预配置连接（启动时由命令行 / 配置文件加载）。
     *
     * @param connection 预配置连接
     */
    public void putConfigured(Connection connection) {
        if (connection.key() != null && !connection.key().isBlank()) {
            byKey.put(connection.key(), connection);
        }
        String tkey = connection.protocol() + "@" + connection.host() + ":" + connection.port();
        byTarget.put(tkey, connection);
    }
}
