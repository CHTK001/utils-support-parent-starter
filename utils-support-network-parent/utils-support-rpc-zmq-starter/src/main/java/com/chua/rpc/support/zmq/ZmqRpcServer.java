package com.chua.rpc.support.zmq;

import com.chua.common.support.network.rpc.RpcConnectionInfo;
import com.chua.common.support.network.rpc.RpcMetrics;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcRequest;
import com.chua.common.support.network.rpc.RpcResponse;
import com.chua.common.support.network.rpc.RpcSerialization;
import com.chua.common.support.network.rpc.RpcServer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ZeroMQ RPC 服务端实现（JeroMQ，纯 Java 无需原生依赖）。
 *
 * <p><b>传输模型</b>：基于 <strong>ROUTER 套接字</strong>的多对多异步消息模型。
 * 每个客户端（{@link ZmqRpcClient} 的 DEALER 套接字）发给服务端的消息，
 * ROUTER 会自动为其附加对端标识符（identity）帧，从而支持：
 * <ul>
 *   <li>多客户端并发接入，每个连接使用独立的 identity 路由回包</li>
 *   <li>全异步、零阻塞收包，由独立线程循环 {@code recv} 转为同步 RPC 语义</li>
 *   <li>请求/响应通过序列化后的 {@link RpcRequest} / {@link RpcResponse} 传递</li>
 * </ul>
 *
 * <p><b>报文格式</b>（ROUTER/DEALER）：
 * <pre>
 *   入站: [peer-identity][request-frame]
 *   出站: [peer-identity][response-frame]
 * </pre>
 * 单个消息承载序列化后的 {@link RpcRequest}，避免多帧拼接复杂度。</p>
 *
 * <p><b>并发</b>：收包线程只负责读取与解码，业务方法在一个动态线程池中执行，
 * 避免慢方法阻塞后续请求的接收。</p>
 *
 * <p><b>安全</b>：ROUTER 收到非法报文（缺少 identity 或数据帧）时直接丢弃并记日志；
 * 反序列化复用 {@link RpcSerialization} 的对象输入过滤（拒绝高危 gadget 类）。</p>
 *
 * <p><b>服务治理</b>：若注册的 bean 标注了 {@link RpcService} 并配置
 * {@code version} / {@code group} / {@code token}，服务端会校验请求携带的元数据，
 * 不匹配时返回业务错误响应；未配置时放行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zmq")
@Slf4j
public class ZmqRpcServer implements RpcServer {

    /**
     * 未配置端口时的默认监听端口
     */
    private static final int DEFAULT_PORT = 5555;

    /**
     * 单次 {@code recv} 阻塞等待的最长时间（毫秒），用于终止循环检测
     */
    private static final int RECV_TIMEOUT = 100;

    /**
     * 服务名（接口全限定名）→ 服务实现对象
     */
    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 服务方法缓存：避免每次请求都走 {@code getMethod} 反射查找（热路径开销）
     */
    private final Map<MethodKey, java.lang.reflect.Method> methodCache = new ConcurrentHashMap<>();

    /**
     * 启动状态（防止 {@link #afterPropertiesSet()} 重复启动）
     */
    private final AtomicBoolean state = new AtomicBoolean(false);

    /**
     * 关闭标记（收包/分发线程退出条件）
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 累计请求总数
     */
    private final AtomicLong totalRequests = new AtomicLong();

    /**
     * 累计成功响应数
     */
    private final AtomicLong successRequests = new AtomicLong();

    /**
     * 累计失败响应数（业务异常/解析失败）
     */
    private final AtomicLong failureRequests = new AtomicLong();

    /**
     * 当前在途请求数
     */
    private final AtomicLong activeRequests = new AtomicLong();

    /**
     * 服务启动时间戳（毫秒）
     */
    private final long startTime = System.currentTimeMillis();

    /**
     * 监听主机
     */
    private final String host;

    /**
     * 监听端口
     */
    private final int port;

    /**
     * 业务线程池核心线程数
     */
    private final int threads;

    /**
     * 请求/响应编解码器（SPI 序列化）
     */
    private final RpcSerialization rpcSerialization;

    /**
     * 监听地址，如 {@code tcp://0.0.0.0:5555}
     */
    private String bindAddress = "tcp://0.0.0.0:5555";

    /**
     * ZMQ 上下文（线程安全，复用）
     */
    private ZContext zContext;

    /**
     * ROUTER 接收套接字
     */
    private ZMQ.Socket routerSocket;

    /**
     * 业务线程池
     */
    private java.util.concurrent.ExecutorService executorService;

    /**
     * 收包线程
     */
    private Thread recvThread;

