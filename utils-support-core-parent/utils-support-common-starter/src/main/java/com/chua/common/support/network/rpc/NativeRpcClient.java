package com.chua.common.support.network.rpc;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.tcp.JdkTcpClient;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 原生 TCP NIO RPC 客户端，复用 {@link TcpClient} 长度帧传输层。
 *
 * <p>传输层（连接池、读写超时、帧收发）由 {@link JdkTcpClient} 承担，
 * 本类只负责 RPC 语义：代理生成、负载均衡、端点容错、请求/响应编解码。</p>
 *
 * <p>安全与健壮性约束：</p>
 * <ul>
 *   <li>连接与读操作均受 {@link RpcConsumerConfig} 超时约束，避免对端无响应时无限阻塞</li>
 *   <li>响应报文长度受传输层 {@code MAX_BODY_SIZE} 上限约束，防止恶意/异常服务端 OOM</li>
 *   <li>反序列化使用 {@link ObjectInputFilter} 拒绝高危 gadget 类（反序列化攻击防护）</li>
 *   <li>对端连接断开时立即抛出异常，避免 {@code read} 返回 {@code -1} 后死循环</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
@Slf4j
@Spi("native")
public class NativeRpcClient implements RpcClient {

    /**
     * 日志
     */

    /**
     * 默认端口
     */
    private static final int DEFAULT_PORT = 18866;

    /**
     * 默认连接超时（毫秒），消费者未配置时使用
     */
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;

    /**
     * 默认读超时（毫秒），消费者未配置时使用
     */
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    /**
     * 候选服务端地址
     */
    private final List<String> addresses = new ArrayList<>();

    /**
     * 服务发现
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * APP 名称
     */
    private final String appName;

    /**
     * 连接超时（毫秒）
     */
    private final int connectTimeout;

    /**
     * 读超时（毫秒）
     */
    private final int readTimeout;

    /**
     * 底层 TCP 长度帧客户端（复用传输层）
     */
    private final TcpClient tcpClient;

    /**
     * 代理缓存
     */
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    /**
     * 负载均衡器（SPI 实例，为空时回退顺序调用）
     */
    private final com.chua.common.support.lang.balance.LoadBalance loadBalance;

    /**
     * 请求/响应编解码器（SPI 序列化，Fury 优先）
     */
    private final RpcSerialization rpcSerialization;

    /**
     * 是否启用同 JVM 直调：目标服务本机已注册时直接调用本地对象，跳过 TCP 与序列化
     */
    private final boolean inlineEnabled;

    /**
     * 创建原生 TCP NIO RPC 客户端。
     *
     * @param registryConfigs 注册中心配置列表
     * @param consumerConfig  消费者配置
     * @param name            APP 名称
     */
    public NativeRpcClient(List<RpcRegistryConfig> registryConfigs, RpcConsumerConfig consumerConfig, String name) {
        int configuredTimeout = consumerConfig != null && consumerConfig.getTimeout() != null
                ? consumerConfig.getTimeout() : DEFAULT_READ_TIMEOUT;
        this.connectTimeout = configuredTimeout > 0 ? configuredTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = configuredTimeout;
        int poolSize = consumerConfig != null && consumerConfig.getConnections() != null
                && consumerConfig.getConnections() > 0 ? consumerConfig.getConnections() : 4;
        this.loadBalance = createLoadBalancer(consumerConfig);
        this.rpcSerialization = new RpcSerialization(
                consumerConfig != null ? consumerConfig.getSerialization() : null);
        this.inlineEnabled = consumerConfig != null && Boolean.TRUE.equals(consumerConfig.getInline());
        log.info("NativeRpcClient serialization: {}, inline: {}", rpcSerialization.name(), inlineEnabled);
        if (registryConfigs == null) {
            registryConfigs = new ArrayList<>();
        }
        this.appName = name;
        ServiceDiscovery sd = null;
        if (registryConfigs != null) {
            for (RpcRegistryConfig config : registryConfigs) {
                String protocol = config.getProtocol();
                if (protocol != null && !"direct".equals(protocol) && !"native".equals(protocol)) {
                    try {
                        DiscoveryOption option = new DiscoveryOption();
                        option.setAddress(config.getAddress());
                        sd = ServiceProvider.of(ServiceDiscovery.class).getNewExtension(protocol, option);
                        if (sd != null) {
                            sd.start();
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to init ServiceDiscovery: {}", e.getMessage());
                    }
                }
                if (config.getAddress() != null) {
                    addresses.add(config.getAddress());
                }
            }
        }
        this.serviceDiscovery = sd;
        if (addresses.isEmpty() && sd == null) {
            addresses.add("localhost:" + DEFAULT_PORT);
        }
        // 复用共享传输层：连接池大小、连接超时、读超时均来自消费者配置
        this.tcpClient = new JdkTcpClient(poolSize, connectTimeout, readTimeout);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    /** 获取 */
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type ->
                ProxyUtils.newProxy((Class<T>) type, type.getClassLoader(),
                        new DelegateMethodIntercept<>((Class<T>) type, new RpcInvoker((Class<T>) type))));
    }

    private class RpcInvoker implements Function<ProxyMethod, Object> {
        private final Class<?> targetType;

        RpcInvoker(Class<?> targetType) {
            this.targetType = targetType;
        }

