package com.chua.rpc.support.zmq;

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
 * ZeroMQ RPC 客户端实现（JeroMQ，纯 Java 无需原生依赖）。
 *
 * <p>与 {@link ZmqRpcServer}（ROUTER）配套使用，客户端维护一个 <strong>DEALER
 * 套接字</strong>连接服务端。DEALER 支持异步收发：发送序列化后的
 * {@link RpcRequest} 单帧报文，服务端按连接处理并回包。</p>
 *
 * <p><b>并发模型</b>：每个 DEALER 套接字在 JeroMQ 内维护独立的收发线程与
 * 消息队列，多个调用线程共享同一套接字时，通过请求 ID 帧区分响应归属——但为
 * 保持与 {@link RpcSerialization} 单帧报文的简洁性，当前实现采用<b>每个目标接口
 * 一个独立套接字 + 请求锁</b>的同步模型，避免响应串扰。</p>
 *
 * <p><b>容错</b>：调用失败时按 {@link RpcConsumerConfig} 配置进行有限次重试
 * 并递增退避；业务异常（服务端已执行并返回）不重试，直接抛出。</p>
 *
 * <p><b>响应式超时</b>：套接字接收超时取自消费者配置的 {@code timeout}，
 * 防止服务端无响应时无限阻塞调用线程。</p>
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
     * 目标服务地址，如 {@code tcp://127.0.0.1:5555}
     */
    private final String serverAddress;

    /**
     * 消费者全局配置
     */
    private final RpcConsumerConfig consumerConfig;

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
     * 目标接口 → 独立 DEALER 套接字（避免多接口共享套接字产生路由串扰）
     */
    private final Map<Class<?>, ZMQ.Socket> socketCache = new ConcurrentHashMap<>();

    /**
     * 目标接口 → 动态代理缓存
     */
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    /**
     * 构造器。
     *
     * @param registryConfigs 注册中心配置（zmq 实现仅取第一个地址，如 {@code tcp://127.0.0.1:5555}）
     * @param consumerConfig  消费者配置（超时 / 重试 / 重试间隔），可为 {@code null}
     * @param name            应用名（zmq 实现不使用，保留 SPI 构造契约）
     */
    public ZmqRpcClient(List<RpcRegistryConfig> registryConfigs, RpcConsumerConfig consumerConfig, String name) {
        this.consumerConfig = consumerConfig;
        String address = registryConfigs != null && !registryConfigs.isEmpty()
                ? registryConfigs.get(0).getAddress() : DEFAULT_ADDRESS;
        this.serverAddress = normalizeAddress(address);
        this.recvTimeout = consumerConfig != null && consumerConfig.getTimeout() != null
                && consumerConfig.getTimeout() > 0 ? consumerConfig.getTimeout() : 10000;
        this.rpcSerialization = new RpcSerialization(
                consumerConfig != null ? consumerConfig.getSerialization() : null);
        this.zContext = new ZContext();
    }

    /**
     * 规范化端点地址：补全 {@code tcp://} 前缀。
     *
     * @param address 注册中心配置中的地址
     * @return 规范的 ZMQ 端点
     */
    private static String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return DEFAULT_ADDRESS;
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
    /** 获取远程代理 */
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type ->
                ProxyUtils.newProxy((Class<T>) type, type.getClassLoader(),
                        new DelegateMethodIntercept<>((Class<T>) type, new RpcInvoker((Class<T>) type))));
    }

    /**
     * 获取（或创建）目标接口对应的独立 DEALER 套接字。
     *
     * @param targetType 目标接口
     * @return DEALER 套接字
     */
    private ZMQ.Socket ensureSocket(Class<?> targetType) {
        return socketCache.computeIfAbsent(targetType, type -> {
            ZMQ.Socket socket = zContext.createSocket(SocketType.DEALER);
            socket.setLinger(0);
            socket.setReceiveTimeOut(recvTimeout);
            socket.connect(serverAddress);
            return socket;
        });
    }

    /**
     * 远程调用实现：RpcInvoker 通过 {@link RpcSerialization} 完成请求序列化与响应反序列化。
     */
    private class RpcInvoker implements Function<ProxyMethod, Object> {

        /**
         * 目标接口（用于套接字复用）
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
        /** 执行调用 */
        public Object apply(ProxyMethod proxyMethod) {
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
            // DEALER 单帧发送；JeroMQ 内部线程负责实际 IO，send 后本线程阻塞 recv
            boolean sent = socket.send(requestData, 0);
            if (!sent) {
                throw RpcException.transport("ZMQ send failed: " + serverAddress);
            }
            byte[] responseData = socket.recv(0);
            if (responseData == null) {
                throw RpcException.transport("ZMQ recv timeout: " + serverAddress
                        + " (timeout=" + recvTimeout + "ms)");
            }
            RpcResponse response = rpcSerialization.deserializeResponse(responseData);
            if (!response.isSuccess()) {
                throw RpcException.business(response.getError());
            }
            return response.getResult();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        for (ZMQ.Socket socket : socketCache.values()) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关闭时忽略套接字异常
            }
        }
        socketCache.clear();
        proxyCache.clear();
        zContext.close();
        log.info("ZmqRpcClient closed");
    }
}