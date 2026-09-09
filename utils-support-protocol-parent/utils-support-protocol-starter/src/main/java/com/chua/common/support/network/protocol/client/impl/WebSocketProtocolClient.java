package com.chua.common.support.network.protocol.client.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.AbstractProtocolClient;
import com.chua.common.support.network.protocol.request.HttpServletResponse;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.nio.ByteBuffer;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * WebSocket客户端实现
 * <p>
 * 提供WebSocket协议的客户端实现，支持：
 * 1. 异步和同步消息发送
 * 2. 监听器机制（连接、消息、错误事件）
 * 3. 心跳检测和自动重连
 * 4. SSL/TLS加密连接
 * 5. 二进制和文本消息支持
 * 6. Query参数和Parameter参数支持
 * 7. 智能参数处理（连接时和消息发送时）
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("WEBSOCKET")
public class WebSocketProtocolClient extends AbstractProtocolClient {

    private WebSocket webSocket;
    private HttpClient httpClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicReference<ServletResponse> lastResponse = new AtomicReference<>();
    private CountDownLatch connectionLatch;
    private CountDownLatch responseLatch;
    private ServletRequest connectionRequest; // 存储连接时的请求信息

    public WebSocketProtocolClient(ClientSetting clientSetting) {
        super(clientSetting);
    }

    @Override
    protected boolean doConnect() {
        return doConnect(null);
    }

