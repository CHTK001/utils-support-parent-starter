package com.chua.common.support.network.rpc;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 原生 TCP NIO RPC 客户端，纯 JDK 实现。
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("native")
public class NativeRpcClient implements RpcClient {

    private static final Logger log = LoggerFactory.getLogger(NativeRpcClient.class);
    private static final int HEADER_SIZE = 4;
    private static final int DEFAULT_PORT = 18866;

    private final List<String> addresses = new ArrayList<>();
    private final ServiceDiscovery serviceDiscovery;
    private final String appName;
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    public NativeRpcClient(List<RpcRegistryConfig> registryConfigs, RpcConsumerConfig consumerConfig, String name) {
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
                        if (sd != null) { sd.start(); break; }
                    } catch (Exception e) {
                        log.warn("Failed to init ServiceDiscovery: {}", e.getMessage());
                    }
                }
                if (config.getAddress() != null) { addresses.add(config.getAddress()); }
            }
        }
        this.serviceDiscovery = sd;
        if (addresses.isEmpty() && sd == null) {
            addresses.add("localhost:" + DEFAULT_PORT);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type ->
                ProxyUtils.newProxy((Class<T>) type, type.getClassLoader(),
                        new DelegateMethodIntercept<>((Class<T>) type, new RpcInvoker((Class<T>) type))));
    }

    private class RpcInvoker implements Function<ProxyMethod, Object> {
        private final Class<?> targetType;
        RpcInvoker(Class<?> targetType) { this.targetType = targetType; }

        @Override
        public Object apply(ProxyMethod pm) {
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
                if (d != null) { targets.add(0, d.getHost() + ":" + d.getPort()); }
            }
            for (String addr : targets) {
                try { return call(addr, req); } catch (Exception e) {
                    log.warn("NativeRPC failed: {}, error: {}", addr, e.toString());
                }
            }
            throw new IllegalStateException("All native RPC endpoints unreachable");
        }

        private Object call(String addr, RpcRequest req) throws Exception {
            String host = addr.contains(":") ? addr.split(":")[0] : addr;
            int port = addr.contains(":") ? Integer.parseInt(addr.split(":")[1]) : DEFAULT_PORT;
            try (SocketChannel ch = SocketChannel.open()) {
                ch.configureBlocking(true);
                ch.connect(new InetSocketAddress(host, port));
                byte[] reqData = serialize(req);
                ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + reqData.length);
                buf.putInt(reqData.length); buf.put(reqData); buf.flip();
                ch.write(buf);
                ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
                readFully(ch, headerBuf); headerBuf.flip();
                int bodyLen = headerBuf.getInt();
                ByteBuffer bodyBuf = ByteBuffer.allocate(bodyLen);
                readFully(ch, bodyBuf); bodyBuf.flip();
                byte[] respData = new byte[bodyLen]; bodyBuf.get(respData);
                RpcResponse resp = deserialize(respData);
                if (!resp.isSuccess()) { throw new IOException(resp.getError()); }
                return resp.getResult();
            }
        }

        private void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
            while (buf.hasRemaining()) { ch.read(buf); }
        }
    }

    @Override
    public void close() {
        proxyCache.clear();
        if (serviceDiscovery != null) { try { serviceDiscovery.close(); } catch (Exception ignored) { } }
    }

    static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) { oos.writeObject(obj); }
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (T) ois.readObject();
        }
    }
}