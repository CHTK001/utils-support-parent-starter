package com.chua.ionet.support.client;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.iohao.net.external.core.config.ExternalGlobalConfig;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import com.iohao.net.extension.client.InputCommandRegion;
import com.iohao.net.extension.client.join.ClientRunOne;
import com.iohao.net.extension.client.kit.ClientUserConfigs;
import com.iohao.net.extension.client.user.ClientUser;
import com.iohao.net.extension.client.user.DefaultClientUser;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
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

    /**
     * 服务器主机地址
     */
    private final String host;
    /**
     * 服务器端口号
     */
    private final int port;
    /**
     * 连接方式（TCP/WebSocket）
     */
    private final ExternalJoinEnum joinType;
    /**
     * 输入命令区域列表
     */
    private final List<InputCommandRegion> regions;
    /**
     * 客户端用户对象
     */
    private final ClientUser clientUser;
    /**
     * 是否关闭日志输出
     */
    private final boolean closeLog;
    /**
     * 是否关闭控制台输入扫描
     */
    private final boolean closeScanner;
    /**
     * ClientRunOne 自定义配置器
     */
    private final Consumer<ClientRunOne> configurer;

    /**
     * 连接就绪信号门闩
     */
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    /**
     * 连接状态标记
     */
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** 客户端元数据 */
    private final Map<String, Object> metadata = new HashMap<>();
    /** 主题订阅表（topic -> handler） */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    /** 流程监听器 */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();
    /** 已注册的注解 Bean（beanClass -> 实例） */
    private final Map<Class<?>, Object> annotatedBeans = new ConcurrentHashMap<>();
    /** OnOpen 注解方法列表 */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();
    /** OnClose 注解方法列表 */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();
    /** OnMessage 注解方法列表 */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();
    /** OnError 注解方法列表 */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    /**
     * 创建 IonetSyncClient 实例
     * @param builder builder
     */
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
    /** 连接 */
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

        var clientRunOne = new ClientRunOne()
                .setInputCommandRegions(regions)
                .setConnectAddress(host)
                .setConnectPort(port);

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
            } finally {
                connected.set(true);
                connectionLatch.countDown();
                // @OnOpen 注解分发
                dispatchAnnotatedMethods(onOpenMethods);
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
    /** 断开 */
    public void disconnect() {
        connected.set(false);
        // @OnClose 注解分发
        dispatchAnnotatedMethods(onCloseMethods);
    }

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    /** 获取ClientId */
    public String getClientId() {
        return "ionet-client-" + host + ":" + port;
    }

    @Override
    /** 发送 */
    public void send(String topic, Object message) {
        if (!isConnected()) {
            throw new IllegalStateException("ionet 客户端未连接");
        }
        dispatch(topic, String.valueOf(message));
    }

    @Override
    /** 订阅 */
    public void subscribe(String topic, SyncMessageHandler handler) {
        if (topic != null && handler != null) {
            subscriptions.put(topic, handler);
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncFlowListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取Metadata */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    /**
     * 注册注解 Bean：扫描 {@code @OnOpen/@OnMessage/@OnClose/@OnError} 方法。
     *
     * @param bean 注解处理器实例
     * @return 当前实例
     */
    public IonetSyncClient registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        Class<?> clazz = bean.getClass();
        annotatedBeans.put(clazz, bean);
        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.isAnnotationPresent(OnOpen.class)) {
                onOpenMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnClose.class)) {
                onCloseMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnMessage.class)) {
                String topic = method.getAnnotation(OnMessage.class).value();
                if (topic == null || topic.isEmpty()) {
                    topic = "#";
                }
                onMessageMethods.add(new AnnotatedMessage(clazz, method, topic));
            }
            if (method.isAnnotationPresent(OnError.class)) {
                onErrorMethods.add(new AnnotatedMethod(clazz, method));
            }
        }
        return this;
    }

    /**
     * 分发注解方法（OnOpen/OnClose/OnError，可带参）。
     *
     * @param methods 注解方法列表
     * @param args    方法参数
     */
    private void dispatchAnnotatedMethods(List<AnnotatedMethod> methods, Object... args) {
        for (AnnotatedMethod entry : methods) {
            Object bean = annotatedBeans.get(entry.beanClass());
            if (bean != null) {
                safeInvoke(bean, entry.method(), args);
            }
        }
    }

    /**
     * 按主题分发 OnMessage 注解方法。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    private void dispatchAnnotatedPublish(String topic, String payload) {
        for (AnnotatedMessage entry : onMessageMethods) {
            if (matchTopic(entry.topic(), topic)) {
                Object bean = annotatedBeans.get(entry.beanClass());
                if (bean != null) {
                    safeInvoke(bean, entry.method(), payload);
                }
            }
        }
    }

    /**
     * 主题匹配：支持 # 多级通配、+ / * 单级通配。
     *
     * @param pattern 订阅模式
     * @param topic   实际主题
     * @return true 表示匹配
     */
    private boolean matchTopic(String pattern, String topic) {
        if ("#".equals(pattern)) {
            return true;
        }
        String[] pp = pattern.split("/");
        String[] tp = topic.split("/");
        int p = 0;
        int t = 0;
        while (p < pp.length && t < tp.length) {
            if ("#".equals(pp[p])) {
                return true;
            }
            if ("+".equals(pp[p]) || "*".equals(pp[p])) {
                p++;
                t++;
            } else if (pp[p].equals(tp[t])) {
                p++;
                t++;
            } else {
                return false;
            }
        }
        return p == pp.length && t == tp.length;
    }

    /**
     * 安全调用注解方法，异常走 @OnError 分发。
     *
     * @param bean   目标实例
     * @param method 方法
     * @param args   参数
     */
    private void safeInvoke(Object bean, Method method, Object... args) {
        try {
            if (args == null || args.length == 0) {
                method.invoke(bean);
            } else {
                method.invoke(bean, args);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("ionet 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), cause);
            dispatchAnnotatedMethods(onErrorMethods, cause);
        } catch (Exception e) {
            log.error("ionet 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            dispatchAnnotatedMethods(onErrorMethods, e);
        }
    }

    /**
     * 注解方法元信息。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     */
    private record AnnotatedMethod(Class<?> beanClass, Method method) {
    }

    /**
     * OnMessage 注解方法元信息。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     * @param topic     订阅主题
     */
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {
    }

    /**
     * 本地分发：主题订阅表 + 流程监听器 + @OnMessage 注解。
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
        // @OnMessage 注解分发
        dispatchAnnotatedPublish(topic, payload);
    }

    // ========== Builder ==========

    /** Builder */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * 服务器主机地址，默认 127.0.0.1
         */
        private String host = "127.0.0.1";
        /**
         * 服务器端口号，默认取外部全局端口
         */
        private int port = ExternalGlobalConfig.externalPort;
        /**
         * 连接方式，默认 TCP
         */
        private ExternalJoinEnum joinType = ExternalJoinEnum.TCP;
        /**
         * 输入命令区域列表
         */
        private final List<InputCommandRegion> regions = new ArrayList<>();
        /**
         * 客户端用户对象
         */
        private ClientUser clientUser;
        /**
         * 是否关闭日志输出，默认 true
         */
        private boolean closeLog = true;
        /**
         * 是否关闭控制台输入扫描，默认 true
         */
        private boolean closeScanner = true;
        /**
         * ClientRunOne 自定义配置器
         */
        private Consumer<ClientRunOne> configurer;

        /** Host */
        public Builder host(String host) { this.host = host; return this; }
        /** Port */
        public Builder port(int port) { this.port = port; return this; }
        /** 合并Type */
        public Builder joinType(ExternalJoinEnum joinType) { this.joinType = joinType; return this; }
        /** 添加Region */
        public Builder addRegion(InputCommandRegion region) { this.regions.add(region); return this; }
        /** Regions */
        public Builder regions(List<InputCommandRegion> regions) { this.regions.addAll(regions); return this; }
        /** ClientUser */
        public Builder clientUser(ClientUser clientUser) { this.clientUser = clientUser; return this; }
        /** UserId */
        public Builder userId(long userId) { this.clientUser = new DefaultClientUser(); this.clientUser.setJwt(String.valueOf(userId)); return this; }
        /** 关闭记录日志 */
        public Builder closeLog(boolean close) { this.closeLog = close; return this; }
        /** 关闭Scanner */
        public Builder closeScanner(boolean close) { this.closeScanner = close; return this; }
        /** Configurer */
        public Builder configurer(Consumer<ClientRunOne> configurer) { this.configurer = configurer; return this; }

        /** 构建 */
        public IonetSyncClient build() {
            if (regions.isEmpty()) {
                throw new IllegalArgumentException("At least one InputCommandRegion is required: call .addRegion(region)");
            }
            return new IonetSyncClient(this);
        }
    }
}
