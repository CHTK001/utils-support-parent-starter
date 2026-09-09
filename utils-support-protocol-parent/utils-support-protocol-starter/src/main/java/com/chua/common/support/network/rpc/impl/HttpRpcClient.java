package com.chua.common.support.network.rpc.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.lang.proxy.DelegateMethodIntercept;
import com.chua.common.support.lang.proxy.ProxyMethod;
import com.chua.common.support.lang.proxy.ProxyUtils;
import com.chua.common.support.lang.robin.Node;
import com.chua.common.support.lang.robin.LoadBalance;
import com.chua.common.support.lang.robin.WeightLoadBalance;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.network.rpc.*;
import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.core.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP RPC 客户端
 * <p>支持服务发现和 LZ4 压缩</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/04/02
 */
@Spi("http")
@Slf4j
public class HttpRpcClient implements RpcClient {

    private final LoadBalance loadBalance = new WeightLoadBalance();
    private final List<NetAddress> addresses = new LinkedList<>();
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();
    private final Serialization serialization;
    private final String applicationName;
    private ServiceDiscovery serviceDiscovery;

    public HttpRpcClient(List<RpcRegistryConfig> rpcRegistryConfigs, RpcConsumerConfig rpcConsumerConfig, String name) {
        this.applicationName = name;
        
        // 优先使用 LZ4，如果不可用则使用 JDK 序列化
        Serialization lz4 = ServiceProvider.of(Serialization.class).getNewExtension("lz4");
        this.serialization = lz4 != null ? lz4 : ServiceProvider.of(Serialization.class).getNewExtension("jdk");
        log.info("HttpRpcClient using serialization: {}", serialization.name());

        // 初始化服务发现或直连地址
        initServiceDiscovery(rpcRegistryConfigs);
    }

    private void initServiceDiscovery(List<RpcRegistryConfig> rpcRegistryConfigs) {
        if (rpcRegistryConfigs == null || rpcRegistryConfigs.isEmpty()) {
            return;
        }
        
        for (RpcRegistryConfig config : rpcRegistryConfigs) {
            String protocol = config.getProtocol();
            
            // 如果有服务发现协议，尝试初始化
            if (protocol != null && !"direct".equals(protocol)) {
                try {
                    DiscoveryOption option = new DiscoveryOption();
                    option.setAddress(config.getAddress());
                    this.serviceDiscovery = ServiceProvider.of(ServiceDiscovery.class).getNewExtension(protocol, option);
                    if (serviceDiscovery != null) {
                        serviceDiscovery.start();
                        log.info("HttpRpcClient service discovery initialized: {}", protocol);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Failed to initialize service discovery: {}", e.getMessage());
                }
            }
            
            // 直连模式
            NetAddress addr = NetAddress.of(config.getAddress());
            addresses.add(addr);
            Node node = new Node(addr);
            node.setWeight(config.getWeight() != null ? config.getWeight() : 1);
            loadBalance.addNode(node);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type -> 
            ProxyUtils.newProxy(type, new DelegateMethodIntercept<>(type, new RpcInvoker(type)))
        );
    }

    private class RpcInvoker implements Function<ProxyMethod, Object> {
        private final Class<?> targetType;

        RpcInvoker(Class<?> targetType) {
            this.targetType = targetType;
        }

        @Override
        public Object apply(ProxyMethod proxyMethod) {
            Method method = proxyMethod.getMethod();
            
            // 构建请求
            RpcRequest request = new RpcRequest();
            request.setService(targetType.getName());
            request.setMethod(method.getName());
            request.setParamTypes(Arrays.stream(method.getParameterTypes())
                    .map(Class::getName).toArray(String[]::new));
            request.setArgs(proxyMethod.getArgs());

            // 获取服务地址
            NetAddress address = getServiceAddress(targetType.getName());
            if (address == null) {
                throw new IllegalStateException("No available service for: " + targetType.getName());
            }

            // 发送请求
            try {
                RpcResponse response = sendRequest(address, request);
                if (response.isSuccess()) {
                    return response.getResult();
                } else {
                    throw new IllegalStateException("RPC call failed: " + response.getError());
                }
            } catch (Exception e) {
                log.error("RPC call failed: {}.{}", targetType.getName(), method.getName(), e);
                throw new IllegalStateException("RPC call failed: " + e.getMessage(), e);
            }
        }
    }

    private NetAddress getServiceAddress(String serviceName) {
        // 优先从服务发现获取
        if (serviceDiscovery != null) {
            String path = "/" + applicationName + "/" + serviceName;
            Discovery discovery = serviceDiscovery.getService(path);
            if (discovery != null) {
                return NetAddress.of(discovery.getHost(), discovery.getPort());
            }
        }
        
        // 从配置的地址中负载均衡选择
        Node node = loadBalance.selectNode();
        if (node != null) {
            return node.getValue(NetAddress.class);
        }
        return null;
    }

    private RpcResponse sendRequest(NetAddress address, RpcRequest request) throws Exception {
        try (Socket socket = new Socket(address.getHost(), address.getPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            
            // 序列化并发送请求
            byte[] requestData = serialization.serialize(request);
            out.writeInt(requestData.length);
            out.write(requestData);
            out.flush();
            
            // 读取响应
            int length = in.readInt();
            byte[] responseData = new byte[length];
            in.readFully(responseData);
            
            return serialization.deserialize(responseData, RpcResponse.class);
        }
    }

    @Override
    public void close() throws Exception {
        proxyCache.clear();
        if (serviceDiscovery != null) {
            serviceDiscovery.close();
        }
        log.info("HttpRpcClient closed");
    }
}
