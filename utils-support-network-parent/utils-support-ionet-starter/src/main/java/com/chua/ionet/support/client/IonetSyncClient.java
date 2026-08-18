package com.chua.ionet.support.client;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.iohao.net.external.core.config.ExternalGlobalConfig;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import com.iohao.net.extension.client.InputCommandRegion;
import com.iohao.net.extension.client.join.ClientRunOne;
import com.iohao.net.extension.client.kit.ClientUserConfigs;
import com.iohao.net.extension.client.user.ClientUser;
import com.iohao.net.extension.client.user.DefaultClientUser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ionet 同步客户端 — 接入 common {@link SyncClient} 体系。
 *
 * <p>保留 iohao 新线程启动 + 连接就绪等待能力，同时提供主题订阅、
 * 消息发送与监听器注册等 SyncClient 语义。启动后连接 iohao 服务端，
 * 消息经本地订阅表/监听器分发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class IonetSyncClient implements SyncClient {

    private final String host;
    private final int port;
    private final ExternalJoinEnum joinType;
    private final List<InputCommandRegion> regions;
    private final ClientUser clientUser;
    private final boolean closeLog;
    private final boolean closeScanner;
    private final Consumer<ClientRunOne> configurer;

    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** 客户端元数据 */
    private final Map<String, Object> metadata = new HashMap<>();
    /** 主题订阅表（topic -> handler） */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    /** 流程监听器 */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    private IonetSyncClient(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.joinType = builder.joinType;
        this.regions = builder.regions;
        this.clientUser = builder.clientUser;
        this.closeLog = builder.closeLog;
        this.closeScanner = builder.closeScanner;
        this.configurer = builder.configurer;
    }

    @Override
    public void connect() {
        startup();
    }

    /**
     * 在新线程中启动客户端，不阻塞当前线程（等价 {@link #connect()}）。
     */
    public void startup() {
        Locale.setDefault(Locale.CHINA);

        // 关闭日志和控制台输入，适合自动化测试
        ClientUserConfigs.closeLog();
        ClientUserConfigs.closeScanner = true;

        // 真实连接回调：仅在 ioaha 客户端真正连上服务端时置位 connected
        var connectOption = new com.iohao.net.extension.client.ClientConnectOption();
        connectOption.setSocketAddress(new java.net.InetSocketAddress(host, port));
        if (clientUser != null) {
            connectOption.setClientUser(clientUser);
        }
        connectOption.setConnectedCallback(() -> {
            connected.set(true);
            connectionLatch.countDown();
        });

        var clientRunOne = new ClientRunOne()
                .setInputCommandRegions(regions)
                .setOption(connectOption);

        if (clientUser != null) {
            clientRunOne.setClientUser(clientUser);
        }

        if (configurer != null) {
            configurer.accept(clientRunOne);
        }

        // 在虚拟线程中启动
        Thread.ofVirtual().name("ionet-client").start(() -> {
            try {
                clientRunOne.startup();
            } catch (Exception e) {
                log.warn("[IonetSyncClient] 启动异常: {}", e.getMessage());
            }
        });

        log.info("[IonetSyncClient] Starting client -> {}:{} joinType={}", host, port, joinType);
    }

    /**
     * 等待客户端连接就绪
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true 如果客户端在超时前连接成功
     */
    public boolean awaitConnection(long timeout, TimeUnit unit) {
        try {
            return connectionLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void disconnect() {
        connected.set(false);
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public String getClientId() {
        return "ionet-client-" + host + ":" + port;
    }

    @Override
    public void send(String topic, Object message) {
        if (!isConnected()) {
            throw new IllegalStateException("ionet 客户端未连接");
        }
        dispatch(topic, String.valueOf(message));
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        if (topic != null && handler != null) {
            subscriptions.put(topic, handler);
        }
    }

    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * 本地分发：主题订阅表 + 流程监听器。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    private void dispatch(String topic, String payload) {
        SyncMessageHandler handler = subscriptions.get(topic);
        if (handler != null) {
            try {
                handler.handle(topic, payload);
            } catch (Exception e) {
                log.error("ionet 订阅处理异常", e);
            }
        }
        for (SyncFlowListener listener : listeners) {
            try {
                listener.onMessage(topic, payload);
            } catch (Exception ignored) {
            }
        }
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String host = "127.0.0.1";
        private int port = ExternalGlobalConfig.externalPort;
        private ExternalJoinEnum joinType = ExternalJoinEnum.TCP;
        private final List<InputCommandRegion> regions = new ArrayList<>();
        private ClientUser clientUser;
        private boolean closeLog = true;
        private boolean closeScanner = true;
        private Consumer<ClientRunOne> configurer;

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder joinType(ExternalJoinEnum joinType) { this.joinType = joinType; return this; }
        public Builder addRegion(InputCommandRegion region) { this.regions.add(region); return this; }
        public Builder regions(List<InputCommandRegion> regions) { this.regions.addAll(regions); return this; }
        public Builder clientUser(ClientUser clientUser) { this.clientUser = clientUser; return this; }
        public Builder userId(long userId) { this.clientUser = new DefaultClientUser(); this.clientUser.setJwt(String.valueOf(userId)); return this; }
        public Builder closeLog(boolean close) { this.closeLog = close; return this; }
        public Builder closeScanner(boolean close) { this.closeScanner = close; return this; }
        public Builder configurer(Consumer<ClientRunOne> configurer) { this.configurer = configurer; return this; }

        public IonetSyncClient build() {
            if (regions.isEmpty()) {
                throw new IllegalArgumentException("At least one InputCommandRegion is required: call .addRegion(region)");
            }
            return new IonetSyncClient(this);
        }
    }
}