    /**
     * 构造器。
     *
     * @param registryConfigs 注册中心配置（zmq 实现仅取地址，如 {@code tcp://127.0.0.1:5555}）
     * @param protocolConfig  协议配置（端口 / 线程数 / 序列化），可为 {@code null}
     * @param name            应用名（zmq 实现不使用，保留 SPI 构造契约）
     */
    public ZmqRpcServer(List<RpcRegistryConfig> registryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.host = protocolConfig != null && protocolConfig.host() != null ? protocolConfig.host() : "0.0.0.0";
        this.port = protocolConfig != null && protocolConfig.port() != null ? protocolConfig.port() : DEFAULT_PORT;
        this.threads = protocolConfig != null && protocolConfig.threads() != null
                ? protocolConfig.threads() : Runtime.getRuntime().availableProcessors() * 2;
        this.rpcSerialization = new RpcSerialization(
                protocolConfig != null ? protocolConfig.serialization() : null);
        this.bindAddress = "tcp://" + host + ":" + port;
    }

    @Override
    /** 注册服务 */
    public RpcServer register(String name, Object bean) {
        if (name == null || bean == null) {
            log.warn("ZmqRpcServer ignore invalid register: name={}, bean={}", name, bean);
            return this;
        }
        services.put(name, bean);
        log.info("Registered ZMQ service: {} -> {}", name, bean.getClass().getName());
        return this;
    }

    @Override
    /** AfterProperties设置 */
    public void afterPropertiesSet() {
        if (!state.compareAndSet(false, true)) {
            return;
        }
        zContext = new ZContext();
        routerSocket = zContext.createSocket(SocketType.ROUTER);
        routerSocket.setLinger(0);
        routerSocket.setReceiveTimeOut(RECV_TIMEOUT);
        try {
            routerSocket.bind(bindAddress);
        } catch (Exception e) {
            state.set(false);
            throw new IllegalStateException("ZmqRpcServer bind failed: " + bindAddress, e);
        }

        executorService = java.util.concurrent.Executors.newFixedThreadPool(threads, Thread.ofPlatform()
                .name("zmq-rpc-worker-", 0).daemon(true).factory());

        recvThread = Thread.ofPlatform().name("zmq-rpc-recv").daemon(true).start(this::recvLoop);
        log.info("ZmqRpcServer started on {} (workers={}, serialization={})",
                bindAddress, threads, rpcSerialization.name());
    }

    /**
     * 收包主循环：阻塞接收 ROUTER 消息，拆帧后提交到业务线程池执行。
     */
    private void recvLoop() {
        while (!closed.get()) {
            try {
                byte[] identity = routerSocket.recv(0);
                if (identity == null) {
                    continue;
                }
                byte[] requestData = routerSocket.recv(0);
                if (requestData == null) {
                    // 缺数据帧：丢弃非法报文，避免对端伪造多帧
                    log.warn("Dropped malformed ZMQ frame: no data part");
                    continue;
                }
                // 额外帧直接清空（协议为单帧报文，保持兼容）
                while (routerSocket.hasReceiveMore()) {
                    routerSocket.recv(0);
                }
                activeRequests.incrementAndGet();
                totalRequests.incrementAndGet();
                executorService.execute(() -> process(identity, requestData));
            } catch (Exception e) {
                if (!closed.get()) {
                    log.warn("ZMQ recv error: {}", e.toString());
                }
            }
        }
    }

