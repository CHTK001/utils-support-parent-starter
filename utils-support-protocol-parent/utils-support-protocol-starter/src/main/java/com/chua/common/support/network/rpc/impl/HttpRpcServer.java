package com.chua.common.support.network.rpc.impl;

import com.chua.common.support.core.annotation.Order;
import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcRequest;
import com.chua.common.support.network.rpc.RpcResponse;
import com.chua.common.support.network.rpc.RpcServer;
import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.ClassUtils;
import com.chua.common.support.core.utils.IoUtils;
import com.chua.common.support.core.utils.ThreadUtils;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP RPC 服务器
 * <p>支持服务发现和 LZ4 压缩</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/04/02
 */
@Spi("http")
@Slf4j
public class HttpRpcServer implements RpcServer {

    private final Map<String, SortedSet<Event>> tasks = new ConcurrentHashMap<>();
    private final List<ServerSocket> serverSockets = new LinkedList<>();
    private final List<NetAddress> bindAddresses = new LinkedList<>();
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String applicationName;
    private final Serialization serialization;
    private ServiceDiscovery serviceDiscovery;

    public HttpRpcServer(List<RpcRegistryConfig> rpcRegistryConfigs, List<RpcProtocolConfig> rpcProtocolConfigs, String name) {
        this.applicationName = name;
        this.executorService = ThreadUtils.newFixedThreadExecutor(
                Math.max(4, Runtime.getRuntime().availableProcessors()), "http-rpc-server");
        
        // 优先使用 LZ4，如果不可用则使用 JDK 序列化
        Serialization lz4 = ServiceProvider.of(Serialization.class).getNewExtension("lz4");
        this.serialization = lz4 != null ? lz4 : ServiceProvider.of(Serialization.class).getNewExtension("jdk");
        log.info("HttpRpcServer using serialization: {}", serialization.name());

        // 初始化服务发现
        initServiceDiscovery(rpcRegistryConfigs);
        
        // 初始化服务器端口
        initServerSockets(rpcProtocolConfigs);
    }

