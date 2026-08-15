package com.chua.rsocket.support.server;

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

    public RSocketServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        serverDisposable = io.rsocket.core.RSocketServer.create((setup, sendingSocket) -> {
            return Mono.just(new io.rsocket.RSocket() {

                @Override
                public Mono<io.rsocket.Payload> requestResponse(io.rsocket.Payload payload) {
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
                                if (response.getResult() != null) {
                                    responseBody = String.valueOf(response.getResult());
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
                                if (response.getResult() != null) {
                                    responseBody = String.valueOf(response.getResult());
                                }
                            } catch (Exception e) {
                                log.error("RSocket requestResponse 处理异常: topic={}", topic, e);
                            }
                        }
                        publish(topic, data);
                    }
                    return Mono.just(io.rsocket.util.DefaultPayload.create(responseBody));
                }

                @Override
                public Mono<Void> fireAndForget(io.rsocket.Payload payload) {
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
                    return Mono.empty();
                }

                @Override
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
        invokeAnnotatedMethods(OnClose.class);
        log.info("RSocket 服务器停止");
    }

    @Override
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
                        method.invoke(bean);
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
                Object result = method.invoke(bean, args);
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
    private static class SimpleServerRequest implements com.chua.common.support.network.server.request.ServerRequest {

        private final String topic;
        private final String body;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        SimpleServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        @Override
        public String getUri() {
            return "/" + topic;
        }

        @Override
        public String getPath() {
            return "/" + topic;
        }

        @Override
        public com.chua.common.support.network.http.HttpMethod getMethod() {
            return com.chua.common.support.network.http.HttpMethod.POST;
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        @Override
        public Map<String, String> getParams() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public String getParam(String name) {
            return null;
        }

        @Override
        public String getContentType() {
            return "application/json";
        }

        @Override
        public long getContentLength() {
            return body != null ? body.getBytes().length : -1;
        }

        @Override
        public byte[] getBody() {
            return body != null ? body.getBytes() : new byte[0];
        }

        @Override
        public String getBodyString() {
            return body;
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(body != null ? body.getBytes() : new byte[0]);
        }

        @Override
        public String getRemoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }

    /**
     * 轻量 RSocket 响应适配。
     */
    private static class SimpleServerResponse implements com.chua.common.support.network.server.response.ServerResponse {

        private volatile boolean ended;
        private volatile boolean committed;
        private int status = 200;
        private Object result;

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public ServerResponse setStatus(int status) {
            this.status = status;
            return this;
        }

        @Override
        public ServerResponse setBody(byte[] body) {
            this.result = body;
            return this;
        }

        @Override
        public ServerResponse setBody(String body) {
            this.result = body;
            return this;
        }

        @Override
        public ServerResponse setHeader(String name, String value) {
            return this;
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public ServerResponse setContentType(String contentType) {
            return this;
        }

        @Override
        public byte[] getBody() {
            return result instanceof byte[] ? (byte[]) result : null;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public ServerResponse sendRedirect(String location) {
            return this;
        }

        @Override
        public ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }

        @Override
        public boolean isEnded() {
            return ended;
        }

        @Override
        public void end() {
            this.ended = true;
        }

        @Override
        public ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }

        @Override
        public void writeRaw(byte[] bytes) {
        }

        @Override
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }

        @Override
        public Object getResult() {
            return result;
        }
    }

    /**
     * FluxSink 包装器。
     */
    private record FluxSinkWrapper(reactor.core.publisher.FluxSink<io.rsocket.Payload> sink) {
    }
}