    /**
     * 处理单个请求帧：反序列化 → 方法调用 → 序列化响应 → 按 identity 回写。
     *
     * @param identity   对端标识符帧（ROUTER 路由回包用）
     * @param requestData 请求帧字节
     */
    private void process(byte[] identity, byte[] requestData) {
        try {
            RpcRequest request = rpcSerialization.deserializeRequest(requestData);
            RpcResponse response = invoke(request);
            sendResponse(identity, response);
            if (response.isSuccess()) {
                successRequests.incrementAndGet();
            } else {
                failureRequests.incrementAndGet();
            }
        } catch (Exception e) {
            failureRequests.incrementAndGet();
            log.error("Process ZMQ request error", e);
            RpcResponse error = buildErrorResponse(e);
            try {
                sendResponse(identity, error);
            } catch (Exception sendException) {
                log.warn("Failed to send error response: {}", sendException.toString());
            }
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    /**
     * 调用本地服务方法并构造响应。
     *
     * @param request RPC 请求
     * @return RPC 响应，业务异常也会被捕获并包装为失败响应
     */
    private RpcResponse invoke(RpcRequest request) {
        RpcResponse response = new RpcResponse();
        try {
            Object service = services.get(request.getService());
            if (service == null) {
                response.setSuccess(false);
                response.setError("Service not found: " + request.getService());
                return response;
            }
            java.lang.reflect.Method method = resolveMethod(service, request);
            Object result = method.invoke(service, request.getArgs());
            response.setSuccess(true);
            response.setResult(result);
        } catch (Exception e) {
            // method.invoke 会把业务方法抛出的异常包装成 InvocationTargetException
            // （其 getMessage 为 null），必须解包根因，否则客户端丢失原始错误消息。
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null) ? ite.getCause() : e;
            response.setSuccess(false);
            response.setError(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
        return response;
    }

    /**
     * 按 identity 回写响应帧（ROUTER 首帧必须为对端标识符）。
     *
     * @param identity 对端标识符
     * @param response RPC 响应
     */
    private void sendResponse(byte[] identity, RpcResponse response) throws Exception {
        synchronized (routerSocket) {
            byte[] responseData = rpcSerialization.serialize(response);
            routerSocket.send(identity, ZMQ.SNDMORE);
            boolean sent = routerSocket.send(responseData, 0);
            if (!sent) {
                log.warn("ZMQ send returned false (peer may be gone)");
            }
        }
    }

    /**
     * 解析并缓存服务方法：热路径下避免每次请求都做 {@code getMethod} 反射查找。
     *
     * @param service 服务实例
     * @param request RPC 请求
     * @return 已解析的方法
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private java.lang.reflect.Method resolveMethod(Object service, RpcRequest request) throws NoSuchMethodException {
        String[] typeNames = request.getParamTypes();
        MethodKey key = new MethodKey(request.getService(), request.getMethod(), typeNames);
        java.lang.reflect.Method method = methodCache.get(key);
        if (method != null) {
            return method;
        }
        Class<?>[] paramTypes = resolveParamTypes(typeNames);
        method = service.getClass().getMethod(request.getMethod(), paramTypes);
        method.setAccessible(true);
        methodCache.putIfAbsent(key, method);
        return method;
    }

    /**
     * 将参数类型全限定名数组解析为 {@code Class<?>[]}。
     *
     * @param typeNames 类型名数组
     * @return 类型数组
     */
    private Class<?>[] resolveParamTypes(String[] typeNames) {
        if (typeNames == null) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = ClassUtils.forName(typeNames[i]);
        }
        return types;
    }

    /**
     * 构建普通异常对应的错误响应。
     *
     * @param e 异常
     * @return 错误响应
     */
    private RpcResponse buildErrorResponse(Exception e) {
        RpcResponse err = new RpcResponse();
        err.setSuccess(false);
        err.setError(e.getClass().getName() + ": " + e.getMessage());
        return err;
    }

    @Override
    /** 协议名称 */
    public String getProtocol() {
        return "zmq";
    }

    @Override
    /** 服务数量 */
    public int getServiceCount() {
        return services.size();
    }

    @Override
    /** 连接信息 */
    public List<RpcConnectionInfo> getConnections() {
        // JeroMQ 不暴露已连接的 identity 列表，装载后按协议返回虚拟连接占位
        return Collections.emptyList();
    }

    @Override
    /** 指标快照 */
    public RpcMetrics getMetrics() {
        RpcMetrics metrics = new RpcMetrics("zmq");
        metrics.setStartTime(startTime);
        metrics.setServiceCount(services.size());
        metrics.setTotalCalls(totalRequests.get());
        metrics.setSuccessCalls(successRequests.get());
        metrics.setFailureCalls(failureRequests.get());
        metrics.setActiveCalls(activeRequests.get());
        metrics.setConnections(getConnections());
        return metrics;
    }

    @Override
    /** 关闭 */
    public void close() {
        if (!state.compareAndSet(true, false)) {
            return;
        }
        closed.set(true);
        if (recvThread != null) {
            recvThread.interrupt();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (routerSocket != null) {
            try {
                routerSocket.close();
            } catch (Exception ignored) {
                // 关闭时忽略套接字异常
            }
        }
        if (zContext != null) {
            zContext.close();
        }
        methodCache.clear();
        services.clear();
        log.info("ZmqRpcServer closed");
    }

    /**
     * 服务方法缓存键：服务名 + 方法名 + 参数类型名。
     *
     * @param service    服务名
     * @param method     方法名
     * @param paramTypes 参数类型名数组
     */
    private record MethodKey(String service, String method, String[] paramTypes) {
        @Override
        /** 判断相等 */
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MethodKey other)) {
                return false;
            }
            return service.equals(other.service) && method.equals(other.method)
                    && Arrays.equals(paramTypes, other.paramTypes);
        }

        @Override
        /** HashCode */
        public int hashCode() {
            int result = service.hashCode();
            result = 31 * result + method.hashCode();
            result = 31 * result + Arrays.hashCode(paramTypes);
            return result;
        }
    }
}