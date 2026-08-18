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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 原生 TCP NIO RPC 客户端，纯 JDK 实现。
 *
 * <p>安全与健壮性约束：</p>
 * <ul>
 *   <li>连接与读操作均受 {@link RpcConsumerConfig} 超时约束，避免对端无响应时无限阻塞</li>
 *   <li>响应报文长度受 {@link #MAX_BODY_SIZE} 上限约束，防止恶意/异常服务端 OOM</li>
 *   <li>反序列化使用 {@link ObjectInputFilter} 拒绝高危 gadget 类（反序列化攻击防护）</li>
 *   <li>对端连接断开时立即抛出异常，避免 {@code read} 返回 {@code -1} 后死循环</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("native")
public class NativeRpcClient implements RpcClient {

    private static final Logger log = LoggerFactory.getLogger(NativeRpcClient.class);
    private static final int HEADER_SIZE = 4;
    private static final int DEFAULT_PORT = 18866;

    /**
     * 响应报文长度上限（字节），默认 8MB，与协议配置一致
     */
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;

    /**
     * 默认连接超时（毫秒），消费者未配置时使用
     */
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;

    /**
     * 默认读超时（毫秒），消费者未配置时使用
     */
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    private final List<String> addresses = new ArrayList<>();
    private final ServiceDiscovery serviceDiscovery;
    private final String appName;

    /**
     * 连接超时（毫秒），取自消费者配置
     */
    private final int connectTimeout;

    /**
     * 读超时（毫秒），取自消费者配置
     */
    private final int readTimeout;

    /**
     * 每个端点复用连接数（连接池大小），取自消费者配置，默认 4
     */
    private final int poolSize;

    /**
     * 端点地址 → 连接池；复用长连接避免高并发下反复建连导致 Windows 临时端口耗尽
     */
    private final Map<String, PooledConnections> pools = new ConcurrentHashMap<>();

    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    /**
     * 负载均衡器（SPI 实例，为空时回退顺序调用）
     */
    private final com.chua.common.support.lang.balance.LoadBalance loadBalance;

    public NativeRpcClient(List<RpcRegistryConfig> registryConfigs, RpcConsumerConfig consumerConfig, String name) {
        int configuredTimeout = consumerConfig != null && consumerConfig.getTimeout() != null
                ? consumerConfig.getTimeout() : DEFAULT_READ_TIMEOUT;
        this.connectTimeout = configuredTimeout > 0 ? configuredTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = configuredTimeout;
        this.poolSize = consumerConfig != null && consumerConfig.getConnections() != null
                && consumerConfig.getConnections() > 0 ? consumerConfig.getConnections() : 4;
        this.loadBalance = createLoadBalancer(consumerConfig);
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

        private Object call(String addr, RpcRequest req) throws Exception {
            String host = addr.contains(":") ? addr.split(":")[0] : addr;
            int port = addr.contains(":") ? Integer.parseInt(addr.split(":")[1]) : DEFAULT_PORT;
            // 复用长连接避免高并发下反复建连耗尽 Windows 临时端口；先借用再归还
            PooledConnections pool = pools.computeIfAbsent(addr,
                    a -> new PooledConnections(poolSize, connectTimeout, readTimeout));
            Exception first = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                SocketChannel ch = pool.borrow(host, port);
                boolean usable = false;
                try {
                    Object result = exchange(ch, req);
                    usable = true;
                    return result;
                } catch (RpcException e) {
                    // 业务异常：服务端已成功执行并返回「业务失败」，重试会放大副作用，直接抛出
                    if (e.isBusiness()) { throw e; }
                    first = e;
                } catch (Exception e) {
                    first = e;
                } finally {
                    pool.recycle(ch, usable);
                }
            }
            throw first;
        }

        /**
         * 在已建立的连接上执行一次请求-响应交换。
         *
         * @param ch  已连接的通道
         * @param req 请求对象
         * @return 服务端返回结果
         * @throws Exception 传输失败或业务异常时抛出
         */
        private Object exchange(SocketChannel ch, RpcRequest req) throws Exception {
            byte[] reqData = serialize(req);
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + reqData.length);
            buf.putInt(reqData.length); buf.put(reqData); buf.flip();
            ch.write(buf);
            ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
            readFully(ch, headerBuf);
            headerBuf.flip();
            int bodyLen = headerBuf.getInt();
            if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
                throw RpcException.transport("Native RPC response too large: " + bodyLen);
            }
            ByteBuffer bodyBuf = ByteBuffer.allocate(bodyLen);
            readFully(ch, bodyBuf); bodyBuf.flip();
            byte[] respData = new byte[bodyLen]; bodyBuf.get(respData);
            RpcResponse resp = deserialize(respData);
            if (!resp.isSuccess()) { throw RpcException.business(resp.getError()); }
            return resp.getResult();
        }

        /**
         * 阻塞式完整读取，带读超时；对端断开（{@code read == -1}）时立即抛异常，避免死循环。
         *
         * @param ch  套接字通道
         * @param buf 目标缓冲区
         * @throws IOException 对端关闭或读取失败时抛出
         */
        private void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
            while (buf.hasRemaining()) {
                int read = ch.read(buf);
                if (read == -1) {
                    throw new IOException("Native RPC connection closed by server");
                }
            }
        }
    }

    /**
     * 端点级连接池：按 {@code capacity} 上限复用长连接。
     *
     * <p>设计要点：借用即独占，归还才可用——同一连接同一时刻只有一个在途请求，
     * 保证服务端异步 worker 写响应时不会发生交错；连接失效时关闭并放回建连额度。</p>
     */
    private static final class PooledConnections {

        /**
         * 空闲连接队列（容量即连接数上限）
         */
        private final ArrayBlockingQueue<SocketChannel> idle;

        /**
         * 已创建连接数（含借用中与空闲中）
         */
        private final AtomicInteger created = new AtomicInteger();

        /**
         * 连接数上限
         */
        private final int capacity;

        /**
         * 连接超时（毫秒），建连与等待空闲连接共用
         */
        private final int connectTimeout;

        /**
         * 读超时（毫秒）
         */
        private final int readTimeout;

        PooledConnections(int capacity, int connectTimeout, int readTimeout) {
            this.capacity = Math.max(capacity, 1);
            this.idle = new ArrayBlockingQueue<>(this.capacity);
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        /**
         * 借用一个可用连接：优先取空闲队列，无空闲且未达上限时新建，否则等待归还。
         *
         * @param host 服务端主机
         * @param port 服务端端口
         * @return 可用的已连接通道（借用方独占，完成后必须 {@link #recycle}）
         * @throws IOException 建连失败或等待超时时抛出
         */
        SocketChannel borrow(String host, int port) throws IOException {
            for (;;) {
                SocketChannel ch = idle.poll();
                if (ch != null) {
                    if (ch.isOpen() && ch.isConnected()) { return ch; }
                    closeQuietly(ch);
                    created.decrementAndGet();
                    continue;
                }
                int cur = created.get();
                if (cur < capacity && created.compareAndSet(cur, cur + 1)) {
                    try {
                        return openChannel(host, port);
                    } catch (IOException e) {
                        created.decrementAndGet();
                        throw e;
                    }
                }
                // 全部连接均被占用，等待有连接被归还
                try {
                    SocketChannel waited = idle.poll(connectTimeout, TimeUnit.MILLISECONDS);
                    if (waited != null) {
                        if (waited.isOpen() && waited.isConnected()) { return waited; }
                        closeQuietly(waited);
                        created.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for pooled connection", e);
                }
            }
        }

        /**
         * 归还连接：仍可用则放回空闲队列，否则关闭并释放建连额度。
         *
         * @param ch     借用的通道
         * @param usable 连接是否仍可用（本请求是否成功交换）
         */
        void recycle(SocketChannel ch, boolean usable) {
            if (usable && idle.offer(ch)) {
                return;
            }
            closeQuietly(ch);
            created.decrementAndGet();
        }

        /**
         * 关闭池内全部空闲连接（借用中的由调用方自行归还后关闭）。
         */
        void closeAll() {
            SocketChannel ch;
            while ((ch = idle.poll()) != null) {
                closeQuietly(ch);
                created.decrementAndGet();
            }
        }

        /**
         * 新建并连接一个通道：非阻塞探测完成连接（带超时），随后切回阻塞并设置读超时。
         *
         * @param host 服务端主机
         * @param port 服务端端口
         * @return 已连接通道
         * @throws IOException 建连失败或超时时抛出
         */
        private SocketChannel openChannel(String host, int port) throws IOException {
            SocketChannel ch = SocketChannel.open();
            try {
                ch.configureBlocking(true);
                Socket socket = ch.socket();
                socket.setSoTimeout(readTimeout);
                SocketAddress target = new InetSocketAddress(host, port);
                // 阻塞模式下无法直接给 SocketChannel.connect 传超时，先切非阻塞探测再切回阻塞
                ch.configureBlocking(false);
                boolean connected = ch.connect(target);
                if (!connected) {
                    long deadline = System.currentTimeMillis() + connectTimeout;
                    while (!ch.finishConnect()) {
                        if (System.currentTimeMillis() > deadline) {
                            throw new SocketTimeoutException("Native RPC connect timeout: " + host + ":" + port);
                        }
                        Thread.sleep(10);
                    }
                }
                ch.configureBlocking(true);
                return ch;
            } catch (IOException | RuntimeException e) {
                closeQuietly(ch);
                throw e instanceof IOException ioe ? ioe
                        : new IOException("Failed to connect " + host + ":" + port, e);
            } catch (InterruptedException e) {
                closeQuietly(ch);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while connecting " + host + ":" + port, e);
            }
        }
    }

    /**
     * 安静关闭通道，忽略关闭过程中的异常。
     *
     * @param ch 通道，可为 {@code null}
     */
    private static void closeQuietly(SocketChannel ch) {
        if (ch == null) { return; }
        try { ch.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        proxyCache.clear();
        pools.values().forEach(PooledConnections::closeAll);
        pools.clear();
        if (serviceDiscovery != null) { try { serviceDiscovery.close(); } catch (Exception ignored) { } }
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

    static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) { oos.writeObject(obj); }
        return bos.toByteArray();
    }

    static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            ois.setObjectInputFilter(objectInputFilter());
            return (T) ois.readObject();
        }
    }

    /**
     * 创建反序列化安全过滤器。
     *
     * <p>策略：默认放行普通业务类，但拒绝已知反序列化攻击 gadget 链上的高危类，
     * 同时限制对象图深度与数组长度，防止 {@link #deserialize(byte[])} 被恶意报文利用。</p>
     *
     * @return 对象输入过滤器
     */
    static ObjectInputFilter objectInputFilter() {
        return info -> {
            Class<?> serialClass = info.serialClass();
            if (serialClass == null) {
                return ObjectInputFilter.Status.UNDECIDED;
            }
            if (info.depth() > 64) {
                return ObjectInputFilter.Status.REJECTED;
            }
            if (info.arrayLength() >= 0 && info.arrayLength() > 100_000) {
                return ObjectInputFilter.Status.REJECTED;
            }
            String name = serialClass.getName();
            for (String denied : DENIED_CLASS_PREFIXES) {
                if (name.startsWith(denied)) {
                    return ObjectInputFilter.Status.REJECTED;
                }
            }
            return ObjectInputFilter.Status.UNDECIDED;
        };
    }

    /**
     * 高危反序列化 gadget 类前缀黑名单
     */
    private static final String[] DENIED_CLASS_PREFIXES = {
            "com.sun.", "java.rmi.", "javax.naming.", "javax.management.",
            "org.apache.commons.collections.", "org.apache.commons.beanutils.",
            "org.apache.commons.fileupload.", "org.apache.xbean.",
            "org.springframework.beans.factory.", "org.springframework.context.",
            "org.codehaus.groovy.runtime.", "com.mchange.v2.c3p0.",
            "com.alibaba.fastjson.", "net.sf.json.", "org.jboss.",
            "org.python.core.", "org.mozilla.javascript.", "jdk.internal.",
            "sun.rmi.", "org.apache.dubbo.", "com.caucho."
    };
}