package com.chua.common.support.network.rpc;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生 TCP NIO RPC 服务端，复用 {@link JdkTcpServer} 长度帧传输层。
 *
 * <p>传输层（连接接收、拼帧、响应回写）由 {@link JdkTcpServer} 承担，
 * 本类只负责 RPC 语义：反序列化请求、方法查找调用、序列化响应。</p>
 *
 * <p>支持直连和注册中心两种模式：
 * <ul>
 *   <li>直连：通过 {@link RpcRegistryConfig#getAddress()} 直接指定监听地址</li>
 *   <li>注册中心：通过 {@link RpcRegistryConfig#getProtocol()} 指定注册中心类型（如 zookeeper/nacos），
 *       自动使用 {@link ServiceDiscovery} SPI 注册服务</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("native")
public class NativeRpcServer implements RpcServer {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(NativeRpcServer.class);

    /**
     * 默认端口
     */
    private static final int DEFAULT_PORT = 18866;

    /**
     * 默认工作线程数
     */
    private static final int DEFAULT_WORKERS = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 端口号
     */
    private final int port;

    /**
     * 主机名
     */
    private final String host;

    /**
     * Worker 线程数
     */
    private final int workerThreads;

    /**
     * IO Selector 线程数
     */
    private final int ioThreadsCount;

    /**
     * APP名称
     */
    private final String appName;

    /**
     * 注册中心配置
     */
    private final List<RpcRegistryConfig> registryConfigs;

    /**
     * 服务注册表
     */
    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 本进程内已注册服务共享注册表（供同 JVM 直调使用）。
     *
     * <p>key 为服务接口全限定名，value 为服务实现对象。客户端开启
     * {@link RpcConsumerConfig#getInline()} 后直接在此查找并本地调用，
     * 绕过 TCP 与序列化。</p>
     */
    static final Map<String, Object> LOCAL_SERVICES = new ConcurrentHashMap<>();

    /**
     * 底层 TCP 长度帧服务端（复用传输层）
     */
    private TcpServer tcpServer;

    /**
     * 服务发现
     */
    private ServiceDiscovery serviceDiscovery;

    /**
     * 服务方法缓存：避免每次请求都走 getMethod 反射查找（热路径开销）
     */
    private final Map<MethodKey, java.lang.reflect.Method> methodCache = new ConcurrentHashMap<>();

    /**
     * 请求/响应编解码器（SPI 序列化，Fury 优先）
     */
    private final RpcSerialization rpcSerialization;

    /**
     * 创建原生 TCP NIO RPC 服务端。
     *
     * @param registryConfigs 注册中心配置列表
     * @param protocolConfig  协议配置
     * @param name            APP 名称
     */
    public NativeRpcServer(List<RpcRegistryConfig> registryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.registryConfigs = registryConfigs;
        this.appName = name;
        this.host = protocolConfig != null && protocolConfig.host() != null ? protocolConfig.host() : "0.0.0.0";
        this.port = protocolConfig != null && protocolConfig.port() != null ? protocolConfig.port() : DEFAULT_PORT;
        this.workerThreads = protocolConfig != null && protocolConfig.threads() != null
                ? protocolConfig.threads() : DEFAULT_WORKERS;
        this.ioThreadsCount = protocolConfig != null && protocolConfig.ioThreads() != null
                && protocolConfig.ioThreads() > 0
                ? protocolConfig.ioThreads() : Runtime.getRuntime().availableProcessors();
        this.rpcSerialization = new RpcSerialization(
                protocolConfig != null ? protocolConfig.serialization() : null);
        log.info("NativeRpcServer serialization: {}", rpcSerialization.name());
        initServiceDiscovery();
    }

    @Override
    /** AfterProperties设置 */
    public void afterPropertiesSet() {
        // 传输层只负责帧收发，RPC 语义（反序列化/方法调用/序列化）在这里挂接
        ServerSetting serverSetting = ServerSetting.defaults();
        serverSetting.setAuto(false);
        serverSetting.setHost(host);
        serverSetting.setPort(port);
        serverSetting.setWorkerThreads(workerThreads);
        this.tcpServer = new JdkTcpServer(serverSetting)
                .setIoThreads(ioThreadsCount)
                .setHandler(this::handleRequest);
        this.tcpServer.start();
        log.info("NativeRpcServer started on {}:{} (ioThreads={}, workers={})",
                host, tcpServer.getPort(), ioThreadsCount, workerThreads);
    }

    /**
     * RPC 帧处理：反序列化请求、调用方法、序列化响应。
     *
     * @param reqData 请求帧字节
     * @return 响应帧字节
     */
    private byte[] handleRequest(byte[] reqData) throws Exception {
        try {
            RpcRequest request = rpcSerialization.deserializeRequest(reqData);
            RpcResponse response = invoke(request);
            return rpcSerialization.serialize(response);
        } catch (Exception e) {
            log.error("Process request error", e);
            return rpcSerialization.serialize(buildErrorResponse(e));
        }
    }

    /** 初始化ServiceDiscovery */
    private void initServiceDiscovery() {
        if (registryConfigs == null || registryConfigs.isEmpty()) {
            return;
        }
        for (RpcRegistryConfig config : registryConfigs) {
            String protocol = config.getProtocol();
            if (protocol != null && !"direct".equals(protocol) && !"native".equals(protocol)) {
                try {
                    DiscoveryOption option = new DiscoveryOption();
                    option.setAddress(config.getAddress());
                    this.serviceDiscovery = ServiceProvider.of(ServiceDiscovery.class).getNewExtension(protocol, option);
                    if (serviceDiscovery != null) {
                        serviceDiscovery.start();
                        log.info("ServiceDiscovery initialized: {}", protocol);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Failed to init ServiceDiscovery: {}", e.getMessage());
                }
            }
        }
    }

    /** 调用 */
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
            // method.invoke 会把业务方法抛出的异常包装成 InvocationTargetException（其 getMessage 为 null，
            // toString 只显示包装类名），必须解包根因，否则客户端拿到的远程错误消息丢失原始信息。
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                    ? ite.getCause() : e;
            response.setSuccess(false);
            response.setError(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
        return response;
    }

    /**
     * 解析并缓存服务方法：热路径下避免每次请求都做 getMethod 反射查找。
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

    /** 构建记录错误Response */
    private RpcResponse buildErrorResponse(Exception e) {
        RpcResponse err = new RpcResponse();
        err.setSuccess(false);
        err.setError(e.getClass().getName() + ": " + e.getMessage());
        return err;
    }

    @Override
    /** 注册 */
    public RpcServer register(String name, Object bean) {
        services.put(name, bean);
        LOCAL_SERVICES.put(name, bean);
        // 注册到 ServiceDiscovery
        if (serviceDiscovery != null) {
            Discovery discovery = Discovery.builder()
                    .serverId(name)
                    .host(host.equals("0.0.0.0") ? "127.0.0.1" : host)
                    .port(port)
                    .protocol("native")
                    .build();
            serviceDiscovery.registerService("/" + appName + "/" + name, discovery);
            log.info("Registered to ServiceDiscovery: {}", name);
        }
        log.info("NativeRpcServer registered: {}", name);
        return this;
    }

    @Override
    /** 关闭 */
    public void close() {
        if (tcpServer != null) {
            try {
                tcpServer.close();
            } catch (IOException ignored) {
            }
        }
        if (serviceDiscovery != null) {
            try {
                serviceDiscovery.close();
            } catch (Exception ignored) {
            }
        }
        methodCache.clear();
        log.info("NativeRpcServer closed");
    }

    /**
     * 服务方法缓存键：服务名 + 方法名 + 参数类型名。
     *
     * @param service    服务名
     * @param method     方法名
     * @param paramTypes 参数类型名数组
     * @since 4.0.0.42
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