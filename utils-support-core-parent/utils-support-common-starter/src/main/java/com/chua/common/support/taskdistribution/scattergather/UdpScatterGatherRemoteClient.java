package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.scattergather.ScatterGatherContext;
import com.chua.common.support.scattergather.ScatterGatherNode;
import com.chua.common.support.scattergather.ScatterGatherRemoteClient;
import com.chua.common.support.scattergather.ScatterGatherResult;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * UDP 实现 ScatterGatherRemoteClient。
 *
 * <p>包装 {@link com.chua.common.support.network.sync.impl.UdpSyncClient}，
 * 通过 UDP 向目标节点发送请求并等待响应。</p>
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
     * 响应缓存：requestId -> CompletableFuture
     */
    private final ConcurrentHashMap<String, CompletableFuture<ScatterGatherResult<Object>>> pendingResponses = new ConcurrentHashMap<>();

    /**
     * SyncClient 实例缓存：serverUrl -> SyncClient
     */
    private final ConcurrentHashMap<String, SyncClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建到目标节点的连接。
     *
     * @param node 目标节点
     * @return SyncClient 实例
     */
    private SyncClient getOrCreateClient(ScatterGatherNode node) {
        String serverUrl = "udp://" + node.getHost() + ":" + node.getPort();
        return clientCache.computeIfAbsent(serverUrl, url -> {
            SyncClient client = ServiceProvider.of(SyncClient.class).getNewExtension("udp", url);
            if (client != null) {
                client.subscribe("sync/response", (topic, message) -> {
                    if (message instanceof TcpScatterGatherRemoteClient.ScatterGatherResultWithRequestId wrapper) {
                        CompletableFuture<ScatterGatherResult<Object>> future = pendingResponses.remove(wrapper.requestId());
                        if (future != null) {
                            future.complete(wrapper.result());
                        }
                    }
                });
                client.connect();
                log.debug("UDP 客户端已连接到: {}", url);
            }
            return client;
        });
    }

    @Override
    public ScatterGatherResult<Object> invoke(ScatterGatherContext context, ScatterGatherNode node, long timeoutMillis) throws Exception {
        SyncClient client = getOrCreateClient(node);
        if (client == null || !client.isConnected()) {
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
        for (SyncClient client : clientCache.values()) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("关闭客户端连接异常: {}", e.getMessage());
            }
        }
        clientCache.clear();
        pendingResponses.clear();
    }
}