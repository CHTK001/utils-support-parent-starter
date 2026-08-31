package com.chua.rsocket.support.server;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import io.rsocket.transport.netty.server.TcpServerTransport;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * RSocket 嵌入式服务器，轻量级实现。
 * <p>
 * 继承 {@link AbstractServer}，支持 {@link ServerFilter} 过滤器链、
 * {@link com.chua.common.support.objects.annotation.OnOpen @OnOpen}、
 * {@link com.chua.common.support.objects.annotation.OnClose @OnClose}、
 * {@link com.chua.common.support.objects.annotation.OnMessage @OnMessage} 注解处理。
 * 基于 RSocket Java 实现，支持 requestResponse、fireAndForget、requestStream 模型。
 * </p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setPort(7000);
 * RSocketServer server = new RSocketServer(setting);
 *
 * server.register(new Object() {
 *     &#64;OnOpen
 *     public void onConnect() { System.out.println("客户端连接"); }
 *
 *     &#64;OnMessage("order")
 *     public void onOrder(String payload) { System.out.println("收到: " + payload); }
 *
 *     &#64;OnClose
 *     public void onDisconnect() { System.out.println("客户端断开"); }
 * });
 *
 * server.start();
 * server.publish("order", "hello");
 * server.stop();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rsocket")
public class RSocketServer extends AbstractServer {

    /**
     * RSocket 服务器 disposable
     */
    private Disposable serverDisposable;

    /**
     * 主题到订阅者 FluxSink 列表的映射
     */
    private final Map<String, List<FluxSinkWrapper>> topicSubscribers = new ConcurrentHashMap<>();

    /**
     * 主题到 ServerHandler 的映射
     */
    private final Map<String, ServerHandler> messageHandlers = new ConcurrentHashMap<>();

    /**
     * 虚拟线程执行器(异步派发 requestResponse/fireAndForget 业务,避免阻塞连接 event loop)
     */
    private final java.util.concurrent.ExecutorService bizExecutor =
            ThreadUtils.newVirtualThreadPerTaskExecutor();