    /**
     * 连接到WebSocket服务器
     * <p>
     * 支持在连接时传递参数，参数会被添加到WebSocket连接URL中
     *
     * @param request 连接请求（可选）
     * @return 连接是否成功
     */
    protected boolean doConnect(ServletRequest request) {
        try {
            this.connectionRequest = request;

            String protocol = clientSetting.ssl() ? "wss" : "ws";
            String baseUrl = protocol + "://" + clientSetting.host() + ":" + clientSetting.port();

            // 构建完整的WebSocket URL（包含参数）
            String fullUrl = buildWebSocketUrl(baseUrl, request);
            URI serverUri = new URI(fullUrl);

            connectionLatch = new CountDownLatch(1);

            // 创建 HttpClient
            long timeout = clientSetting.getConnectTimeoutMillis() > 0 ? clientSetting.getConnectTimeoutMillis() : 10000;
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();

            // 构建并发起连接
            java.util.Map<String, String> headers = createWebSocketHeaders();
            java.net.http.WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeout));
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }

            webSocket = builder.buildAsync(serverUri, new JdkWebSocketListener()).get(timeout, TimeUnit.MILLISECONDS);

            // 等待连接建立信号
            boolean connectionResult = connectionLatch.await(timeout, TimeUnit.MILLISECONDS);
            if (!connectionResult) {
                log.error("WebSocket V2连接超时");
                return false;
            }
            return connected.get();
        } catch (Exception e) {
            log.error("WebSocket V2连接失败", e);
            return false;
        }
    }

    @Override
    protected void doDisconnect() {
        if (webSocket != null && connected.get()) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing").join();
            } catch (Exception e) {
                log.error("断开WebSocket V2连接时发生错误", e);
            } finally {
                connected.set(false);
            }
        }
    }


    @Override
    protected ServletResponse doSendSync(ServletRequest request) {
        if (!connected.get() || webSocket == null) {
            return ServletResponse.error("WebSocket V2未连接");
        }

        try {
            responseLatch = new CountDownLatch(1);
            lastResponse.set(null);

            // 发送消息（支持参数处理）
            sendWebSocketMessage(request);

            // 等待响应
            boolean responseReceived = responseLatch.await(clientSetting.getReadTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!responseReceived) {
                return ServletResponse.error("WebSocket V2响应超时");
            }

            ServletResponse response = lastResponse.get();
            return response != null ? response : ServletResponse.error("未收到响应");

        } catch (Exception e) {
            log.error("发送WebSocket V2消息失败", e);
            return ServletResponse.error("发送失败: " + e.getMessage());
        }
    }

    @Override
    protected CompletableFuture<ServletResponse> doSendAsync(ServletRequest request) {
        CompletableFuture<ServletResponse> future = new CompletableFuture<>();

        if (!connected.get() || webSocket == null) {
            future.completeExceptionally(new IllegalStateException("WebSocket V2未连接"));
            return future;
        }

        try {
            responseLatch = new CountDownLatch(1);
            lastResponse.set(null);

            // 发送消息（支持参数处理）
            sendWebSocketMessage(request);

            // 异步等待响应
            CompletableFuture.runAsync(() -> {
                try {
                    boolean responseReceived = responseLatch.await(clientSetting.getReadTimeoutMillis(), TimeUnit.MILLISECONDS);
                    if (!responseReceived) {
                        future.completeExceptionally(new java.util.concurrent.TimeoutException("WebSocket V2响应超时"));
                    } else {
                        ServletResponse response = lastResponse.get();
                        if (response != null) {
                            future.complete(response);
                        } else {
                            future.completeExceptionally(new RuntimeException("未收到响应"));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    protected void doSendOneWay(ServletRequest request) {
        if (!connected.get() || webSocket == null) {
            log.warn("WebSocket V2未连接，无法发送单向消息");
            return;
        }

        try {
            // 发送消息（支持参数处理）
            sendWebSocketMessage(request);
        } catch (Exception e) {
            log.error("发送WebSocket V2单向消息失败", e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && webSocket != null && !webSocket.isInputClosed();
    }

    @Override
    public String getProtocolName() {
        return "WEBSOCKET";
    }

    /**
     * 处理收到的消息
     */
    private void handleMessage(byte[] data) {
        HttpServletResponse response = HttpServletResponse.builder()
                .statusCode(200)
                .build();
        response.setBody(data);

        lastResponse.set(response);
        if (responseLatch != null) {
            responseLatch.countDown();
        }
    }

    @Override
    protected void doStartHeartbeat() {
        // WebSocket协议本身有ping/pong机制，这里可以实现自定义心跳
        if (webSocket != null) {
            // 可以定期发送ping帧
            if (log.isDebugEnabled()) {
                log.debug("WebSocket V2心跳启动");
            }
        }
    }

    @Override
    protected void doStopHeartbeat() {
        if (log.isDebugEnabled()) {
            log.debug("WebSocket V2心跳停止");
        }
    }

    @Override
    protected void doHeartbeat() {
        // WebSocket协议本身有ping/pong机制，这里可以实现自定义心跳
        if (webSocket != null && connected.get()) {
            try {
                // 发送ping帧（JDK WebSocket）
                webSocket.sendPing(ByteBuffer.wrap(new byte[]{'p','i','n','g'})).join();
                if (log.isDebugEnabled()) {
                    log.debug("WebSocket V2心跳ping发送");
                }
            } catch (Exception e) {
                log.error("发送WebSocket V2心跳失败", e);
            }
        }
    }

    @Override
    protected void doClose() {
        doDisconnect();
    }

    /**
     * 创建WebSocket连接头
     */
    private java.util.Map<String, String> createWebSocketHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();

        // 添加客户端标识header
        headers.put("X-Protocol-Client", "true");
        headers.put("X-Client-Id", clientSetting.getClientId());

        // 添加自定义header
        String customHeaders = clientSetting.getCustomHeaders();
        if (customHeaders != null && !customHeaders.trim().isEmpty()) {
            String[] headerPairs = customHeaders.split(";");
            for (String headerPair : headerPairs) {
                String[] parts = headerPair.split("=", 2);
                if (parts.length == 2) {
                    String name = parts[0].trim();
                    String value = parts[1].trim();
                    if (!name.isEmpty() && !value.isEmpty()) {
                        headers.put(name, value);
                        if (log.isDebugEnabled()) {
                            log.debug("添加自定义WebSocket头: {} = {}", name, value);
                        }
                    }
                }
            }
        }

        return headers;
    }

    /**
     * 构建WebSocket连接URL
     * <p>
     * 支持多种参数处理方式：
     * 1. Query参数：添加到WebSocket连接URL中
     * 2. Parameter参数：作为备选，添加到连接URL中
     * 3. 路径参数：支持在路径中包含参数
     *
     * @param baseUrl 基础URL
     * @param request 请求对象（可选）
     * @return 完整的WebSocket URL
     */
    private String buildWebSocketUrl(String baseUrl, ServletRequest request) {
        if (request == null) {
            return baseUrl;
        }

        StringBuilder url = new StringBuilder(baseUrl);

        // 1. 添加路径（如果请求中有路径信息）
        String path = request.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
                url.append("/");
            }
            url.append(path.startsWith("/") ? path.substring(1) : path);
        }

        // 2. 构建查询字符串
        String queryString = buildQueryStringForWebSocket(request);
        if (queryString != null && !queryString.isEmpty()) {
            url.append(url.toString().contains("?") ? "&" : "?").append(queryString);
        }

        return url.toString();
    }

    /**
     * 为WebSocket构建查询字符串
     * <p>
     * 优先级：
     * 1. Query参数：优先使用专门的查询参数
     * 2. Parameter参数：作为备选
     *
     * @param request 请求对象
     * @return 查询字符串
     */
    private String buildQueryStringForWebSocket(ServletRequest request) {
        // 1. 优先使用原始查询字符串
        String existingQueryString = request.getQueryString();
        if (existingQueryString != null && !existingQueryString.trim().isEmpty()) {
            return existingQueryString;
        }

        // 2. 从查询参数映射构建
        java.util.Map<String, String[]> queryParams = request.getQueryParameterMap();
        if (queryParams != null && !queryParams.isEmpty()) {
            return buildQueryStringFromMap(queryParams);
        }

        // 3. 从请求参数构建（WebSocket连接时的备选方案）
        java.util.Map<String, String[]> allParams = request.getParameterMap();
        if (allParams != null && !allParams.isEmpty()) {
            return buildQueryStringFromMap(allParams);
        }

        return null;
    }

    /**
     * 从参数映射构建查询字符串
     *
     * @param paramMap 参数映射
     * @return 查询字符串
     */
    private String buildQueryStringFromMap(java.util.Map<String, String[]> paramMap) {
        if (paramMap == null || paramMap.isEmpty()) {
            return null;
        }

        StringBuilder queryString = new StringBuilder();
        for (java.util.Map.Entry<String, String[]> entry : paramMap.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();

            if (values != null) {
                for (String value : values) {
                    if (!queryString.isEmpty()) {
                        queryString.append("&");
                    }
                    queryString.append(urlEncode(key)).append("=").append(urlEncode(value != null ? value : ""));
                }
            }
        }

        return queryString.length() > 0 ? queryString.toString() : null;
    }

    /**
     * 增强消息发送，支持参数处理
     * <p>
     * 对于WebSocket，参数通常在连接时处理，但也可以在消息中包含参数信息
     *
     * @param request 请求对象
     * @return 处理后的消息内容
     */
    private String buildWebSocketMessage(ServletRequest request) {
        // 1. 如果有请求体，优先使用请求体
        byte[] body = request.getBody();
        if (body != null && body.length > 0) {
            return new String(body);
        }

        // 2. 如果没有请求体但有参数，可以构建参数消息
        java.util.Map<String, String[]> params = request.getParameterMap();
        if (params != null && !params.isEmpty()) {
            // 构建JSON格式的参数消息
            StringBuilder message = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, String[]> entry : params.entrySet()) {
                if (!first) {
                    message.append(",");
                }
                first = false;

                String key = entry.getKey();
                String[] values = entry.getValue();
                message.append("\"").append(key).append("\":");

                if (values.length == 1) {
                    message.append("\"").append(values[0]).append("\"");
                } else {
                    message.append("[");
                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) message.append(",");
                        message.append("\"").append(values[i]).append("\"");
                    }
                    message.append("]");
                }
            }
            message.append("}");
            return message.toString();
        }

        return "";
    }

    /**
     * 发送WebSocket消息
     * <p>
     * 智能处理消息发送，支持：
     * 1. 请求体数据发送
     * 2. 参数数据发送
     * 3. 二进制和文本消息自动识别
     *
     * @param request 请求对象
     */
    private void sendWebSocketMessage(ServletRequest request) {
        // 1. 优先使用请求体
        byte[] body = request.getBody();
        if (body != null && body.length > 0) {
            // 根据内容类型决定发送方式
            String contentType = request.getContentType();
            if ("application/octet-stream".equals(contentType)) {
                webSocket.sendBinary(ByteBuffer.wrap(body), true).join();
            } else {
                webSocket.sendText(new String(body), true).join();
            }
            return;
        }

        // 2. 如果没有请求体，尝试从参数构建消息
        String message = buildWebSocketMessage(request);
        if (message != null && !message.isEmpty()) {
            webSocket.sendText(message, true).join();
            return;
        }

        // 3. 发送空消息
        webSocket.sendText("", true).join();
    }

    /**
     * URL编码工具方法
     *
     * @param value 需要编码的值
     * @return 编码后的值
     */
    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }

        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 带参数的连接方法
     * <p>
     * 允许在连接WebSocket时传递参数
     *
     * @param request 连接请求，包含连接参数
     * @return 连接是否成功
     */
    public boolean connectWithParameters(ServletRequest request) {
        return doConnect(request);
    }

    /**
     * JDK WebSocket 监听器
     */
    private class JdkWebSocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            if (log.isDebugEnabled()) {
                log.debug("WebSocket 连接已建立");
            }
            connected.set(true);
            if (connectionLatch != null) {
                connectionLatch.countDown();
            }
            webSocket.request(1);
            // 触发连接事件
            fireConnectionEvent("WebSocket连接已建立", null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            byte[] bytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            handleMessage(bytes);
            fireMessageEvent(data.toString(), null);
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            handleMessage(bytes);
            fireMessageEvent(bytes, null);
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket V2连接关闭: 代码={}, 原因={}", statusCode, reason);
            connected.set(false);
            fireDisconnectionEvent("WebSocket连接关闭: " + reason, null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("WebSocket V2错误", error);
            connected.set(false);
            Exception ex = error instanceof Exception ? (Exception) error : new Exception(error);
            fireErrorEvent("WebSocket错误: " + error.getMessage(), ex);
        }
    }
}
