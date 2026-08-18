package com.chua.ionet.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import com.iohao.net.framework.core.BarSkeletonBuilder;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * ionet 同步服务器 — 接入 common {@link SyncServer} 体系。
 *
 * <p>委托 {@link IonetServer} 提供统一生命周期与 ServerFilter 链；消息入口
 * （IonetFilterInOut 走 filter 链）通过监听 filter 转发为 {@code onMessage}
 * 通知 {@link SyncServerListener}。保留异步启动 + 等待就绪能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class IonetSyncServer implements SyncServer {

    /**
     * 底层委托的 ionet 服务器
     */
    private final IonetServer delegate;
    /**
     * 服务器线程引用
     */
    private final AtomicReference<Thread> serverThread = new AtomicReference<>();
    /**
     * 启动完成信号门闩
     */
    private final CountDownLatch startupLatch = new CountDownLatch(1);
    /** 客户端元数据（clientId -> metadata） */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /** 同步监听器 */
    /** Listeners */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();
    /** 已注册的注解 Bean（beanClass -> 实例） */
    private final Map<Class<?>, Object> annotatedBeans = new ConcurrentHashMap<>();
    /** OnOpen 注解方法列表 */
    /** ONopenmethods */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();
    /** OnClose 注解方法列表 */
    /** ONclosemethods */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();
    /** OnMessage 注解方法列表 */
    /** ON消息methods */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();
    /** OnError 注解方法列表 */
    /** ON错误methods */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    private IonetSyncServer(IonetServer delegate) {
        this.delegate = delegate;
        // 消息入口 filter：ionet 消息经 filter 链时转发为 onMessage
        delegate.addFilter(new MessageNotifyFilter());
    }

    /**
     * SPI 构造（供 {@code ServiceProvider.of(SyncServer.class).getNewExtension("ionet", setting)}
     * 反射创建）：delegate 一个默认 IonetServer，端口取自 setting。
     *
     * @param setting 服务器配置
     */
    public IonetSyncServer(ServerSetting setting) {
        this(new IonetServer(setting != null ? setting : ServerSetting.defaults()));
    }

    @Override
    public void start() {
        Locale.setDefault(Locale.CHINA);
        delegate.start();
        // @OnOpen 注解分发
        dispatchAnnotatedMethods(onOpenMethods);
    }

    /**
     * 在当前线程阻塞启动服务器（前台运行，等价 {@link #start()}）。
     */
    public void startup() {
        start();
    }

    /**
     * 在新线程中异步启动服务器，不阻塞当前线程
     *
     * @return this，支持链式调用 awaitStartup()
     */
    public IonetSyncServer startupAsync() {
        Thread t = Thread.ofVirtual().name("ionet-server").start(() -> {
            try {
                start();
            } finally {
                startupLatch.countDown();
            }
        });
        serverThread.set(t);
        return this;
    }

    /**
     * 等待服务器启动完成（配合 startupAsync 使用）
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true 如果服务器在超时前启动完成
     */
    public boolean awaitStartup(long timeout, TimeUnit unit) {
        try {
            return startupLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 获取服务器线程
     */
    public Thread getServerThread() {
        return serverThread.get();
    }

    @Override
    public void stop() {
        // @OnClose 注解分发
        dispatchAnnotatedMethods(onCloseMethods);
        delegate.stop();
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public ProtocolType getProtocolType() {
        return delegate.getProtocolType();
    }

    @Override
    public int getPort() {
        return delegate.getPort();
    }

    @Override
    public String getProtocol() {
        return delegate.getProtocol();
    }

    @Override
    public Server addFilter(ServerFilter filter) {
        return delegate.addFilter(filter);
    }

    @Override
    public Server removeFilter(ServerFilter filter) {
        return delegate.removeFilter(filter);
    }

    @Override
    public Server refreshFilters() {
        return delegate.refreshFilters();
    }

    @Override
    public ServerSetting getSetting() {
        return delegate.getSetting();
    }

    @Override
    public com.chua.common.support.objects.ObjectContext getObjectContext() {
        return delegate.getObjectContext();
    }

    @Override
    public void setObjectContext(com.chua.common.support.objects.ObjectContext objectContext) {
        delegate.setObjectContext(objectContext);
    }

    @Override
    public List<ServerFilter> getFilters() {
        return delegate.getFilters();
    }

    @Override
    public Server registerBean(Object bean) {
        // 委托底层注册进 ObjectContext 的同时，扫描 @OnOpen/@OnMessage/@OnClose/@OnError 注解
        delegate.registerBean(bean);
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

    @Override
    public Server unregisterBean(Object bean) {
        return delegate.unregisterBean(bean);
    }

    @Override
    public void publish(String topic, Object message) {
        for (SyncServerListener listener : listeners) {
            try {
                listener.onMessage("broadcast", topic, String.valueOf(message));
            } catch (Exception ignored) {
            }
        }
        // @OnMessage 注解分发
        dispatchAnnotatedPublish(topic, String.valueOf(message));
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        for (SyncServerListener listener : listeners) {
            try {
                listener.onMessage(clientId, topic, String.valueOf(message));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        return clients.getOrDefault(clientId, Map.of());
    }

    @Override
    public void addListener(SyncServerListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
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
     * 消息通知 filter：把 ionet 消息（path=/cmdMerge, body=payload, remote=userId）
     * 转发为 onMessage 通知监听器，并登记客户端。
     */
    private final class MessageNotifyFilter implements ServerFilter {

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            String clientId = request.getRemoteAddress();
            String topic = request.getPath() != null ? request.getPath() : "/";
            String payload = request.getBodyString();
            if (clientId != null) {
                clients.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                        .put("address", clientId);
            }
            for (SyncServerListener listener : listeners) {
                try {
                    listener.onMessage(clientId, topic, payload);
                } catch (Exception ignored) {
                }
            }
            // @OnMessage 注解分发
            dispatchAnnotatedPublish(topic, payload);
            chain.doFilter(request, response);
        }

        @Override
        public ProtocolType[] supportProtocols() {
            return new ProtocolType[]{ProtocolType.TCP, ProtocolType.UDP, ProtocolType.WS};
        }
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * 内部 IonetServer 构建器
         */
        private final IonetServer.Builder serverBuilder = IonetServer.builder();

        public Builder port(int port) { serverBuilder.port(port); return this; }
        public Builder joinType(ExternalJoinEnum joinType) { serverBuilder.joinType(joinType); return this; }
        public Builder logicServerName(String name) { serverBuilder.logicServerName(name); return this; }
        public Builder scanActionPackage(Class<?> scanClass) { serverBuilder.scanActionPackage(scanClass); return this; }
        public Builder enableCenterServer(boolean enable) { serverBuilder.enableCenterServer(enable); return this; }
        public Builder debugMode(boolean debug) { serverBuilder.debugMode(debug); return this; }
        public Builder skeletonConfigurer(Consumer<BarSkeletonBuilder> configurer) { serverBuilder.skeletonConfigurer(configurer); return this; }

        public IonetSyncServer build() {
            return new IonetSyncServer(serverBuilder.build());
        }
    }
}
