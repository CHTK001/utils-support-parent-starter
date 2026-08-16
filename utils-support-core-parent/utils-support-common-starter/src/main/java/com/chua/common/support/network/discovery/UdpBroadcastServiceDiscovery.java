package com.chua.common.support.network.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于UDP广播的服务发现实现类。
 * 作者：CH

 * @author CH
 * @since 4.0.0.42
 */@Spi("udp-broadcast")
public class UdpBroadcastServiceDiscovery extends AbstractServiceDiscovery implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UdpBroadcastServiceDiscovery.class);

    /**
     * 默认监听端口
     */
    private static final int DEFAULT_PORT = 53321;

    /**
     * UDP数据包缓冲区大小
     */
    private static final int BUFFER_SIZE = 65507;

    /**
     * 记录最后收到消息的服务器ID和对应的时间戳
     */
    private final ConcurrentMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    /**
     * 本地注册的服务列表，线程安全
     */
    private final List<Discovery> localServices = new CopyOnWriteArrayList<>();

    /**
     * 用于处理接收UDP消息的执行器
     */
    private ExecutorService executor;

    /**
     * 用于发送心跳和清理过期服务的调度执行器
     */
    private ScheduledExecutorService scheduler;

    /**
     * 用于发送UDP广播报文的Socket
     */
    private DatagramSocket sendSocket;

    /**
     * 用于接收UDP广播报文的Socket
     */
    private DatagramSocket receiveSocket;

    /**
     * 广播地址
     */
    private InetAddress broadcastAddress;

    /**
     * 服务监听的端口号
     */
    private int port;

    /**
     * 表示当前服务是否正在运行的标志位
     */
    private AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造函数，初始化基础配置
     *
     * @param discoveryOption 服务发现选项
     */
    public UdpBroadcastServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
     * 构造函数，初始化基础配置并指定集群名称
     *
     * @param discoveryOption 服务发现选项
     * @param clusterName     集群名称
     */
    public UdpBroadcastServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    @Override
    public void start() throws IOException {
        if (running.get()) {
            return;
        }
        running.set(true);

        // 设置端口，优先使用配置中的超时时间作为端口，否则使用默认端口
        port = discoveryOption.getConnectionTimeoutMillis() > 0 ? discoveryOption.getConnectionTimeoutMillis() : DEFAULT_PORT;

        String addr = discoveryOption.getAddress();
        // 解析广播地址，如果未配置则使用全局广播地址
        if (StringUtils.isNotEmpty(addr)) {
            broadcastAddress = InetAddress.getByName(addr);
        } else {
            broadcastAddress = InetAddress.getByName("255.255.255.255");
        }

        // 创建发送Socket并启用广播模式
        sendSocket = new DatagramSocket();
        sendSocket.setBroadcast(true);

        // 创建接收Socket并启用广播模式，设置超时时间为1秒
        receiveSocket = new DatagramSocket(port);
        receiveSocket.setBroadcast(true);
        receiveSocket.setSoTimeout(1000);

        // 启动接收线程
        executor = ThreadUtils.newSingleThreadExecutor("udp-broadcast-recv");
        executor.submit(this);

        // 启动心跳发送任务
        scheduler = ThreadUtils.newScheduledThreadPoolExecutor(1, "udp-broadcast-heartbeat");
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 1, 5, TimeUnit.SECONDS);

        // 启动过期清理任务
        scheduler.scheduleAtFixedRate(this::cleanExpired, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        byte[] buf = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                receiveSocket.receive(packet);
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                handleMessage(msg);
            } catch (SocketTimeoutException ignored) {
                // 忽略超时异常
            } catch (IOException e) {
                if (running.get()) {
                    log.error("UDP receive error", e);
                }
            }
        }
    }

    /**
     * 处理接收到的UDP消息
     *
     * @param msg 消息内容
     */
    private void handleMessage(String msg) {
        try {
            UdpMessage um = Json.fromJson(msg, UdpMessage.class);
            if (um == null || StringUtils.isNullOrEmpty(um.getPath())) {
                return;
            }
            String path = StringUtils.startWithAppend(um.getPath(), "/");

            if (um.isRemove()) {
                removeFromCache(path, um.getServerId());
            } else {
                addToCache(path, um.getDiscovery());
                lastSeen.put(um.getServerId(), System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.debug("Failed to parse UDP message: {}", msg, e);
        }
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        localServices.add(discovery);
        addToCache(prefixed, discovery);
        broadcast(createMessage(path, discovery, false));
        return this;
    }

    @Override
    protected void doUnregister(String path, Discovery discovery) {
        broadcast(createMessage(path, discovery, true));
        localServices.removeIf(d -> Objects.equals(d.getServerId(), discovery.getServerId()));
    }

    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        localServices.removeIf(d -> Objects.equals(d.getServerId(), oldDiscovery.getServerId()));
        localServices.add(newDiscovery);
        broadcast(createMessage(path, newDiscovery, false));
    }

    /**
     * 定时发送心跳包
     */
    private void sendHeartbeat() {
        for (Discovery d : localServices) {
            broadcast(createMessage(d.getUriSpec(), d, false));
        }
    }

    /**
     * 广播UDP消息
     *
     * @param msg 要广播的消息
     */
    private void broadcast(UdpMessage msg) {
        try {
            byte[] data = Json.toJson(msg).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, port);
            sendSocket.send(packet);
        } catch (IOException e) {
            log.debug("Broadcast error", e);
        }
    }

    /**
     * 创建UDP消息对象
     *
     * @param path   服务路径
     * @param d      服务发现信息
     * @param remove 是否为移除操作
     * @return UDP消息对象
     */
    private UdpMessage createMessage(String path, Discovery d, boolean remove) {
        UdpMessage msg = new UdpMessage();
        msg.setPath(path);
        msg.setDiscovery(d);
        msg.setServerId(d.getServerId());
        msg.setRemove(remove);
        return msg;
    }

    /**
     * 清理过期的服务记录
     */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        long expiry = 15000;

        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            if (now - entry.getValue() > expiry) {
                for (String path : localCache.keySet()) {
                    removeFromCache(path, entry.getKey());
                }
                lastSeen.remove(entry.getKey());
            }
        }
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        scheduler.scheduleAtFixedRate(() -> {
            String path = StringUtils.startWithAppend(serviceName, "/");
            Set<Discovery> services = get(path);

            if (CollectionUtils.size(services) > 0) {
                for (Discovery d : services) {
                    listener.listen(serviceName, d, Event.ADD);
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        running.set(false);

        // 发送注销广播
        for (Discovery d : localServices) {
            broadcast(createMessage(d.getUriSpec(), d, true));
        }

        if (executor != null) {
            executor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (sendSocket != null) {
            sendSocket.close();
        }
        if (receiveSocket != null) {
            receiveSocket.close();
        }
        clearCache();
    }

    /**
     * UDP广播消息内部类
     */
    public static class UdpMessage {
        /**
         * 服务路径
         */
        private String path;

        /**
         * 服务器唯一标识
         */
        private String serverId;

        /**
         * 服务发现详细信息
         */
        private Discovery discovery;

        /**
         * 标记是否为移除操作
         */
        private boolean remove;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public Discovery getDiscovery() {
            return discovery;
        }

        public void setDiscovery(Discovery discovery) {
            this.discovery = discovery;
        }

        public boolean isRemove() {
            return remove;
        }

        public void setRemove(boolean remove) {
            this.remove = remove;
        }
    }
}
