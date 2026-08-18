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
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(NativeRpcServer.class);
    /** Header_size */
    private static final int HEADER_SIZE = 4;
    /** Default_port */
    private static final int DEFAULT_PORT = 18866;
    /** Default_workers */
    private static final int DEFAULT_WORKERS = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 请求报文长度上限（字节），默认 8MB，防止恶意/异常客户端构造超长报文耗尽内存或触发 OOM
     */
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;

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
    /** APP名称 */
    private final String appName;
    /** 注册表configs */
    private final List<RpcRegistryConfig> registryConfigs;
    private final Map<String, Object> services = new ConcurrentHashMap<>();
    /** Worker池 */
    private final ExecutorService workerPool;

    /** 服务器通道 */
    private ServerSocketChannel serverChannel;
    /** 接收连接用 Selector（专用线程） */
    private Selector acceptSelector;
    /** IO Selector 数组（多线程分发读事件，按 ioThreads 配置） */
    private Selector[] ioSelectors;
    /** IO Selector 线程数组 */
    private Thread[] ioThreads;
    /** 下一个 IO Selector 分配游标（轮询注册新连接） */
    private final AtomicInteger ioCursor = new AtomicInteger();
    private volatile boolean running;
    /** Selector线程（接收连接） */
    private Thread acceptThread;
    /** 服务discovery */
    private ServiceDiscovery serviceDiscovery;

    /** IO 线程数，取自协议配置 ioThreads，默认 CPU 核数 */
    private final int ioThreadsCount;

    /**
     * 服务方法缓存：避免每次请求都走 getMethod 反射查找（热路径开销）
     */
    private final Map<MethodKey, java.lang.reflect.Method> methodCache = new ConcurrentHashMap<>();

    public NativeRpcServer(List<RpcRegistryConfig> registryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.registryConfigs = registryConfigs;
        this.appName = name;
        this.host = protocolConfig != null && protocolConfig.host() != null ? protocolConfig.host() : "0.0.0.0";
        this.port = protocolConfig != null && protocolConfig.port() != null ? protocolConfig.port() : DEFAULT_PORT;
        this.workerThreads = protocolConfig != null && protocolConfig.threads() != null ? protocolConfig.threads() : DEFAULT_WORKERS;
        this.ioThreadsCount = protocolConfig != null && protocolConfig.ioThreads() != null && protocolConfig.ioThreads() > 0
                ? protocolConfig.ioThreads() : Runtime.getRuntime().availableProcessors();
        this.workerPool = ThreadUtils.newFixedThreadExecutor(workerThreads, "native-rpc-worker");
        initServiceDiscovery();
    }

    @Override
    public void afterPropertiesSet() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(host, port));
            acceptSelector = Selector.open();
            serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);
            // 启动多个 IO Selector 线程，把读事件分发并行化，避免单线程成为瓶颈
            running = true;
            ioSelectors = new Selector[ioThreadsCount];
            ioThreads = new Thread[ioThreadsCount];
            for (int i = 0; i < ioThreadsCount; i++) {
                final Selector ioSelector = Selector.open();
                ioSelectors[i] = ioSelector;
                final int idx = i;
                ioThreads[i] = new Thread(() -> ioEventLoop(ioSelector), "native-rpc-io-" + idx);
                ioThreads[i].setDaemon(true);
                ioThreads[i].start();
            }
            acceptThread = new Thread(this::acceptLoop, "native-rpc-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("NativeRpcServer started on {}:{} (ioThreads={}, workers={})",
                    host, port, ioThreadsCount, workerThreads);
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

    /**
     * 接收连接专用线程：accept 后轮询注册到某个 IO Selector。
     */
    private void acceptLoop() {
        while (running) {
            try {
                acceptSelector.select(1000);
                Set<SelectionKey> keys = acceptSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) { continue; }
                    if (key.isAcceptable()) { doAccept(key); }
                }
            } catch (ClosedSelectorException e) { break; }
            catch (IOException e) { log.error("Accept selector error", e); }
        }
    }

    /**
     * IO Selector 线程：只负责读事件分发，业务处理交给 worker 线程池。
     *
     * @param ioSelector 该线程专属的 Selector
     */
    private void ioEventLoop(Selector ioSelector) {
        while (running) {
            try {
                ioSelector.select(1000);
                Set<SelectionKey> keys = ioSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) { continue; }
                    if (key.isReadable()) { doRead(key); }
                }
            } catch (ClosedSelectorException e) { break; }
            catch (IOException e) { log.error("IO selector error", e); }
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
        if (sc != null) {
            sc.configureBlocking(false);
            // 轮询选择一个 IO Selector 注册，分散读事件压力
            Selector ioSelector = ioSelectors[Math.floorMod(ioCursor.getAndIncrement(), ioSelectors.length)];
            sc.register(ioSelector, SelectionKey.OP_READ, new Attachment(ioSelector));
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
            // 只在起始位置清空 header，避免半包场景下把已读字节清掉导致数据丢失
            if (att.headerBuf.position() == 0) {
                att.headerBuf.clear();
            }
            int r = sc.read(att.headerBuf);
            if (r == -1) { closeChannel(keyFor(sc, att)); return false; }
            if (att.headerBuf.position() < HEADER_SIZE) { return false; }
            att.headerBuf.flip();
            att.bodyLen = att.headerBuf.getInt();
            if (att.bodyLen <= 0 || att.bodyLen > MAX_BODY_SIZE) {
                closeChannel(keyFor(sc, att));
                throw new IOException("Invalid body length: " + att.bodyLen);
            }
            att.bodyBuf = ByteBuffer.allocate(att.bodyLen);
            att.state = State.BODY;
        }
        int r = sc.read(att.bodyBuf);
        if (r == -1) { closeChannel(keyFor(sc, att)); return false; }
        return !att.bodyBuf.hasRemaining();
    }

    private SelectionKey keyFor(SocketChannel sc, Attachment att) {
        return sc.keyFor(att.ioSelector);
    }

    private void processRequest(SocketChannel sc, byte[] reqData) {
        try {
            RpcRequest request = deserialize(reqData);
            RpcResponse response = invoke(request);
            writeResponse(sc, response);
        } catch (Exception e) {
            log.error("Process request error", e);
            RpcResponse err = new RpcResponse();
            err.setSuccess(false);
            err.setError(e.getClass().getName() + ": " + e.getMessage());
            writeResponse(sc, err);
        }
    }

    /**
     * 将响应完整写入通道。通道为非阻塞模式，必须循环写入直至缓冲区耗尽，避免粘包/半包。
     *
     * @param sc       目标通道
     * @param response 响应对象
     */
    private void writeResponse(SocketChannel sc, RpcResponse response) {
        try {
            byte[] respData = serialize(response);
            if (respData.length > MAX_BODY_SIZE) {
                log.warn("Response too large: {}", respData.length);
                respData = serialize(buildErrorResponse("Response too large"));
            }
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + respData.length);
            buf.putInt(respData.length);
            buf.put(respData);
            buf.flip();
            while (buf.hasRemaining()) {
                int written = sc.write(buf);
                if (written <= 0) {
                    // 非阻塞模式下无更多空间，等待下一轮事件；此处由 worker 直接写，短暂让步避免忙等
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            log.error("Write response error", e);
        }
    }

    private RpcResponse buildErrorResponse(String message) {
        RpcResponse err = new RpcResponse();
        err.setSuccess(false);
        err.setError(message);
        return err;
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
        try { if (acceptSelector != null) { acceptSelector.wakeup(); acceptSelector.close(); } } catch (IOException ignored) {}
        if (ioSelectors != null) {
            for (Selector ioSelector : ioSelectors) {
                try { if (ioSelector != null) { ioSelector.wakeup(); ioSelector.close(); } } catch (IOException ignored) {}
            }
        }
        try { if (serverChannel != null) { serverChannel.close(); } } catch (IOException ignored) {}
        if (serviceDiscovery != null) { try { serviceDiscovery.close(); } catch (Exception ignored) {} }
        methodCache.clear();
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
            // 复用客户端包级过滤策略，拒绝已知高危 gadget 类，防止反序列化攻击
            ois.setObjectInputFilter(NativeRpcClient.objectInputFilter());
            return (T) ois.readObject();
        }
    }

    private enum State { HEADER, BODY }

    private static class Attachment {
        final ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
        /** 归属的 IO Selector（用于 keyFor 关闭连接时定位注册表） */
        final Selector ioSelector;
        State state = State.HEADER;
        int bodyLen;
        ByteBuffer bodyBuf;
        Attachment(Selector ioSelector) {
            this.ioSelector = ioSelector;
        }
        void reset() {
            headerBuf.clear();
            state = State.HEADER;
            bodyBuf = null;
        }
    }

    /**
     * 服务方法缓存键：服务名 + 方法名 + 参数类型名。
     */
    private record MethodKey(String service, String method, String[] paramTypes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) { return true; }
            if (!(o instanceof MethodKey other)) { return false; }
            return service.equals(other.service) && method.equals(other.method)
                    && Arrays.equals(paramTypes, other.paramTypes);
        }

        @Override
        public int hashCode() {
            int result = service.hashCode();
            result = 31 * result + method.hashCode();
            result = 31 * result + Arrays.hashCode(paramTypes);
            return result;
        }
    }
}