    private void initServiceDiscovery(List<RpcRegistryConfig> rpcRegistryConfigs) {
        if (rpcRegistryConfigs == null || rpcRegistryConfigs.isEmpty()) {
            return;
        }
        try {
            RpcRegistryConfig config = rpcRegistryConfigs.get(0);
            String protocol = config.getProtocol();
            if (protocol == null) {
                protocol = "default";
            }
            DiscoveryOption option = new DiscoveryOption();
            option.setAddress(config.getAddress());
            this.serviceDiscovery = ServiceProvider.of(ServiceDiscovery.class).getNewExtension(protocol, option);
            if (serviceDiscovery != null) {
                serviceDiscovery.start();
                log.info("HttpRpcServer service discovery initialized: {}", protocol);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize service discovery: {}", e.getMessage());
        }
    }

    private void initServerSockets(List<RpcProtocolConfig> rpcProtocolConfigs) {
        if (rpcProtocolConfigs == null || rpcProtocolConfigs.isEmpty()) {
            return;
        }
        for (RpcProtocolConfig config : rpcProtocolConfigs) {
            try {
                ServerSocket serverSocket = new ServerSocket(config.getPort());
                serverSockets.add(serverSocket);
                bindAddresses.add(NetAddress.of(config.getHost(), config.getPort()));
                log.info("HttpRpcServer bound to {}:{}", config.getHost(), config.getPort());
            } catch (Exception e) {
                log.error("Failed to create ServerSocket on port {}: {}", config.getPort(), e.getMessage());
            }
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (running.compareAndSet(false, true)) {
            for (ServerSocket serverSocket : serverSockets) {
                executorService.submit(() -> acceptConnections(serverSocket));
            }
            log.info("HttpRpcServer started");
        }
    }

    private void acceptConnections(ServerSocket serverSocket) {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executorService.submit(() -> handleRequest(socket));
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error accepting connection: {}", e.getMessage());
                }
            }
        }
    }

    private void handleRequest(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            
            // 读取请求数据长度
            int length = in.readInt();
            byte[] requestData = new byte[length];
            in.readFully(requestData);
            
            // 反序列化请求
            RpcRequest request = serialization.deserialize(requestData, RpcRequest.class);
            RpcResponse response = processRequest(request);
            
            // 序列化响应
            byte[] responseData = serialization.serialize(response);
            out.writeInt(responseData.length);
            out.write(responseData);
            out.flush();
        } catch (Exception e) {
            log.error("Error handling request: {}", e.getMessage());
        }
    }

    private RpcResponse processRequest(RpcRequest request) {
        RpcResponse response = new RpcResponse();
        try {
            SortedSet<Event> events = tasks.get(request.getMethod());
            if (events == null || events.isEmpty()) {
                response.setSuccess(false);
                response.setError("Method not found: " + request.getMethod());
                return response;
            }
            
            Class<?>[] paramTypes = getParamTypes(request.getParamTypes());
            for (Event event : events) {
                if (event.isMatch(paramTypes)) {
                    Object result = event.invoke(request.getArgs());
                    response.setSuccess(true);
                    response.setResult(result);
                    return response;
                }
            }
            response.setSuccess(false);
            response.setError("No matching method found");
        } catch (Exception e) {
            response.setSuccess(false);
            response.setError(e.getMessage());
        }
        return response;
    }

    private Class<?>[] getParamTypes(String[] typeNames) {
        if (typeNames == null) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = ClassUtils.forName(typeNames[i]);
        }
        return types;
    }

    @Override
    public RpcServer register(Object bean) {
        ClassUtils.doWithMethods(bean.getClass(), method -> {
            if (!Modifier.isPublic(method.getModifiers())) {
                return;
            }
            ClassUtils.setAccessible(method);
            Order order = method.getAnnotation(Order.class);
            tasks.computeIfAbsent(method.getName(), it -> new TreeSet<>()).add(Event.builder()
                    .name(method.getName())
                    .method(method)
                    .bean(bean)
                    .parameterCount(method.getParameterCount())
                    .parameters(method.getParameters())
                    .order(null == order ? 0 : order.value())
                    .build());
        });
        return this;
    }

    @Override
    public RpcServer register(String name, Object bean) {
        register(bean);
        // 注册到服务发现
        if (serviceDiscovery != null && !bindAddresses.isEmpty()) {
            NetAddress addr = bindAddresses.get(0);
            Discovery discovery = Discovery.builder()
                .serverId(name)
                .host(addr.getHost())
                .port(addr.getPort())
                .protocol("http")
                .build();
            serviceDiscovery.registerService("/" + applicationName + "/" + name, discovery);
            log.info("Registered service to discovery: {}", name);
        }
        return this;
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        for (ServerSocket serverSocket : serverSockets) {
            IoUtils.closeQuietly(serverSocket);
        }
        serverSockets.clear();
        ThreadUtils.closeQuietly(executorService);
        if (serviceDiscovery != null) {
            serviceDiscovery.close();
        }
        log.info("HttpRpcServer closed");
    }

    @Data
    @Builder
    static class Event implements Comparable<Event> {
        private String name;
        private Method method;
        private Object bean;
        private int parameterCount;
        private Parameter[] parameters;
        private Integer order;

        @Override
        public int compareTo(@NotNull Event o) {
            return Optional.ofNullable(order).orElse(0) - Optional.ofNullable(o.order).orElse(0);
        }

        public boolean isMatch(Class<?>[] forNames) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterCount != forNames.length) {
                return false;
            }
            for (int i = 0; i < forNames.length; i++) {
                if (!parameterTypes[i].isAssignableFrom(forNames[i])) {
                    return false;
                }
            }
            return true;
        }

        public Object invoke(Object[] array) {
            try {
                return method.invoke(bean, array);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to invoke method: " + method.getName(), e);
            }
        }
    }
}
