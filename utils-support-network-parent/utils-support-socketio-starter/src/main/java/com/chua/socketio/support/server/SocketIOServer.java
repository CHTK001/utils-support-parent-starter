// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.socketio.support.server;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.spi.annotations.Spi;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.Transport;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.network.server.response.ServerResponse;

/**
 * Socket.IO 嵌入式服务器，轻量级实现。
 * <p>
 * 继承 {@link AbstractServer}，支持 {@link ServerFilter} 过滤器链、
 * {@link com.chua.common.support.objects.annotation.OnOpen @OnOpen}、
 * {@link com.chua.common.support.objects.annotation.OnClose @OnClose}、
 * {@link com.chua.common.support.objects.annotation.OnMessage @OnMessage} 注解处理。
 * 基于 Netty-Socket.IO 实现，提供主题订阅和发布能力。
 * </p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setPort(9092);
 * SocketIOServer server = new SocketIOServer(setting);
 *
 * server.register(new Object() {
 *     &#64;OnOpen
// [P3C E1 豁免] 场景化输出，非业务日志
 *     public void onConnect() { System.out.println("客户端连接"); }
 *
 *     &#64;OnMessage("order")
// [P3C E1 豁免] 场景化输出，非业务日志
 *     public void onOrder(String payload) { System.out.println("收到: " + payload); }
 *
 *     &#64;OnClose
// [P3C E1 豁免] 场景化输出，非业务日志
 *     public void onDisconnect() { System.out.println("客户端断开"); }
 * });
 *
 * server.start();
 * server.publish("order", "hello");
 * server.stop();
 * }</pre>
 * 服务端.发布("订单", "hello");
 * 服务端.停止();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("socketio")
public class SocketIOServer extends AbstractServer {

    /**
     * Netty-Socket.IO 服务器实例
     */
    private com.corundumstudio.socketio.SocketIOServer delegate;

    /**
     * 运行标记，替代 Netty-Socket.IO 不存在的 是否running() 方法
     */
    private volatile boolean delegateRunning;

    /**
     * 主题到 服务端处理器 的映射
     */
    private final Map<String, ServerHandler> messageHandlers = new ConcurrentHashMap<>();

    /**
     * 创建 Socketio服务端 实例
     * @param setting setting
     */
    public SocketIOServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 执行开始
    */
    @Override
    protected void doStart() {
        Configuration configuration = new Configuration();
        configuration.setHostname(setting.getHost());
        configuration.setPort(setting.getPort());
        configuration.setBossThreads(setting.getBossThreads());
        configuration.setWorkerThreads(setting.getWorkerThreads());
        configuration.setTransports(Transport.POLLING, Transport.WEBSOCKET);

        delegate = new com.corundumstudio.socketio.SocketIOServer(configuration);

        delegate.addConnectListener(client -> {
            invokeAnnotatedMethods(OnOpen.class);
        });

        delegate.addDisconnectListener(client -> {
            invokeAnnotatedMethods(OnClose.class);
        });

        for (String topic : messageHandlers.keySet()) {
            delegate.addEventListener(topic, String.class, (client, data, ackRequest) -> {
                handleMessage(topic, data, ackRequest);
            });
        }

        delegate.start();
        delegateRunning = true;
        log.info("Socket.IO 服务器启动: {}:{}", setting.getHost(), setting.getPort());
    }

    /**
     * 执行停止
    */
    @Override
    protected void doStop() {
        if (delegate != null) {
            delegate.stop();
            delegate = null;
        }
        delegateRunning = false;
        messageHandlers.clear();
        log.info("Socket.IO 服务器停止");
    }

    /**
     * 获取协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
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
    public SocketIOServer registerBean(Object handler) {
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
                registerMapping("/" + topic, serverHandler);
                log.debug("注册 @OnMessage: topic={}, method={}", topic, method.getName());
            }
        }
        return this;
    }

    /**
     * 注册主题订阅处理器。
     *
     * @param topic   主题名称
     * @param handler 消息处理器
     * @return 当前服务器实例，支持链式调用
     */
    public SocketIOServer onSubscribe(String topic, ServerHandler handler) {
        messageHandlers.put(topic, handler);
        registerMapping("/" + topic, handler);
        if (delegate != null && delegateRunning) {
            delegate.addEventListener(topic, String.class, (client, data, ackRequest) -> {
                handleMessage(topic, data, ackRequest);
            });
        }
        return this;
    }

    /**
     * 向所有订阅了指定主题的客户端广播消息。
     *
     * @param topic   主题名称
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        if (delegate != null && delegateRunning) {
            delegate.getBroadcastOperations().sendEvent(topic, payload);
            log.debug("Socket.IO 发布消息: topic={}, payload={}", topic, payload);
        }
    }

    /**
     * 处理收到的消息，走 服务端过滤器 链，并通过 ACK请求 返回结果。
     *
     * @param topic      主题名称
     * @param data       消息内容
     * @param ackRequest Socket.IO ACK 请求
     */
    private void handleMessage(String topic, String data, com.corundumstudio.socketio.AckRequest ackRequest) {
        ServerHandler handler = messageHandlers.get(topic);
        if (handler == null) {
            return;
        }
        SimpleServerRequest request = new SimpleServerRequest(topic, data);
        SimpleServerResponse response = new SimpleServerResponse();
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.error("Socket.IO 消息处理异常: topic={}", topic, e);
        }
        Object result = response.getResult();
        if (result != null) {
            String resultStr = result.toString();
            ackRequest.sendAckData(resultStr);
            publish(topic, resultStr);
        }
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
                    } else if (paramTypes[i] == ServerResponse.class) {
                        args[i] = response;
                    } else if (paramTypes[i] == String.class) {
                        args[i] = body;
                    } else if (paramTypes[i] == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("不支持的请求参数类型: " + paramTypes[i].getName());
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

    // ==================== 轻量请求/响应适配 ====================

    /**
     * 轻量 Socket.IO 请求适配。
     * @author CH
     * @since 4.0.0
     */
    private static class SimpleServerRequest implements com.chua.common.support.network.server.request.ServerRequest {

        /**
         * topic
         */
        private final String topic;
        /**
         * 数据内容
         */
        private final String body;
        /**
         * attributes
         */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        /**
         * Simple Server Request
         * @param body 字符串
         * @param topic 字符串
         * @return 返回值
        */
        SimpleServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        /**
         * 获取Uri
        */
        @Override
        public String getUri() {
            return "/" + topic;
        }

        /**
         * 获取路径
        */
        @Override
        public String getPath() {
            return "/" + topic;
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
            return java.util.Collections.emptyMap();
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
            return "application/json";
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
        */
        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }

    /**
     * 轻量 Socket.IO 响应适配。
     * @author CH
     * @since 4.0.0
     */
    private static class SimpleServerResponse implements ServerResponse {

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
            return this;
        }

        /**
         * 获取结果
        */
        @Override
        public Object getResult() {
            return result;
        }    }}