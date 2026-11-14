// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.vertx.support.server.websocket;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.ServerWebSocket;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Vert.x 的 WebSocket 服务端。
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
@Spi("vertx-websocket")
public class VertxWebSocketServer extends AbstractServer {

    /**
     * Vertx
    */
    private Vertx vertx;
    /**
     * 服务器
    */
    private io.vertx.core.http.HttpServer server;
    /**
     * topic处理器
    */
    private final Map<String, List<ServerHandler>> topicHandlers = new ConcurrentHashMap<>();
    /**
     * Connections
    */
    private final List<ServerWebSocket> connections = new CopyOnWriteArrayList<>();

    /**
     * 创建 vertxwebSocket服务端 实例
     * @param setting setting
     */
    public VertxWebSocketServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 执行开始
    */
    @Override
    protected void doStart() {
        rejectStreamEncryption("WebSocket 实现未接 TLS，需要加密请用反向代理终结 TLS 或改用支持 wss 的实现");
        // 事件循环数提到 CPU 核数:WebSocket 帧解析/回显都在事件循环执行,
 // bossthreads 默认 1 会让单事件循环成为高并发吞吐瓶颈
        int eventLoopPoolSize = Math.max(Runtime.getRuntime().availableProcessors(), 2);
        int workerPoolSize = Math.max(setting.getWorkerThreads(), Runtime.getRuntime().availableProcessors() * 4);

        VertxOptions opts = new VertxOptions()
                .setEventLoopPoolSize(eventLoopPoolSize)
                .setWorkerPoolSize(workerPoolSize)
                .setPreferNativeTransport(true);
        vertx = Vertx.vertx(opts);

        io.vertx.core.http.HttpServerOptions httpOpts = new io.vertx.core.http.HttpServerOptions()
                .setHost(setting.getHost())
                .setPort(setting.getPort())
                .setAcceptBacklog(Math.max(setting.getBacklog(), 2048))
                .setTcpFastOpen(true)
                .setTcpNoDelay(setting.isTcpNoDelay())
                .setReusePort(setting.isSoReuseAddr())
                .setMaxWebSocketFrameSize(setting.getMaxFrameSize())
                .setMaxWebSocketMessageSize(setting.getMaxFrameSize() * 4);
        server = vertx.createHttpServer(httpOpts);
        server.webSocketHandler(ws -> {
            connections.add(ws);
            invokeAnnotatedMethods(OnOpen.class);

            ws.textMessageHandler(text -> {
                String topic = "default";
                String body = text;
                int idx = text.indexOf('\n');
                if (idx > 0) {
                    topic = text.substring(0, idx).trim();
                    body = text.substring(idx + 1);
                } else if (idx == 0) {
                    body = text.substring(1);
                }
                String finalTopic = topic;
                String finalBody = body;
                List<ServerHandler> handlers = topicHandlers.get(finalTopic);
                if (handlers == null) {
                    return;
                }
 // 事件循环直跑:WebSocket 回声 处理器 为非阻塞回调,无需 执行阻塞 切 工人 线程,
                // 高并发下省去每消息线程切换 + 队列调度开销,吞吐显著提升
                // 直接调用订阅处理器：原实现走 handleRequest（路由/过滤器链），
                // 那条路径不查 topicHandlers，导致 onSubscribe 注册的处理器永远不会被调用。
                for (ServerHandler handler : handlers) {
                    VertxServerRequest request = new VertxServerRequest(finalTopic, finalBody);
                    VertxServerResponse response = new VertxServerResponse(ws);
                    try {
                        handler.handle(request, response);
                    } catch (Exception e) {
                        log.warn("Vert.x WebSocket handler error: {}", e.getMessage(), e);
                    }
                }
            });

            ws.binaryMessageHandler(data -> {
                String topic = "default";
                String body = data.toString();
                int idx = body.indexOf('\n');
                if (idx > 0) {
                    topic = body.substring(0, idx).trim();
                    body = body.substring(idx + 1);
                } else if (idx == 0) {
                    body = body.substring(1);
                }
                String finalTopic = topic;
                String finalBody = body;
                List<ServerHandler> handlers = topicHandlers.get(finalTopic);
                if (handlers == null) {
                    return;
                }
 // 事件循环直跑:WebSocket 回声 处理器 为非阻塞回调,无需 执行阻塞 切 工人 线程,
                // 高并发下省去每消息线程切换 + 队列调度开销,吞吐显著提升
                // 直接调用订阅处理器：原实现走 handleRequest（路由/过滤器链），
                // 那条路径不查 topicHandlers，导致 onSubscribe 注册的处理器永远不会被调用。
                for (ServerHandler handler : handlers) {
                    VertxServerRequest request = new VertxServerRequest(finalTopic, finalBody);
                    VertxServerResponse response = new VertxServerResponse(ws);
                    try {
                        handler.handle(request, response);
                    } catch (Exception e) {
                        log.warn("Vert.x WebSocket handler error: {}", e.getMessage(), e);
                    }
                }
            });

            ws.closeHandler(v -> {
                connections.remove(ws);
                invokeAnnotatedMethods(OnClose.class);
            });
            ws.exceptionHandler(err -> {
                log.warn("Vert.x WebSocket error: {}", err.getMessage(), err);
                connections.remove(ws);
            });
        });
        server.listen(setting.getPort(), setting.getHost())
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /**
     * 执行停止
    */
    @Override
    protected void doStop() {
        if (server != null) {
            try {
                server.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception e) {
                log.debug("[network] 关闭 websocket 服务端失败，已忽略: {}", e.getMessage());
            }
        }
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception e) {
                log.debug("[network] 关闭 vertx 实例失败，已忽略: {}", e.getMessage());
            }
        }
        connections.clear();
        invokeAnnotatedMethods(OnClose.class);
        log.info("Vert.x WebSocketServer stopped");
    }

    /**
     * 获取协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
    }

    /**
     * 注册Bean
    */
    @Override
    public VertxWebSocketServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        Class<?> clazz = handler.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            // 可访问性由下方 ReflectUtils.invoke（MethodHandle 私有查找）统一处理，不再原生 setAccessible
            if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
                if (topic == null || topic.isEmpty()) {
                    topic = "default";
                }
                ServerHandler serverHandler = createMessageHandler(handler, method);
                topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(serverHandler);
            }
        }
        return this;
    }

    /**
     * On订阅
     *
     * @param topic topic
     * @param handler 处理器
     * @return on订阅的结果
     */
    public VertxWebSocketServer onSubscribe(String topic, ServerHandler handler) {
        topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 发布
     *
     * @param topic topic
     * @param payload payload
     */
    public void publish(String topic, String payload) {
        String text = topic + "\n" + payload;
        for (ServerWebSocket ws : connections) {
            if (!ws.isClosed()) {
                ws.writeTextMessage(text);
            }
        }
    }

    /**
     * 创建消息处理器
     *
     * @param bean Bean
     * @param method 方法
     * @return 创建消息处理器的结果
     */
    private ServerHandler createMessageHandler(Object bean, Method method) {
        // 可访问性由下方 ReflectUtils.invoke（MethodHandle 私有查找）统一处理，不再原生 setAccessible
        return (request, response) -> {
            try {
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                String body = request.getBodyString();
                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> type = paramTypes[i];
                    if (type == com.chua.common.support.network.server.request.ServerRequest.class) {
                        args[i] = request;
                    } else if (type == com.chua.common.support.network.server.response.ServerResponse.class) {
                        args[i] = response;
                    } else if (type == String.class) {
                        args[i] = body;
                    } else if (type == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("Unsupported param: " + type.getName());
                    }
                }
                Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(),
                        method.getParameterTypes(), args);
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

    /**
     * 调用annotated方法
     *
     * @param annotationType 注解类型
     * @author CH
     * @since 4.0.0
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
                    try {
                        ReflectUtils.invoke(bean, method.getName(), method.getReturnType());
                    } catch (Exception e) {
                        log.error("Invoke {} error: {}.{}", annotationType.getSimpleName(),
                                bean.getClass().getSimpleName(), method.getName(), e);
                    }
                }
            }
        }
    }

    private static class VertxServerRequest implements ServerRequest {

        /**
         * Topic
        */
        private final String topic;
        /**
         * 请求体
        */
        private final String body;
        /**
         * attributes
        */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        /**
         * Vertx Server Request
         * @param body 字符串
         * @param topic 字符串
         * @return 返回值
        */
        VertxServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        /**
         * 获取Uri
        */
        @Override
        public String getUri() {
            return "/ws/" + topic;
        }

        /**
         * 获取路径
        */
        @Override
        public String getPath() {
            return "/ws/" + topic;
        }

        /**
         * 获取方法
        */
        @Override
        public com.chua.common.support.network.http.HttpMethod getMethod() {
            return com.chua.common.support.network.http.HttpMethod.POST;
        }

        /**
         * 获取头部
        */
        @Override
        public String getHeader(String name) {
            return null;
        }

        /**
         * 获取头部
        */
        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        /**
         * 获取参数
        */
        @Override
        public Map<String, String> getParams() {
            return Collections.emptyMap();
        }

        /**
         * 获取参数
        */
        @Override
        public String getParam(String name) {
            return null;
        }

        /**
         * 获取内容类型
        */
        @Override
        public String getContentType() {
            return "text/plain";
        }

        /**
         * 获取内容获取长度
        */
        @Override
        public long getContentLength() {
            return body != null ? body.getBytes().length : -1;
        }

        /**
         * 获取主体
        */
        @Override
        public byte[] getBody() {
            return body != null ? body.getBytes() : new byte[0];
        }

        /**
         * 获取主体字符串
        */
        @Override
        public String getBodyString() {
            return body;
        }

        /**
         * 获取输入流
        */
        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(body != null ? body.getBytes() : new byte[0]);
        }

        /**
         * 获取远程地址
        */
        @Override
        public String getRemoteAddress() {
            return "127.0.0.1";
        }

        /**
         * 获取远程端口
        */
        @Override
        public int getRemotePort() {
            return 0;
        }

        /**
         * 获取Attributes
        */
        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        /**
         * 获取Attribute
        */
        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        /**
         * 设置Attribute
         *
         * @param name 名称
         * @param value 值
         * @author CH
         * @since 4.0.0
         */
        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }

    private static class VertxServerResponse implements ServerResponse {

        /**
         * WS
        */
        private final ServerWebSocket ws;
        /**
         * 结束
        */
        private volatile boolean ended;
        /**
         * committed
        */
        private volatile boolean committed;
        /**
         * 状态
        */
        private int status = 200;
        /**
         * 结果
        */
        private Object result;

        /**
         * Vertx Server Response
         * @param ws 参数
         * @return 返回值
        */
        VertxServerResponse(ServerWebSocket ws) {
            this.ws = ws;
        }

        /**
         * 获取状态
        */
        @Override
        public int getStatus() {
            return status;
        }

        /**
         * 设置状态
        */
        @Override
        public ServerResponse setStatus(int status) {
            this.status = status;
            return this;
        }

        /**
         * 获取头部
        */
        @Override
        public String getHeader(String name) {
            return null;
        }

        /**
         * 设置头部
        */
        @Override
        public ServerResponse setHeader(String name, String value) {
            return this;
        }

        /**
         * 获取头部
        */
        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        /**
         * 获取内容类型
        */
        @Override
        public String getContentType() {
            return null;
        }

        /**
         * 设置内容类型
        */
        @Override
        public ServerResponse setContentType(String contentType) {
            return this;
        }

        /**
         * 设置主体
        */
        @Override
        public ServerResponse setBody(byte[] body) {
            this.result = body;
            return this;
        }

        /**
         * 设置主体
        */
        @Override
        public ServerResponse setBody(String body) {
            this.result = body;
            return this;
        }

        /**
         * 获取主体
        */
        @Override
        public byte[] getBody() {
            return result instanceof byte[] ? (byte[]) result : null;
        }

        /**
         * 获取输出流
        */
        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        /**
         * 发送Redirect
        */
        @Override
        public ServerResponse sendRedirect(String location) {
            return this;
        }

        /**
         * 发送记录错误
        */
        @Override
        public ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }

        /**
         * 刷新
        */
        @Override
        public void flush() {
        }

        /**
         * 是否Committed
        */
        @Override
        public boolean isCommitted() {
            return committed;
        }

        /**
         * 是否结束
        */
        @Override
        public boolean isEnded() {
            return ended;
        }

        /**
         * 结束
        */
        @Override
        public void end() {
            this.ended = true;
        }

        /**
         * 重置
        */
        @Override
        public ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }

        /**
         * 写入Raw
        */
        @Override
        public void writeRaw(byte[] bytes) {
        }

        /**
         * 设置结果
        */
        @Override
        public ServerResponse setResult(Object result) {
            this.result = result;
            if (result != null && ws != null && !ws.isClosed()) {
                ws.writeTextMessage(result.toString());
            }
            return this;
        }

        /**
         * 获取结果
        */
        @Override
        public Object getResult() {
            return result;
        }
    }
}