    /**
     * 创建 RSocketServer 实例
     * @param setting setting
     */
    public RSocketServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        // 增加 Netty worker 线程数,避免 1000 并发短连接 SETUP 握手溢出默认 4 线程
        int workers = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        System.setProperty("reactor.netty.ioWorkerCount", String.valueOf(workers));
        serverDisposable = io.rsocket.core.RSocketServer.create((setup, sendingSocket) -> {
            return Mono.just(new io.rsocket.RSocket() {

                @Override
                /** RequestResponse */
                public Mono<io.rsocket.Payload> requestResponse(io.rsocket.Payload payload) {
                    // 虚拟线程异步派发,避免同步认证(DB/Redis)阻塞连接 event loop,
                    // 否则同一条长连接的并发 stream 会全部串行排队导致超时
                    return Mono.fromCallable(() -> {
                        String topic = extractTopic(payload);
                        String data = payload.getDataUtf8();
                        String responseBody = "{\"status\":\"ok\"}";
                        if (topic != null) {
                            ServerHandler handler = messageHandlers.get(topic);
                            if (handler != null) {
                                // 优先走主题消息处理器(消息模型)
                                SimpleServerRequest request = new SimpleServerRequest(topic, data);
                                SimpleServerResponse response = new SimpleServerResponse();
                                try {
                                    handler.handle(request, response);
                                    if (response.getBody() != null) {
                                        responseBody = new String(response.getBody(), StandardCharsets.UTF_8);
                                    }
                                } catch (Exception e) {
                                    log.error("RSocket requestResponse 处理异常: topic={}", topic, e);
                                }
                            } else {
                                // 无主题处理器时走统一过滤器链路(URL 路由,兼容 OAuth 认证)
                                SimpleServerRequest request = new SimpleServerRequest(topic, data);
                                SimpleServerResponse response = new SimpleServerResponse();
                                try {
                                    handleRequest(request, response);
                                    if (response.getBody() != null) {
                                        responseBody = new String(response.getBody(), StandardCharsets.UTF_8);
                                    }
                                } catch (Exception e) {
                                    log.error("RSocket requestResponse 处理异常: topic={}", topic, e);
                                }
                            }
                            publish(topic, data);
                        }
                        return io.rsocket.util.DefaultPayload.create(responseBody);
                    }).subscribeOn(reactor.core.scheduler.Schedulers.fromExecutor(bizExecutor));
                }

                @Override
                /** FireAndForget */
                public Mono<Void> fireAndForget(io.rsocket.Payload payload) {
                    // 虚拟线程异步执行,业务不阻塞连接 event loop
                    return Mono.<Void>fromRunnable(() -> {
                        String topic = extractTopic(payload);
                        String data = payload.getDataUtf8();
                        if (topic != null) {
                            ServerHandler handler = messageHandlers.get(topic);
                            if (handler != null) {
                                SimpleServerRequest request = new SimpleServerRequest(topic, data);
                                SimpleServerResponse response = new SimpleServerResponse();
                                try {
                                    handler.handle(request, response);
                                } catch (Exception e) {
                                    log.error("RSocket fireAndForget 处理异常: topic={}", topic, e);
                                }
                            } else {
                                // 无主题处理器时走统一过滤器链路
                                SimpleServerRequest request = new SimpleServerRequest(topic, data);
                                SimpleServerResponse response = new SimpleServerResponse();
                                try {
                                    handleRequest(request, response);
                                } catch (Exception e) {
                                    log.error("RSocket fireAndForget 处理异常: topic={}", topic, e);
                                }
                            }
                            publish(topic, data);
                        }
                    }).subscribeOn(reactor.core.scheduler.Schedulers.fromExecutor(bizExecutor)).then();
                }

                @Override
                /** RequestStream */
                public Flux<io.rsocket.Payload> requestStream(io.rsocket.Payload payload) {
                    String topic = extractTopic(payload);
                    if (topic == null || topic.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.create(sink -> {
                        FluxSinkWrapper wrapper = new FluxSinkWrapper(sink);
                        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(wrapper);
                        sink.onCancel(() -> {
                            List<FluxSinkWrapper> wrappers = topicSubscribers.get(topic);
                            if (wrappers != null) {
                                wrappers.remove(wrapper);
                            }
                        });
                    });
                }
            });
        }).bind(TcpServerTransport.create(setting.getHost(), setting.getPort()))
          .doOnSuccess(d -> log.info("RSocket 服务器启动: {}:{}", setting.getHost(), setting.getPort()))
          .doOnError(e -> log.error("RSocket 服务器启动失败: {}:{}", setting.getHost(), setting.getPort(), e))
          .block();

        if (serverDisposable == null) {
            throw new RuntimeException("RSocket 服务器启动失败");
        }
        invokeAnnotatedMethods(OnOpen.class);
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (serverDisposable != null) {
            serverDisposable.dispose();
            serverDisposable = null;
        }
        for (List<FluxSinkWrapper> wrappers : topicSubscribers.values()) {
            for (FluxSinkWrapper wrapper : wrappers) {
                try {
                    wrapper.sink.complete();
                } catch (Exception ignored) {
                    // 完成异常忽略
                }
            }
        }
        topicSubscribers.clear();
        messageHandlers.clear();
        bizExecutor.shutdownNow();
        invokeAnnotatedMethods(OnClose.class);
        log.info("RSocket 服务器停止");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.UNKNOWN;
    }

