package com.chua.kcp.support.scatter;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.scatter.ConnectionPool;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterRemoteClient;
import com.chua.common.support.scatter.ScatterResult;
import com.chua.common.support.scatter.ScatterResultWithRequestId;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.SeedAddress;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * KCP 实现 ScatterRemoteClient。
 *
 * <p>包装 {@link com.chua.kcp.support.sync.KcpSyncClient}，
 * 通过 KCP 向目标节点发送请求并等待响应，requestId 定位响应。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kcp")
public class KcpScatterRemoteClient implements ScatterRemoteClient<Object> {

    /**
     * 传输协议标识：kcp
     */
    private static final String PROTOCOL_KCP = "kcp";

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
    private final ConcurrentHashMap<String, CompletableFuture<ScatterResult<Object>>> pendingResponses = new ConcurrentHashMap<>();

    /**
     * 连接池
     */
    private final ConnectionPool connectionPool;

    /**
     * 节点配置
     */
    private final ScatterSetting setting;

    /**
     * 默认构造，使用默认配置。
     */
    public KcpScatterRemoteClient() {
        this(new ScatterSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 节点配置
     */
    public KcpScatterRemoteClient(ScatterSetting setting) {
        this.setting = setting == null ? new ScatterSetting() : setting;
        this.connectionPool = new ConnectionPool(PROTOCOL_KCP, DEFAULT_MAX_CONNECTIONS, DEFAULT_IDLE_TIMEOUT, true,
                client -> client.subscribe(SYNC_RESPONSE_TOPIC, (topic, message) -> {
                    if (message instanceof ScatterResultWithRequestId<?> wrapper) {
                        CompletableFuture<ScatterResult<Object>> future = pendingResponses.remove(wrapper.requestId());
                        if (future != null) {
                            @SuppressWarnings("unchecked")
                            ScatterResult<Object> result = (ScatterResult<Object>) wrapper.result();
                            future.complete(result);
                        }
                    }
                }));
    }

    /**
     * 解析 seed 地址列表，支持 host:port 格式，未指定端口时使用默认端口。
     *
     * @return 节点列表
     */
    public List<ScatterNode> resolveSeedNodes() {
        if (setting.getSeeds() == null || setting.getSeeds().isEmpty()) {
            return List.of();
        }
        return setting.getSeeds().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseSeedAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 seed 地址。
     *
     * @param address 地址，格式 host 或 host:port
     * @return 节点
     */
    private ScatterNode parseSeedAddress(String address) {
        SeedAddress seed = SeedAddress.parse(address);
        if (seed == null) {
            log.warn("seed 地址格式无效: {}", address);
            return null;
        }
        int port = seed.effectivePort(setting.getDefaultPort() > 0 ? setting.getDefaultPort() : setting.getPort());
        return new ScatterNode(seed.nodeId(port), seed.getHost(), port, PROTOCOL_KCP,
                setting.effectiveGroupId(), null, java.util.Map.of());
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
    public ScatterResult<Object> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) throws Exception {
        SyncClient client = connectionPool.acquireConnected(node.getHost(), node.getPort());
        if (client == null || !client.isConnected()) {
            return ScatterResult.failure(node.getNodeId(), "无法连接到节点: " + node.getEndpoint());
        }

        long effectiveTimeout = timeoutMillis > TIMEOUT_THRESHOLD ? timeoutMillis : DEFAULT_RESPONSE_TIMEOUT;
        String requestId = context.getRequestId();
        CompletableFuture<ScatterResult<Object>> future = new CompletableFuture<>();
        if (requestId != null) {
            pendingResponses.put(requestId, future);
        }

        try {
            client.send(SYNC_REQUEST_TOPIC, context);
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterResult.timeout(node.getNodeId(), "请求超时: " + effectiveTimeout + "ms");
        } catch (ExecutionException e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterResult.failure(node.getNodeId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (Exception e) {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
            return ScatterResult.failure(node.getNodeId(), e.getMessage());
        }
    }

    /**
     * 关闭所有连接。
     */
    @Override
    public void closeAll() {
        connectionPool.closeAll();
        pendingResponses.clear();
    }
}
