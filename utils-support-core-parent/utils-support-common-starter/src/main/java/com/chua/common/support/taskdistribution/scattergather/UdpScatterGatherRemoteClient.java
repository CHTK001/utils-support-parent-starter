package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.scattergather.ConnectionPool;
import com.chua.common.support.scattergather.ScatterGatherContext;
import com.chua.common.support.scattergather.ScatterGatherNode;
import com.chua.common.support.scattergather.ScatterGatherRemoteClient;
import com.chua.common.support.scattergather.ScatterGatherResult;
import com.chua.common.support.scattergather.ScatterGatherResultWithRequestId;
import com.chua.common.support.scattergather.ScatterGatherSetting;
import com.chua.common.support.scattergather.TransportFallbackStrategy;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * UDP 实现 ScatterGatherRemoteClient。
 *
 * <p>包装 {@link com.chua.common.support.network.sync.impl.UdpSyncClient}，
 * 通过 UDP 向目标节点发送请求并等待响应。支持广播模式，UDP 不可用时通过 {@link TransportFallbackStrategy} 降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UdpScatterGatherRemoteClient implements ScatterGatherRemoteClient<Object> {

    /**
     * 默认响应超时（毫秒）
     */
    private static final long DEFAULT_RESPONSE_TIMEOUT = 10000;

    /**
     * 默认最大连接数
     */
    private static final int DEFAULT_MAX_CONNECTIONS = 50;

    /**
     * 默认空闲超时（毫秒）
     */
    private static final long DEFAULT_IDLE_TIMEOUT = 60_000L;

    /**
     * 响应缓存：requestId -> CompletableFuture
     */
    private final ConcurrentHashMap<String, CompletableFuture<ScatterGatherResult<Object>>> pendingResponses = new ConcurrentHashMap<>();

    /**
     * 连接池
     */
    private final ConnectionPool connectionPool;

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting;

    /**
     * 降级策略
     */
    private final TransportFallbackStrategy fallbackStrategy;

    /**
     * 降级资源清理回调
     */
    private final Runnable fallbackCleanup;

    /**
     * UDP 是否可用
     */
    private boolean udpAvailable = true;

    /**
     * 默认构造，使用默认配置，无降级。
     */
    public UdpScatterGatherRemoteClient() {
        this(new ScatterGatherSetting(), null, null);
    }

    /**
     * 带配置构造，无降级。
     *
     * @param setting 节点配置
     */
    public UdpScatterGatherRemoteClient(ScatterGatherSetting setting) {
        this(setting, null, null);
    }

    /**
     * 完整构造。
     *
     * @param setting         节点配置
     * @param fallbackStrategy 降级策略（UDP 不可用时调用）
     */
    public UdpScatterGatherRemoteClient(ScatterGatherSetting setting, TransportFallbackStrategy fallbackStrategy) {
        this(setting, fallbackStrategy, null);
    }

    /**
     * 完整构造。
     *
     * @param setting          节点配置
     * @param fallbackStrategy 降级策略（UDP 不可用时调用）
     * @param fallbackCleanup  降级资源清理回调
     */
    public UdpScatterGatherRemoteClient(ScatterGatherSetting setting, TransportFallbackStrategy fallbackStrategy,
                                        Runnable fallbackCleanup) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
        this.fallbackStrategy = fallbackStrategy;
        this.fallbackCleanup = fallbackCleanup;
        this.connectionPool = new ConnectionPool("udp", DEFAULT_MAX_CONNECTIONS, DEFAULT_IDLE_TIMEOUT, true,
                client -> client.subscribe("sync/response", (topic, message) -> {
                    if (message instanceof ScatterGatherResultWithRequestId wrapper) {
                        CompletableFuture<ScatterGatherResult<Object>> future = pendingResponses.remove(wrapper.requestId());
                        if (future != null) {
                            future.complete(wrapper.result());
                        }
                    }
                }));
    }

    /**
     * 是否使用广播地址。
     *
     * @param node 目标节点
     * @return 广播地址
     */
    private String resolveAddress(ScatterGatherNode node) {
        if (setting.isUdpBroadcast()) {
            return setting.getUdpBroadcastAddress();
        }
        return node.getHost();
    }

    /**
     * 获取或创建到目标节点的连接。
     *
     * @param node 目标节点
     * @return SyncClient 实例
     */
    private SyncClient getOrCreateClient(ScatterGatherNode node) {
        String address = resolveAddress(node);
        SyncClient client = connectionPool.acquire(address, node.getPort());
        if (client == null || !client.isConnected()) {
            udpAvailable = false;
        }
        return client;
    }

    @Override
    public ScatterGatherResult<Object> invoke(ScatterGatherContext context, ScatterGatherNode node, long timeoutMillis) throws Exception {
        // UDP 不可用时降级
        if (!udpAvailable && fallbackStrategy != null && setting.isUdpFallbackToTcp()) {
            log.debug("UDP 不可用，执行降级策略调用节点: {}", node.getEndpoint());
            return fallbackStrategy.fallbackInvoke(context, node, timeoutMillis);
        }

        SyncClient client = getOrCreateClient(node);
        if (client == null || !client.isConnected()) {
            if (fallbackStrategy != null && setting.isUdpFallbackToTcp()) {
                log.debug("UDP 连接失败，执行降级策略调用节点: {}", node.getEndpoint());
                return fallbackStrategy.fallbackInvoke(context, node, timeoutMillis);
            }
            return ScatterGatherResult.failure(node.getNodeId(), "无法连接到节点: " + node.getEndpoint());
        }

        long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : DEFAULT_RESPONSE_TIMEOUT;
        String requestId = context.getRequestId();
        CompletableFuture<ScatterGatherResult<Object>> future = new CompletableFuture<>();
        if (requestId != null) {
            pendingResponses.put(requestId, future);
        }

        try {
            client.send("sync/request", context);
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterGatherResult.timeout(node.getNodeId(), "请求超时: " + effectiveTimeout + "ms");
        } catch (ExecutionException e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterGatherResult.failure(node.getNodeId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (Exception e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterGatherResult.failure(node.getNodeId(), e.getMessage());
        }
    }

    /**
     * 关闭所有连接。
     */
    public void closeAll() {
        connectionPool.closeAll();
        pendingResponses.clear();
        if (fallbackCleanup != null) {
            fallbackCleanup.run();
        }
    }

    /**
     * 检查 UDP 是否可用。
     *
     * @return true 可用
     */
    public boolean isUdpAvailable() {
        return udpAvailable;
    }
}