        @Override
        /** 应用 */
        public Object apply(ProxyMethod pm) {
            // 同 JVM 直调：目标服务已在本进程注册时直接调用，跳过网络与序列化
            Object localService = inlineEnabled ? NativeRpcServer.LOCAL_SERVICES.get(targetType.getName()) : null;
            if (localService != null) {
                return invokeLocal(localService, pm);
            }

            RpcRequest req = new RpcRequest();
            req.setService(targetType.getName());
            req.setMethod(pm.getMethod().getName());
            req.setParamTypes(java.util.Arrays.stream(pm.getMethod().getParameterTypes())
                    .map(Class::getName).toArray(String[]::new));
            req.setArgs(pm.getArgs());

            List<String> targets = new ArrayList<>(addresses);
            if (serviceDiscovery != null) {
                String path = "/" + appName + "/" + targetType.getName();
                Discovery d = serviceDiscovery.getService(path);
                if (d != null) {
                    targets.add(0, d.getHost() + ":" + d.getPort());
                }
            }
            Exception last = null;
            // 按负载均衡策略选择第一个尝试端点；无均衡器或选择失败时退化为顺序遍历
            String first = selectFirst(targets);
            if (first != null && !first.equals(targets.isEmpty() ? null : targets.get(0))) {
                targets.remove(first);
                targets.add(0, first);
            }
            for (String addr : targets) {
                try {
                    return call(addr, req);
                } catch (RpcException e) {
                    // 业务异常：服务端已成功执行并返回「业务失败」，切换端点重试会放大副作用，直接抛出
                    if (e.isBusiness()) {
                        throw e;
                    }
                    log.warn("NativeRPC failed: {}, error: {}", addr, e.toString());
                    last = e;
                } catch (Exception e) {
                    log.warn("NativeRPC failed: {}, error: {}", addr, e.toString());
                    last = e;
                }
            }
            // 不能吞掉真正的远程异常：全部端点失败时保留最后一个失败原因（含原始消息），
            // 否则客户端会把服务端业务异常（如 fail() 的 RuntimeException）替换成无信息的
            // "unreachable"，异常传播语义被破坏。
            if (last != null) {
                throw RpcException.transport("All native RPC endpoints unreachable", last);
            }
            throw RpcException.transport("All native RPC endpoints unreachable");
        }

        /** 调用 */
        private Object call(String addr, RpcRequest req) throws Exception {
            String host = addr.contains(":") ? addr.split(":")[0] : addr;
            int port = addr.contains(":") ? Integer.parseInt(addr.split(":")[1]) : DEFAULT_PORT;
            // 传输层内部维护连接池与超时，此处只做一次请求-响应交换
            byte[] reqData = rpcSerialization.serialize(req);
            byte[] respData = tcpClient.call(host, port, reqData);
            RpcResponse resp = rpcSerialization.deserializeResponse(respData);
            if (!resp.isSuccess()) {
                throw RpcException.business(resp.getError());
            }
            return resp.getResult();
        }

        /**
         * 同 JVM 直调：反射调用本机已注册的服务对象，零网络、零序列化。
         *
         * @param localService 本机服务对象
         * @param pm           代理方法
         * @return 调用结果
         */
        private Object invokeLocal(Object localService, ProxyMethod pm) {
            try {
                java.lang.reflect.Method m = pm.getMethod();
                m.setAccessible(true);
                Object result = ReflectUtils.invoke(localService, m.getName(), Object.class, m.getParameterTypes(), pm.getArgs());
                // 语义对齐：服务端通过 RpcServer 返回 Future 时也做同样解包
                if (result instanceof java.util.concurrent.Future) {
                    return ((java.util.concurrent.Future<?>) result).get();
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) {
                // 服务端抛出的业务异常原样上抛，保持与远程调用一致
                throw RpcException.business(e.getCause() != null ? e.getCause().toString() : e.toString());
            } catch (Exception e) {
                throw RpcException.transport("Inline RPC invoke failed", e);
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        proxyCache.clear();
        try {
            tcpClient.close();
        } catch (IOException ignored) {
        }
        if (serviceDiscovery != null) {
            try {
                serviceDiscovery.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 创建负载均衡器（SPI 加载，策略名取自消费者配置 {@code loadBalance}，默认随机）。
     *
     * @param consumerConfig 消费者配置，可为空
     * @return 负载均衡器实例，SPI 未找到时返回 {@code null}
     */
    private static com.chua.common.support.lang.balance.LoadBalance createLoadBalancer(RpcConsumerConfig consumerConfig) {
        String type = consumerConfig != null && consumerConfig.getLoadBalance() != null
                ? consumerConfig.getLoadBalance() : "random";
        try {
            return com.chua.common.support.lang.balance.LoadBalance.auto(type,
                    com.chua.common.support.lang.balance.BalanceConfig.builder().build());
        } catch (Exception e) {
            log.warn("Load balance '{}' not available, fallback to sequential: {}", type, e.toString());
            return null;
        }
    }

    /**
     * 从候选端点列表中按负载均衡策略选择一个起始端点。
     *
     * @param targets 候选端点列表
     * @return 选中的端点，无候选或均衡器不可用时返回 {@code null}
     */
    private String selectFirst(List<String> targets) {
        if (loadBalance == null || targets == null || targets.isEmpty()) {
            return null;
        }
        return loadBalance.select(new ArrayList<>(targets));
    }
}
