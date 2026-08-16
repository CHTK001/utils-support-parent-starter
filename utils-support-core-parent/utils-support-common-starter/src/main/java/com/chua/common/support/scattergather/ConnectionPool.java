package com.chua.common.support.scattergather;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 节点连接池。
 * <p>管理到远程节点的连接，支持最大连接数、空闲超时回收及断线自动重连。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ConnectionPool {

    /**
     * 协议
     */
    private final String protocol;

    /**
     * 连接缓存：serverUrl -> 连接包装
     */
    private final Map<String, ConnectionEntry> connections = new ConcurrentHashMap<>();

    /**
     * 最大连接数，默认 100
     */
    private final int maxConnections;

    /**
     * 空闲超时（毫秒），默认 60秒
     */
    private final long idleTimeoutMillis;

    /**
     * 是否自动重连
     */
    private final boolean autoReconnect;

    /**
     * 响应订阅处理器
     */
    private final Consumer<SyncClient> responseSubscriber;

    /**
     * 回收执行器
     */
    private ScheduledExecutorService cleanupExecutor;

    /**
     * 构造连接池。
     *
     * @param protocol         协议
     * @param maxConnections   最大连接数
     * @param idleTimeoutMillis 空闲超时（毫秒）
     * @param autoReconnect    是否自动重连
     * @param responseSubscriber 响应订阅处理器
     */
    public ConnectionPool(String protocol, int maxConnections, long idleTimeoutMillis, boolean autoReconnect,
                          Consumer<SyncClient> responseSubscriber) {
        this.protocol = protocol;
        this.maxConnections = maxConnections <= 0 ? Integer.MAX_VALUE : maxConnections;
        this.idleTimeoutMillis = idleTimeoutMillis <= 0 ? 60_000L : idleTimeoutMillis;
        this.autoReconnect = autoReconnect;
        this.responseSubscriber = responseSubscriber;
        startCleanup();
    }

    /**
     * 获取或创建连接到目标节点。
     *
     * @param host 主机
     * @param port 端口
     * @return 连接客户端
     */
    public SyncClient getOrCreate(String host, int port) {
        String serverUrl = protocol + "://" + host + ":" + port;
        return connections.computeIfAbsent(serverUrl, url -> createEntry(url, host, port)).client;
    }

    /**
     * 创建连接条目。
     *
     * @param serverUrl 连接地址
     * @param host      主机
     * @param port      端口
     * @return 连接条目
     */
    private ConnectionEntry createEntry(String serverUrl, String host, int port) {
        if (connections.size() >= maxConnections) {
            log.warn("连接池已满 (max={})，无法创建新连接: {}", maxConnections, serverUrl);
            return ConnectionEntry.empty(serverUrl);
        }
        SyncClient client = ServiceProvider.of(SyncClient.class).getNewExtension(protocol, serverUrl);
        if (client != null && responseSubscriber != null) {
            responseSubscriber.accept(client);
        }
        return new ConnectionEntry(serverUrl, client);
    }

    /**
     * 获取连接并记录最近使用时间。
     *
     * @param host 主机
     * @param port 端口
     * @return 连接客户端
     */
    public SyncClient acquire(String host, int port) {
        SyncClient client = getOrCreate(host, port);
        ConnectionEntry entry = connections.get(protocol + "://" + host + ":" + port);
        if (entry != null) {
            entry.touch();
            if (client != null && !client.isConnected() && autoReconnect) {
                try {
                    client.connect();
                    entry.touch();
                } catch (Exception e) {
                    log.warn("连接失败: {}:{} - {}", host, port, e.getMessage());
                }
            }
        }
        return client;
    }

    /**
     * 获取连接并确保已连接。
     * <p>与 {@link #acquire(String, int)} 不同，此方法无论 autoReconnect 配置如何都会尝试连接。</p>
     *
     * @param host 主机
     * @param port 端口
     * @return 连接客户端
     */
    public SyncClient acquireConnected(String host, int port) {
        SyncClient client = getOrCreate(host, port);
        ConnectionEntry entry = connections.get(protocol + "://" + host + ":" + port);
        if (entry != null) {
            entry.touch();
            if (client != null && !client.isConnected()) {
                try {
                    client.connect();
                    entry.touch();
                } catch (Exception e) {
                    log.warn("连接失败: {}:{} - {}", host, port, e.getMessage());
                }
            }
        }
        return client;
    }

    /**
     * 启动清理任务。
     */
    private void startCleanup() {
        cleanupExecutor = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("scatter-gather-connection-pool"));
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, idleTimeoutMillis, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理空闲超时连接。
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            ConnectionEntry conn = entry.getValue();
            if (conn.client == null) {
                return true;
            }
            if (now - conn.lastAccess > idleTimeoutMillis) {
                try {
                    conn.client.disconnect();
                } catch (Exception e) {
                    log.debug("关闭空闲连接异常: {}", e.getMessage());
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 关闭所有连接。
     */
    public void closeAll() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        for (ConnectionEntry entry : connections.values()) {
            if (entry.client != null) {
                try {
                    entry.client.disconnect();
                } catch (Exception e) {
                    log.debug("关闭连接异常: {}", e.getMessage());
                }
            }
        }
        connections.clear();
    }

    /**
     * 连接条目。
     */
    private static class ConnectionEntry {
        /**
         * 连接地址
         */
        final String serverUrl;

        /**
         * 客户端
         */
        final SyncClient client;

        /**
         * 最近访问时间
         */
        volatile long lastAccess;

        /**
         * 构造连接条目。
         *
         * @param serverUrl 地址
         * @param client    客户端
         */
        ConnectionEntry(String serverUrl, SyncClient client) {
            this.serverUrl = serverUrl;
            this.client = client;
            this.lastAccess = System.currentTimeMillis();
        }

        /**
         * 空连接条目。
         *
         * @param serverUrl 地址
         * @return 空条目
         */
        static ConnectionEntry empty(String serverUrl) {
            return new ConnectionEntry(serverUrl, null);
        }

        /**
         * 更新访问时间。
         */
        void touch() {
            this.lastAccess = System.currentTimeMillis();
        }
    }
}
