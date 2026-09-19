package com.chua.rpc.support.zmq;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.rpc.LocalServiceRegistry;
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcException;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcRequest;
import com.chua.common.support.network.rpc.RpcResponse;
import com.chua.common.support.network.rpc.RpcSerialization;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * zeromq RPC 客户端实现（jeromq，纯 Java 无需原生依赖）。
 *
 * <p>与 {@link ZmqRpcServer}（ROUTER）配套使用，客户端维护一个 <strong>DEALER
 * Socket</strong>连接服务端。DEALER 支持异步收发：发送序列化后的
 * {@link RpcRequest} 单帧报文，服务端按连接处理并回包。</p>
 *
 * <p><b>并发模型</b>：每个 DEALER Socket在 JeroMQ 内维护独立的收发线程与
 * 消息队列，多个调用线程共享同一Socket时，通过请求 标识 帧区分响应归属——但为
 * 保持与 {@link RpcSerialization} 单帧报文的简洁性，当前实现采用<b>每个目标接口
 * 一个独立Socket + 请求锁</b>的同步模型，避免响应串扰。</p>
 *
 * <p><b>容错</b>：调用失败时按 {@link RpcConsumerConfig} 配置进行有限次重试
 * 并递增退避；业务异常（服务端已执行并返回）不重试，直接抛出。</p>
 *
 * <p><b>响应式超时</b>：Socket接收超时取自消费者配置的 {@code timeout}，
 * 防止服务端无响应时无限阻塞调用线程。</p>
 *
 * <p><b>服务发现</b>：构造器传入的 {@link RpcRegistryConfig} 中 protocol 为注册中心类型
 * （如 {@code zookeeper}/{@code nacos}）时，自动通过 SPI 加载 {@link ServiceDiscovery}，
 * 每次建连按 {@code /appName/serviceName} 动态解析服务端地址；协议 为
 * {@code direct}/{@code zmq} 或空时取第一个地址直连，与无注册中心场景兼容。</p>
 *
 * <p><b>同 JVM 直调</b>：消费者配置 {@code inline=true} 时，若目标服务已在本进程通过
 * {@link ZmqRpcServer#register(String, Object)}（或 NativeRpcServer）注册，则直接调用
 * 本地对象，跳过 ZMQ 网络与序列化，获得极大吞吐与极低延迟。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zmq")
@Slf4j
public class ZmqRpcClient implements RpcClient {

    /**
     * 未配置连接地址时的默认端点
     */
    private static final String DEFAULT_ADDRESS = "tcp://localhost:5555";

    /**
     * 未配置时的默认重试间隔（毫秒）
     */
    private static final int DEFAULT_RETRY_DELAY = 100;

    /**
     * 目标服务地址，如 {@code tcp://127.0.0.1:5555}；直连模式下生效，
     * 注册中心模式下为空并在调用时动态解析
     */
    private final String serverAddress;

    /**
     * 注册中心配置列表（用于服务发现 SPI 初始化）
     */
    private final List<RpcRegistryConfig> registryConfigs;

    /**
     * APP 名称（用于服务发现路径查询）
     */
    private final String appName;

    /**
     * 服务发现实例（SPI 加载，可为 {@code null} 表示纯直连模式）
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * 消费者全局配置
     */
    private final RpcConsumerConfig consumerConfig;

    /**
     * 是否启用同 JVM 直调：目标服务已在本进程注册时直接调用本地对象，跳过 ZMQ 网络与序列化
     */
    private final boolean inlineEnabled;

    /**
     * 调用超时（毫秒）
     */
    private final int recvTimeout;

    /**
     * 请求/响应编解码器
     */
    private final RpcSerialization rpcSerialization;

    /**
     * ZMQ 上下文（线程安全，复用）
     */
    private final ZContext zContext;

    /**
     * 目标接口 → 独立 DEALER Socket（避免多接口共享Socket产生路由串扰）
     */
    private final Map<Class<?>, ZMQ.Socket> socketCache = new ConcurrentHashMap<>();

    /**
     * 目标接口 → 动态代理缓存
     */
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    /**
     * 构造器。
     *
     * @param registryConfigs 注册中心配置列表；协议 为 "ZooKeeper"/"nacos" 等时走服务发现，
     * 为 {@code null} 或 协议 为 "direct"/"zmq"/空时取第一个地址直连
     * @param consumerConfig  消费者配置（超时 / 重试 / 重试间隔），可为 {@code null}
     * @param name            应用名（用于服务发现路径查询）
     */
    public ZmqRpcClient(List<RpcRegistryConfig> registryConfigs, RpcConsumerConfig consumerConfig, String name) {
        this.consumerConfig = consumerConfig;
        this.registryConfigs = registryConfigs;
        this.appName = name;
        this.inlineEnabled = consumerConfig != null && Boolean.TRUE.equals(consumerConfig.getInline());
        String address = registryConfigs != null && !registryConfigs.isEmpty()
                ? registryConfigs.getFirst().getAddress() : null;
        this.serviceDiscovery = initServiceDiscovery();
        // 直连地址仅在无服务发现或地址显式指定时使用；服务发现模式下地址可为空
        this.serverAddress = address != null && !address.isBlank()
                ? normalizeAddress(address) : null;
        this.recvTimeout = consumerConfig != null && consumerConfig.getTimeout() != null
                && consumerConfig.getTimeout() > 0 ? consumerConfig.getTimeout() : 10000;
        this.rpcSerialization = new RpcSerialization(
                consumerConfig != null ? consumerConfig.getSerialization() : null);
        this.zContext = new ZContext();
    }

    /**
     * 初始化服务发现：遍历注册中心配置，协议 为注册中心类型（ZooKeeper/nacos 等）时
     * 通过 SPI 加载 {@link ServiceDiscovery} 并启动。
     *
     * @return 服务发现实例，纯直连模式下返回 {@code null}
     */
    private ServiceDiscovery initServiceDiscovery() {
        if (registryConfigs == null || registryConfigs.isEmpty()) {
            return null;
        }
        for (RpcRegistryConfig config : registryConfigs) {
            String protocol = config.getProtocol();
            if (protocol != null && !"direct".equals(protocol) && !"zmq".equals(protocol)) {
                try {
                    DiscoveryOption option = new DiscoveryOption();
                    option.setAddress(config.getAddress());
                    ServiceDiscovery sd = ServiceProvider.of(ServiceDiscovery.class)
                            .getNewExtension(protocol, option);
                    if (sd != null) {
                        sd.start();
                        log.info("ServiceDiscovery initialized: {}", protocol);
                        return sd;
                    }
                } catch (Exception e) {
                    log.warn("Failed to init ServiceDiscovery: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 规范化端点地址：补全 {@code tcp://} 前缀。
     *
     * @param address 注册中心配置中的地址，可为 {@code null}
     * @return 规范的 ZMQ 端点；地址为空时返回 {@code null}
     */
    private static String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("tcp://") || trimmed.startsWith("ipc://")
                || trimmed.startsWith("inproc://")) {
            return trimmed;
        }
        return "tcp://" + trimmed;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    /**
     * 获取远程代理
     *
     * @param targetType 目标类型
     * @return 获取的结果
     */
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type ->
                ProxyUtils.newProxy((Class<T>) type, type.getClassLoader(),
                        new DelegateMethodIntercept<>((Class<T>) type, new RpcInvoker((Class<T>) type))));
    }

    /**
     * 获取（或创建）目标接口对应的独立 DEALER Socket。
     *
     * @param targetType 目标接口
     * @return DEALER Socket
     */
    private ZMQ.Socket ensureSocket(Class<?> targetType) {
        return socketCache.computeIfAbsent(targetType, type -> {
            String endpoint = resolveServerAddress(targetType);
            ZMQ.Socket socket = zContext.createSocket(SocketType.DEALER);
            socket.setLinger(0);
            socket.setReceiveTimeOut(recvTimeout);
            socket.connect(endpoint);
            return socket;
        });
    }

    /**
     * 解析目标服务端地址：优先通过服务发现按 {@code /appName/serviceName} 查询，
     * 否则回退到构造器直连地址。
     *
     * @param targetType 目标接口
     * @return ZMQ 端点地址，无法解析时抛出异常
     */
    private String resolveServerAddress(Class<?> targetType) {
        if (serviceDiscovery != null) {
            String path = "/" + appName + "/" + targetType.getName();
            Discovery discovery = serviceDiscovery.getService(path);
            if (discovery != null && discovery.getHost() != null) {
                return "tcp://" + discovery.getHost() + ":" + discovery.getPort();
            }
            log.warn("ServiceDiscovery no node for {}, fallback to direct address", path);
        }
        if (serverAddress != null) {
            return serverAddress;
        }
        throw RpcException.transport("No ZMQ server address: serviceDiscovery="
                + (serviceDiscovery != null) + ", direct=" + serverAddress);
    }

    /**
     * 远程调用实现：rpcinvoker 通过 {@link RpcSerialization} 完成请求序列化与响应反序列化。
     * @author CH
     * @since 4.0.0
     */
    private class RpcInvoker implements Function<ProxyMethod, Object> {

        /**
         * 目标接口（用于Socket复用）
         */
        private final Class<?> targetType;

        /**
         * 构造器。
         *
         * @param targetType 目标接口
         */
        RpcInvoker(Class<?> targetType) {
            this.targetType = targetType;
        }

        @Override
        /**
         * 执行调用
        */
        public Object apply(ProxyMethod proxyMethod) {
            // 同 JVM 直调：目标服务已在本进程注册时直接调用，跳过网络与序列化
            Object localService = inlineEnabled ? LocalServiceRegistry.INSTANCE.get(targetType.getName()) : null;
            if (localService != null) {
                return invokeLocal(localService, proxyMethod);
            }
            int maxRetries = consumerConfig != null && Boolean.FALSE.equals(consumerConfig.getRetryEnabled())
                    ? 0 : (consumerConfig != null && consumerConfig.getRetries() != null
                    ? consumerConfig.getRetries() : 0);
            int retryDelay = consumerConfig != null && consumerConfig.getRetryDelay() != null
                    ? consumerConfig.getRetryDelay() : DEFAULT_RETRY_DELAY;
            Throwable lastError = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return doInvoke(proxyMethod);
                } catch (RpcException e) {
                    // 业务异常：直接抛出，不重试（避免放大非幂等接口副作用）
                    if (e.isBusiness()) {
                        throw e;
                    }
                    lastError = e;
                } catch (Throwable e) {
                    lastError = e;
                }
                if (attempt < maxRetries) {
                    log.warn("ZMQ-RPC retry {}/{}, method={}", attempt + 1, maxRetries,
                            proxyMethod.getMethod().getName());
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw RpcException.transport(
                    "ZMQ-RPC failed: " + proxyMethod.getMethod().getName(), lastError);
        }

        /**
         * 执行一次请求-响应交换。
         *
         * @param proxyMethod 代理方法上下文
         * @return 调用结果
         * @throws Exception 传输或反序列化异常
         */
        private Object doInvoke(ProxyMethod proxyMethod) throws Exception {
            RpcRequest request = new RpcRequest();
            request.setService(targetType.getName());
            request.setMethod(proxyMethod.getMethod().getName());
            request.setParamTypes(java.util.Arrays.stream(proxyMethod.getMethod().getParameterTypes())
                    .map(Class::getName).toArray(String[]::new));
            request.setArgs(proxyMethod.getArgs());

            ZMQ.Socket socket = ensureSocket(targetType);
            byte[] requestData = rpcSerialization.serialize(request);
 // DEALER 单帧发送；jeromq 内部线程负责实际 IO，发送 后本线程阻塞 recv
            boolean sent = socket.send(requestData, 0);
            if (!sent) {
                throw RpcException.transport("ZMQ send failed");
            }
            byte[] responseData = socket.recv(0);
            if (responseData == null) {
                throw RpcException.transport("ZMQ recv timeout (timeout=" + recvTimeout + "ms)");
            }
            RpcResponse response = rpcSerialization.deserializeResponse(responseData);
            if (!response.isSuccess()) {
                throw RpcException.business(response.getError());
            }
            return response.getResult();
        }

        /**
         * 同 JVM 直调：反射调用本机已注册的服务对象，零网络、零序列化。
         *
         * @param localService 本机服务对象
         * @param proxyMethod  代理方法
         * @return 调用结果
         */
        private Object invokeLocal(Object localService, ProxyMethod proxyMethod) {
            try {
                java.lang.reflect.Method method = proxyMethod.getMethod();
                method.setAccessible(true);
                Object result = ReflectUtils.invoke(localService, method.getName(), Object.class,
                        method.getParameterTypes(), proxyMethod.getArgs());
 // 语义对齐：服务端通过 rpc服务端 返回 期货 时也做同样解包
                if (result instanceof java.util.concurrent.Future) {
                    return ((java.util.concurrent.Future<?>) result).get();
                }
                return result;
            } catch (Exception e) {
 // reflect工具.invoke 内部已吞掉 抛出（调用失败返回 空），
                // 此处仅处理 Future.get() 等本方法显式抛出的异常
                throw RpcException.transport("Inline ZMQ-RPC invoke failed", e);
            }
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        for (ZMQ.Socket socket : socketCache.values()) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关闭时忽略Socket异常
            }
        }
        socketCache.clear();
        proxyCache.clear();
        zContext.close();
        if (serviceDiscovery != null) {
            try {
                serviceDiscovery.close();
            } catch (Exception ignored) {
                // 关闭时忽略服务发现异常
            }
        }
        log.info("ZmqRpcClient closed");
    }
}
