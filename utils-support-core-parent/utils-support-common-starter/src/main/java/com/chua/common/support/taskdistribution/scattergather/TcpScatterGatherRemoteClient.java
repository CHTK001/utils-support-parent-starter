package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.scattergather.ConnectionPool;
import com.chua.common.support.scattergather.ScatterGatherContext;
import com.chua.common.support.scattergather.ScatterGatherNode;
import com.chua.common.support.scattergather.ScatterGatherRemoteClient;
import com.chua.common.support.scattergather.ScatterGatherResult;
import com.chua.common.support.scattergather.ScatterGatherResultWithRequestId;
import com.chua.common.support.scattergather.ScatterGatherSetting;
import com.chua.common.support.scattergather.SeedAddress;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * TCP 实现 ScatterGatherRemoteClient。
 *
 * <p>包装 {@link com.chua.common.support.network.sync.impl.TcpSyncClient}，
 * 通过 TCP 向目标节点发送请求并等待响应。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tcp")
public class TcpScatterGatherRemoteClient implements ScatterGatherRemoteClient<Object> {

    /**
     * 传输协议标识：tcp
     */
    private static final String PROTOCOL_TCP = "tcp";

    /**
     * 同步请求主题
     */
    private static final String SYNC_REQUEST_TOPIC = "sync/request";

    /**
     * 同步响应主题
     */
    private static final String SYNC_RESPONSE_TOPIC = "sync/response";

    /**
     * 默认响应超时（毫秒）
     */
    private static final long DEFAULT_RESPONSE_TIMEOUT = 10000;

    /**
     * 默认最大连接数
     */
    private static final int DEFAULT_MAX_CONNECTIONS = 100;

    /**
     * 默认空闲超时（毫秒）
     */
    private static final long DEFAULT_IDLE_TIMEOUT = 60_000L;

    /**
     * 超时阈值，仅当显式超时大于该值时使用
     */
    private static final long TIMEOUT_THRESHOLD = 0L;

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
     * 默认构造，使用默认配置。
     */
    public TcpScatterGatherRemoteClient() {
        this(new ScatterGatherSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 节点配置
     */
    public TcpScatterGatherRemoteClient(ScatterGatherSetting setting) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
        this.connectionPool = new ConnectionPool(PROTOCOL_TCP, DEFAULT_MAX_CONNECTIONS, DEFAULT_IDLE_TIMEOUT, true,
                client -> client.subscribe(SYNC_RESPONSE_TOPIC, (topic, message) -> {
                    // DIAG: sync/response 消费端
                    log.info("[sync-diag] remoteClient 收到响应 topic={} message={}",
                            topic, message == null ? "null" : message.getClass().getSimpleName());
                    if (message instanceof ScatterGatherResultWithRequestId wrapper) {
                        CompletableFuture<ScatterGatherResult<Object>> future = pendingResponses.remove(wrapper.requestId());
                        if (future != null) {
                            future.complete(wrapper.result());
                        }
                    }
                }));
    }

    /**
     * 获取或创建到目标节点的连接。
     *
     * @param node 目标节点
     * @return SyncClient 实例
     */
    private SyncClient getOrCreateClient(ScatterGatherNode node) {
        return connectionPool.acquireConnected(node.getHost(), node.getPort());
    }

    /**
     * 解析 seed 地址列表，支持 host:port 格式，未指定端口时使用默认全局端口。
     *
     * @return 节点列表
     */
    public List<ScatterGatherNode> resolveSeedNodes() {
        if (setting.getSeedAddresses() == null || setting.getSeedAddresses().isEmpty()) {
            return List.of();
        }
        return setting.getSeedAddresses().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseSeedAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 seed 地址。
     *
     * @param address 地址，格式 host 或 host:port 或 [ipv6]:port
     * @return 节点
     */
    private ScatterGatherNode parseSeedAddress(String address) {
        SeedAddress seed = SeedAddress.parse(address);
        if (seed == null) {
            log.warn("seed 地址格式无效: {}", address);
            return null;
        }
        int port = seed.effectivePort(setting.getDefaultPort());
        return new ScatterGatherNode(seed.nodeId(setting.getDefaultPort()), seed.getHost(), port, PROTOCOL_TCP, null, Map.of());
    }

    /**
     * 超时清理未完成响应，防止内存泄漏。
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public void cleanupPendingResponses(long timeoutMillis) {
        long now = System.currentTimeMillis();
        pendingResponses.entrySet().removeIf(entry -> {
            CompletableFuture<ScatterGatherResult<Object>> future = entry.getValue();
            if (future.isDone()) {
                return true;
            }
            return false;
        });
    }

    /**
     * 向目标节点发起同步调用并等待响应。
     *
     * @param context       查询上下文
     * @param node          目标节点
     * @param timeoutMillis 超时时间（毫秒）
     * @return 查询结果
     */
    @Override
    public ScatterGatherResult<Object> invoke(ScatterGatherContext context, ScatterGatherNode node, long timeoutMillis) throws Exception {
        SyncClient client = getOrCreateClient(node);
        if (client == null || !client.isConnected()) {
            return ScatterGatherResult.failure(node.getNodeId(), "无法连接到节点: " + node.getEndpoint());
        }

        long effectiveTimeout = timeoutMillis > TIMEOUT_THRESHOLD ? timeoutMillis : DEFAULT_RESPONSE_TIMEOUT;
        String requestId = context.getRequestId();
        CompletableFuture<ScatterGatherResult<Object>> future = new CompletableFuture<>();
        if (requestId != null) {
            pendingResponses.put(requestId, future);
        }

        try {
            // DIAG: sync/request 发出端
            log.info("[sync-diag] remoteClient 发出 sync/request node={} requestId={} path={}",
                    node.getEndpoint(), requestId, context == null ? null : context.getPath());
            client.send(SYNC_REQUEST_TOPIC, context);
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
    }
}