    /**
     * 注册注解处理器。
     * <p>
     * 扫描类上的 {@link OnOpen}、{@link OnClose}、{@link OnMessage} 注解，
     * 自动绑定到对应事件。
     * </p>
     *
     * @param handler 处理器对象
     * @return 当前服务器实例，支持链式调用
     */
    @Override
    public RSocketServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        Class<?> clazz = handler.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage annotation = method.getAnnotation(OnMessage.class);
                String topic = annotation.value();
                if (topic == null || topic.isEmpty()) {
                    topic = "default";
                }
                ServerHandler serverHandler = createMessageHandler(handler, method);
                messageHandlers.put(topic, serverHandler);
                log.debug("注册 @OnMessage: topic={}, method={}", topic, method.getName());
            }
        }
        return this;
    }

    /**
     * 注册主题请求处理器。
     *
     * @param topic   主题名称
     * @param handler 消息处理器
     * @return 当前服务器实例，支持链式调用
     */
    public RSocketServer onSubscribe(String topic, Consumer<String> handler) {
        messageHandlers.put(topic, (request, response) -> {
            handler.accept(request.getBodyString());
        });
        return this;
    }

    /**
     * 注册主题对应的 {@link ServerHandler} 处理器。
     *
     * <p>与 {@link #onSubscribe(String, Consumer)} 不同,此处直接注册请求-响应处理器,
     * 处理器写入 {@link ServerResponse} 的内容会作为 requestResponse 的响应返回。</p>
     *
     * @param topic   主题名称
     * @param handler 请求-响应处理器
     * @return 当前服务器实例，支持链式调用
     */
    public RSocketServer onRequest(String topic, ServerHandler handler) {
        if (handler != null) {
            messageHandlers.put(topic, handler);
        }
        return this;
    }

    /**
     * 向所有订阅了指定主题的客户端推送消息。
     *
     * @param topic   主题名称
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        List<FluxSinkWrapper> wrappers = topicSubscribers.getOrDefault(topic, List.of());
        for (FluxSinkWrapper wrapper : wrappers) {
            try {
                wrapper.sink.next(io.rsocket.util.DefaultPayload.create(payload));
            } catch (Exception e) {
                log.error("RSocket 推送消息失败: topic={}", topic, e);
            }
        }
        log.debug("RSocket 发布消息: topic={}, payload={}", topic, payload);
    }

    /**
     * 从 RSocket Payload 中提取 topic。
     *
     * @param payload RSocket 负载
     * @return topic 名称，提取失败返回 null
     */
    private String extractTopic(io.rsocket.Payload payload) {
        if (payload == null) {
            return null;
        }
        String data = payload.getDataUtf8();
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = Json.fromJson(data, Map.class);
            if (map != null && map.containsKey("topic")) {
                return map.get("topic").toString();
            }
        } catch (Exception ignored) {
            // JSON 解析失败时返回 null
        }
        // 兼容从 metadata 中读取 route(oauth client 将路由放在 metadata 中)
        try {
            String metadata = payload.getMetadataUtf8();
            if (metadata != null && !metadata.isEmpty()) {
                Map<String, Object> metaMap = Json.fromJson(metadata, Map.class);
                if (metaMap != null && metaMap.containsKey("route")) {
                    return metaMap.get("route").toString();
                }
            }
        } catch (Exception ignored) {
            // metadata 解析失败时返回 null
        }
        return null;
    }

    /**
     * 调用标注了指定注解的方法。
     *
     * @param annotationType 注解类型
     */
    private void invokeAnnotatedMethods(Class<? extends Annotation> annotationType) {
        if (getObjectContext() == null) {
            return;
        }
        Collection<String> beanNames = getObjectContext().getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = getObjectContext().getBean(beanName, Object.class);
            if (bean == null) {
                continue;
            }
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotationType)) {
                    method.setAccessible(true);
                    try {
                        ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes());
                    } catch (Exception e) {
                        log.error("调用 {} 方法异常: {}.{}",
                                annotationType.getSimpleName(),
                                bean.getClass().getSimpleName(),
                                method.getName(), e);
                    }
                }
            }
        }
    }

    /**
     * 创建消息处理器。
     *
     * @param bean   目标对象
     * @param method 目标方法
     * @return ServerHandler 实例
     */
    private ServerHandler createMessageHandler(Object bean, Method method) {
        method.setAccessible(true);
        return (request, response) -> {
            try {
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                String body = request.getBodyString();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] == com.chua.common.support.network.server.request.ServerRequest.class) {
                        args[i] = request;
                    } else if (paramTypes[i] == com.chua.common.support.network.server.response.ServerResponse.class) {
                        args[i] = response;
                    } else if (paramTypes[i] == String.class) {
                        args[i] = body;
                    } else if (paramTypes[i] == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("不支持的请求参数类型: " + paramTypes[i].getName());
                    }
                }
                Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
                if (result != null) {
                    response.setResult(result);
                }
            } catch (Exception e) {
                if (!response.isEnded()) {
                    response.sendError(500, "Internal Server Error");
                }
            }
        };
    }

    // ==================== 轻量请求/响应适配 ====================

    /**
     * 轻量 RSocket 请求适配。
     */
    private static class SimpleServerRequest extends com.chua.common.support.network.server.request.AbstractServerRequest {

        /**
         * topic
         */
        private final String topic;
        /**
         * 数据内容
         */
        private final byte[] body;

        SimpleServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }

        @Override
        /** 读取Body */
        protected byte[] readBody() {
            return body;
        }

        @Override
        /** 获取Uri */
        public String getUri() {
            return "/" + topic;
        }

        @Override
        /** 获取Path */
        public String getPath() {
            return "/" + topic;
        }

        @Override
        /** 获取Method */
        public com.chua.common.support.network.http.HttpMethod getMethod() {
            return com.chua.common.support.network.http.HttpMethod.POST;
        }

        @Override
        /** 获取Header */
        public String getHeader(String name) {
            return null;
        }

        @Override
        /** 获取Headers */
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        @Override
        /** 获取RemoteAddress */
        public String getRemoteAddress() {
            return "127.0.0.1";
        }

        @Override
        /** 获取RemotePort */
        public int getRemotePort() {
            return 0;
        }
    }

    /**
     * 轻量 RSocket 响应适配。
     */
    private static class SimpleServerResponse extends com.chua.common.support.network.server.response.AbstractServerResponse {

        @Override
        /** 获取OutputStream */
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        /** 写入Raw */
        public void writeRaw(byte[] bytes) {
            this.body = bytes;
        }
    }

    /**
     * FluxSink 包装器。
     */
    private record FluxSinkWrapper(reactor.core.publisher.FluxSink<io.rsocket.Payload> sink) {
    }
}
