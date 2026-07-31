package com.chua.common.support.network.rpc;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 原生 TCP NIO RPC 服务端，纯 JDK 实现。
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

    private static final Logger log = LoggerFactory.getLogger(NativeRpcServer.class);
    private static final int HEADER_SIZE = 4;
    private static final int DEFAULT_PORT = 18866;
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
    private final String appName;
    private final List<RpcRegistryConfig> registryConfigs;
    private final Map<String, Object> services = new ConcurrentHashMap<>();
    private final ExecutorService workerPool;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running;
    private Thread selectorThread;
    private ServiceDiscovery serviceDiscovery;

    public NativeRpcServer(List<RpcRegistryConfig> registryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.registryConfigs = registryConfigs;
        this.appName = name;
        this.host = protocolConfig != null && protocolConfig.host() != null ? protocolConfig.host() : "0.0.0.0";
        this.port = protocolConfig != null && protocolConfig.port() != null ? protocolConfig.port() : DEFAULT_PORT;
        this.workerThreads = protocolConfig != null && protocolConfig.threads() != null ? protocolConfig.threads() : DEFAULT_WORKERS;
        this.workerPool = ThreadUtils.newFixedThreadExecutor(workerThreads, "native-rpc-worker");
        initServiceDiscovery();
    }

    @Override
    public void afterPropertiesSet() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(host, port));
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            running = true;
            selectorThread = new Thread(this::eventLoop, "native-rpc-selector");
            selectorThread.start();
            log.info("NativeRpcServer started on {}:{}", host, port);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start NativeRpcServer", e);
        }
    }

    private void initServiceDiscovery() {
        if (registryConfigs == null || registryConfigs.isEmpty()) { return; }
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

    private void eventLoop() {
        while (running) {
            try {
                selector.select(1000);
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) { continue; }
                    if (key.isAcceptable()) { doAccept(key); }
                    else if (key.isReadable()) { doRead(key); }
                }
            } catch (ClosedSelectorException e) { break; }
            catch (IOException e) { log.error("Selector error", e); }
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
        if (sc != null) {
            sc.configureBlocking(false);
            sc.register(selector, SelectionKey.OP_READ, new Attachment());
        }
    }

    private void doRead(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Attachment att = (Attachment) key.attachment();
        if (readFrame(sc, att)) {
            byte[] body = att.bodyBuf.array();
            att.reset();
            workerPool.execute(() -> processRequest(sc, body));
        }
    }

    private boolean readFrame(SocketChannel sc, Attachment att) throws IOException {
        if (att.state == State.HEADER) {
            att.headerBuf.clear();
            int r = sc.read(att.headerBuf);
            if (r == -1) { closeChannel(keyFor(sc)); return false; }
            if (att.headerBuf.position() < HEADER_SIZE) { return false; }
            att.headerBuf.flip();
            att.bodyLen = att.headerBuf.getInt();
            att.bodyBuf = ByteBuffer.allocate(att.bodyLen);
            att.state = State.BODY;
        }
        int r = sc.read(att.bodyBuf);
        if (r == -1) { closeChannel(keyFor(sc)); return false; }
        return !att.bodyBuf.hasRemaining();
    }

    private SelectionKey keyFor(SocketChannel sc) {
        return sc.keyFor(selector);
    }

    private void processRequest(SocketChannel sc, byte[] reqData) {
        try {
            RpcRequest request = deserialize(reqData);
            RpcResponse response = invoke(request);
            byte[] respData = serialize(response);
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + respData.length);
            buf.putInt(respData.length);
            buf.put(respData);
            buf.flip();
            sc.write(buf);
        } catch (Exception e) {
            log.error("Process request error", e);
            RpcResponse err = new RpcResponse();
            err.setSuccess(false);
            err.setError(e.getClass().getName() + ": " + e.getMessage());
            try { sc.write(ByteBuffer.wrap(serialize(err))); } catch (Exception ignored) {}
        }
    }

    private RpcResponse invoke(RpcRequest request) {
        RpcResponse response = new RpcResponse();
        try {
            Object service = services.get(request.getService());
            if (service == null) {
                response.setSuccess(false);
                response.setError("Service not found: " + request.getService());
                return response;
            }
            Class<?>[] paramTypes = resolveParamTypes(request.getParamTypes());
            java.lang.reflect.Method method = service.getClass().getMethod(request.getMethod(), paramTypes);
            if (method == null) { throw new NoSuchMethodException(request.getMethod()); }
            method.setAccessible(true);
            Object result = method.invoke(service, request.getArgs());
            response.setSuccess(true);
            response.setResult(result);
        } catch (Exception e) {
            response.setSuccess(false);
            response.setError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return response;
    }

    private Class<?>[] resolveParamTypes(String[] typeNames) {
        if (typeNames == null) { return new Class<?>[0]; }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = ClassUtils.forName(typeNames[i]);
        }
        return types;
    }

    private void closeChannel(SelectionKey key) {
        if (key != null) { try { key.channel().close(); } catch (IOException ignored) {} key.cancel(); }
    }

    @Override
    public RpcServer register(String name, Object bean) {
        services.put(name, bean);
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
    public void close() {
        running = false;
        try { if (selector != null) { selector.wakeup(); selector.close(); } } catch (IOException ignored) {}
        try { if (serverChannel != null) { serverChannel.close(); } } catch (IOException ignored) {}
        if (serviceDiscovery != null) { try { serviceDiscovery.close(); } catch (Exception ignored) {} }
        ThreadUtils.closeQuietly(workerPool);
        log.info("NativeRpcServer closed");
    }

    private static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) { oos.writeObject(obj); }
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (T) ois.readObject();
        }
    }

    private enum State { HEADER, BODY }

    private static class Attachment {
        final ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
        State state = State.HEADER;
        int bodyLen;
        ByteBuffer bodyBuf;
        void reset() { state = State.HEADER; bodyBuf = null; }
    }
}