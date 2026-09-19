package com.chua.qq.support.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionStage;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.*;
import com.chua.common.support.ai.bot.BotInboundMessage.Type;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * QQ 机器人 客户端，实现 {@link BotClient} 接口。
 * <p>对接 QQ 开放平台 Bot API（群机器人 / 频道机器人）。
 * 通过 WebSocket 接收事件，通过 REST API 发送消息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class QqBotClient implements BotClient {

    /**
     * 应用 标识
     */
    private String appId;

    /**
     * 应用密钥
     */
    private String appSecret;

    /**
     * 机器人 令牌
     */
    private String botToken;

    /**
     * API 基础地址
     */
    private String baseUrl = "https://api.sgroup.qq.com";

    /**
     * 连接超时时间（毫秒）
     */
    private long connectTimeoutMillis = 10_000;

    /**
     * 读取超时时间（毫秒）
     */
    private long readTimeoutMillis = 30_000;

    /**
     * Webhook 验证 令牌
     */
    private String webhookVerifyToken;

    /**
     * 事件意图标识
     */
    private int[] intents;

    /**
     * HTTP 客户端实例
     */
    private volatile HttpClient httpClient;

    /**
     * WebSocket 实例
     */
    private volatile WebSocket webSocket;

    /**
     * 运行状态标识
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 是否使用 Webhook 模式
     */
    private volatile boolean useWebhookMode;

    /**
     * 访问令牌
     */
    private volatile String accessToken;

    /**
     * 会话 标识
     */
    private volatile String sessionId;

    /**
     * 最后收到的序列号
     */
    private final AtomicInteger lastSeq = new AtomicInteger(0);

    /**
     * 心跳调度执行器
     */
    private ScheduledExecutorService heartbeatExecutor;

    /**
     * 配置加载器
     */
    private ConfigSaveOrLoader configSaveOrLoader;

    /**
     * 消息监听器列表
     */
    private final List<BotMessageListener> messageListeners
            = new CopyOnWriteArrayList<>();

    /**
     * 错误监听器列表
     */
    private final List<BotErrorListener> errorListeners
            = new CopyOnWriteArrayList<>();

    /**
     * 用户存储实例
     */
    private BotUserStore userStore = new InMemoryBotUserStore();

    /**
     * WebSocket Hello 操作码
     */
    private static final int WS_OP_HELLO = 10;

    /**
     * WebSocket 心跳 ACK 操作码
     */
    private static final int WS_OP_HEARTBEAT_ACK = 11;

    /**
     * WebSocket Identify 操作码
     */
    private static final int WS_OP_IDENTIFY = 2;

    /**
     * WebSocket Dispatch 操作码
     */
    private static final int WS_OP_DISPATCH = 0;

    /**
     * WebSocket Reconnect 操作码
     */
    private static final int WS_OP_RECONNECT = 7;

    /**
     * WebSocket Invalid 会话 操作码
     */
    private static final int WS_OP_INVALID_SESSION = 9;

    /**
     * 退避初始等待时间（毫秒）
     */
    private static final long BACKOFF_INITIAL_MS = 1_000;

    /**
     * 退避最大等待时间（毫秒）
     */
    private static final long BACKOFF_MAX_MS = 30_000;

    /**
     * 退避倍增系数
     */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    @Override
    /**
     * 配置
     * @param token 令牌
     * @param secret secret
     * @param encodingAesKey 编码aes键
     */
    public BotClient configure(String token, String secret,
            String encodingAesKey) {
        if (StringUtils.isNotEmpty(token)) {
            this.appId = token;
        }
        if (StringUtils.isNotEmpty(secret)) {
            this.appSecret = secret;
        }
        if (StringUtils.isNotEmpty(encodingAesKey)) {
            this.botToken = encodingAesKey;
        }
        return this;
    }

    @Override
    /** 令牌 */
    public BotClient token(String token) {
        this.appId = token;
        return this;
    }

    @Override
    /** Secret */
    public BotClient secret(String secret) {
        this.appSecret = secret;
        return this;
    }

    @Override
    /** 编码aes键 */
    public BotClient encodingAesKey(String encodingAesKey) {
        this.botToken = encodingAesKey;
        return this;
    }

    @Override
    /** baseurl */
    public BotClient baseUrl(String baseUrl) {
        if (StringUtils.isNotEmpty(baseUrl)) {
            this.baseUrl = baseUrl;
        }
        return this;
    }

    @Override
    /**
     * 连接超时millis
     * @param connectTimeoutMillis 连接超时millis
     * @param readTimeoutMillis 读取超时millis
     * @param configSaveOrLoader 配置保存或加载
     * @param intents intents
     * @param token 令牌
     * @param ignored ignored
     * @param e e
     * @param e e
     * @param e e
     * @param appId appid
     * @param appSecret appsecret
     * @param authToken 认证令牌
     * @param authToken 认证令牌
     * @param sessionId 会话标识
     * @param WS_OP_IDENTIFY WS_OP_IDENTIFY
     * @param payload payload
     * @param array array
     * @param ws ws
     * @param ws ws
     * @param data 数据
     * @param last 最后一个
     * @param e e
     * @param ws ws
     * @param error 错误
     * @param error 错误
     * @param ws ws
     * @param statusCode 状态编码
     * @param reason ReasonMLML
     * @param ws ws
     * @param message 消息
     * @param ws ws
     * @param message 消息
     * @param rawMessage raw消息
     * @param d d
     * @param intervalMs 间隔ms
     * @param 10_000 10_000
     * @param interval 间隔
     * @param interval 间隔
     * @param ignored ignored
     * @param message 消息
     * @param true true
     * @param eventType 事件类型
     * @param data 数据
     * @param data 数据
     * @param e e
     * @param e e
     * @param eventType 事件类型
     * @param e e
     * @param eventType 事件类型
     * @param data 数据
     * @param List 列表
     * @param Map 映射
     * @param timestamp 时间戳
     * @param e e
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public BotClient connectTimeoutMillis(
            long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    @Override
    /** 读取超时millis */
    public BotClient readTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
        return this;
    }

    @Override
    /**
     * 配置保存或加载
     * @param configSaveOrLoader 配置保存或加载
     * @param intents intents
     * @param token 令牌
     * @param ignored ignored
     * @param e e
     * @param e e
     * @param e e
     * @param appId appid
     * @param appSecret appsecret
     * @param authToken 认证令牌
     * @param authToken 认证令牌
     * @param sessionId 会话标识
     * @param WS_OP_IDENTIFY WS_OP_IDENTIFY
     * @param payload payload
     * @param array array
     * @param ws ws
     * @param ws ws
     * @param data 数据
     * @param last 最后一个
     * @param e e
     * @param ws ws
     * @param error 错误
     * @param error 错误
     * @param ws ws
     * @param statusCode 状态编码
     * @param reason ReasonMLML
     * @param ws ws
     * @param message 消息
     * @param ws ws
     * @param message 消息
     * @param rawMessage raw消息
     * @param d d
     * @param intervalMs 间隔ms
     * @param 10_000 10_000
     * @param interval 间隔
     * @param interval 间隔
     * @param ignored ignored
     * @param message 消息
     * @param true true
     * @param eventType 事件类型
     * @param data 数据
     * @param data 数据
     * @param e e
     * @param e e
     * @param eventType 事件类型
     * @param e e
     * @param eventType 事件类型
     * @param data 数据
     * @param List 列表
     * @param Map 映射
     * @param timestamp 时间戳
     * @param e e
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public BotClient configSaveOrLoader(
            ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
        return this;
    }

    /**
     * 设置事件意图
     *
     * @param intents 意图数组
     * @return this
     */
    public QqBotClient intents(int... intents) {
        this.intents = intents;
        return this;
    }

    /**
     * 设置 Webhook 验证 令牌
     *
     * @param token 验证 令牌
     * @return this
     */
    public QqBotClient webhookVerifyToken(String token) {
        this.webhookVerifyToken = token;
        return this;
    }

    @Override
    /** 开始 */
    public BotClient start() {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException(
                    "appId (BotAppID) is required");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(
                        connectTimeoutMillis))
                .build();
        running.set(true);
        if (webhookVerifyToken == null
                || webhookVerifyToken.isBlank()) {
            useWebhookMode = false;
            connectWebSocket();
            log.info("QQ Bot client started in WebSocket mode");
        } else {
            useWebhookMode = true;
            log.info("QQ Bot client started in webhook mode");
        }
        return this;
    }

    @Override
    /** 停止 */
    public void stop() {
        running.set(false);
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE,
                        "stopped");
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
            webSocket = null;
        }
        sessionId = null;
        accessToken = null;
        log.info("QQ Bot client stopped");
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 建立 WebSocket 连接
     */
    private void connectWebSocket() {
        Thread wsThread = new Thread(() -> {
            long backoff = BACKOFF_INITIAL_MS;
            while (running.get()) {
                try {
                    String authToken = getAccessToken();
                    String gatewayUrl = getGatewayUrl(authToken);
                    URI wsUri = URI.create(gatewayUrl);
                    CompletableFuture<WebSocket> future
                            = httpClient.newWebSocketBuilder()
                            .connectTimeout(Duration.ofMillis(
                                    connectTimeoutMillis))
                            .buildAsync(wsUri,
                                    new WebSocketListener());
                    webSocket = future.get(connectTimeoutMillis,
                            TimeUnit.MILLISECONDS);
                    backoff = BACKOFF_INITIAL_MS;
                    synchronized (QqBotClient.this) {
                        QqBotClient.this.wait();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    notifyError(e);
                    backoff = sleepBackoff(backoff);
                    log.error("WebSocket connection failed: {}",
                            e.getMessage(), e);
                }
            }
        }, "qq-ws-thread");
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /**
     * 获取访问令牌
     * @return 获取access令牌的结果
     */
    private String getAccessToken() throws Exception {
        if (botToken != null && !botToken.isBlank()) {
            return "QQBot " + botToken;
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                    "appSecret is required when botToken "
                            + "is not provided");
        }
        String url = "https://bots.qq.com/app/getAppAccessToken";
        JsonObject body = new JsonObject()
                .fluent("appId", appId)
                .fluent("clientSecret", appSecret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.toJson(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = Json.fromJson(
                response.body(), Map.class);
        String token = (String) result.get("access_token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException(
                    "Failed to get access token: "
                            + response.body());
        }
        this.accessToken = token;
        return "QQBot " + token;
    }

    /**
     * 获取网关 URL
     */
    private String getGatewayUrl(String authToken)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/oauthme"))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Authorization", authToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = Json.fromJson(
                response.body(), Map.class);
        String wsUrl = (String) result.get("websocket");
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new RuntimeException(
                    "No websocket URL in response: "
                            + response.body());
        }
        return wsUrl;
    }

    /**
     * 发送 Identify 帧
     */
    private void identify() {
        if (webSocket == null) {
            return;
        }
        JsonObject payload = new JsonObject()
                .fluent("token", getEffectiveAuthToken())
                .fluent("intents", intents != null
                        ? or(intents)
                        : 0)
                .fluent("seq", lastSeq.get());
        if (sessionId != null) {
            payload.fluent("session_id", sessionId);
        }
        JsonObject frame = new JsonObject()
                .fluent("op", WS_OP_IDENTIFY)
                .fluent("d", payload);
        sendWsMessage(Json.toJson(frame));
    }

    /**
     * 获取effective认证令牌
     *
     * @return 获取effective认证令牌的结果
     */
    private String getEffectiveAuthToken() {
        if (botToken != null && !botToken.isBlank()) {
            return "QQBot " + botToken;
        }
        return "QQBot " + accessToken;
    }

    /**
     * 将意图数组按位或合并
     * @param array array
     * @return 或的结果
     */
    private int or(int[] array) {
        if (array == null || array.length == 0) {
            return 0;
        }
        int result = 0;
        for (int v : array) {
            result |= v;
        }
        return result;
    }

    /**
     * WebSocket 监听器内部类
     *
     * @author CH
     * @since 4.0.0
     */
    private class WebSocketListener implements WebSocket.Listener {

        /**
         * 文本缓冲区
         */
        private final StringBuilder textBuffer
                = new StringBuilder();

        @Override
        /** On打开 */
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket ws,
                CharSequence data,
                boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleWsMessage(message);
                } catch (Exception e) {
                    notifyError(e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        /** On记录错误 */
        public void onError(WebSocket ws, Throwable error) {
            notifyError(error);
            log.error("WebSocket error: {}",
                    error.getMessage(), error);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket ws,
                int statusCode,
                String reason) {
            webSocket = null;
            synchronized (QqBotClient.this) {
                QqBotClient.this.notifyAll();
            }
            return null;
        }

        @Override
        public CompletionStage<?> onPing(
                WebSocket ws,
                ByteBuffer message) {
            ws.sendPong(message);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(
                WebSocket ws,
                ByteBuffer message) {
            return null;
        }
    }

    /**
     * 处理 WebSocket 消息
     * @param rawMessage raw消息
     */
    @SuppressWarnings("unchecked")
    private void handleWsMessage(String rawMessage) {
        Map<String, Object> frame = Json.fromJson(rawMessage,
                Map.class);
        if (frame == null) {
            return;
        }
        int op = toInt(frame.get("op"), -1);
        Number seqNum = (Number) frame.get("s");
        if (seqNum != null) {
            lastSeq.set(seqNum.intValue());
        }
        if (op == WS_OP_HELLO) {
            Map<String, Object> hello
                    = (Map<String, Object>) frame.get("d");
            Number heartbeatInterval = hello != null
                    ? (Number) hello.get("heartbeat_interval")
                    : null;
            startHeartbeat(heartbeatInterval != null
                    ? heartbeatInterval.longValue()
                    : 45_000);
            identify();
        } else if (op == WS_OP_DISPATCH) {
            Map<String, Object> d
                    = (Map<String, Object>) frame.get("d");
            if (d != null) {
                String eventType = (String) d.get("type");
                handleEvent(eventType, d);
            }
        } else if (op == WS_OP_RECONNECT) {
            reconnectWebSocket();
        } else if (op == WS_OP_INVALID_SESSION) {
            sessionId = null;
            identify();
        } else {
            // 忽略未知操作码
        }
    }

    /**
     * 启动心跳
     * @param intervalMs 间隔ms
     */
    private void startHeartbeat(long intervalMs) {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "qq-heartbeat");
                    t.setDaemon(true);
                    return t;
                });
        long interval = Math.max(intervalMs, 10_000);
        heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeatAck,
                interval,
                interval,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 发送心跳确认
     */
    private void sendHeartbeatAck() {
        sendWsMessage("{\"op\":" + WS_OP_HEARTBEAT_ACK
                + ",\"d\":" + lastSeq.get() + "}");
    }

    /**
     * 重连 WebSocket
     */
    private void reconnectWebSocket() {
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE,
                        "reconnecting");
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
            webSocket = null;
        }
    }

    /**
     * 发送 WebSocket 消息
     * @param message 消息
     */
    private void sendWsMessage(String message) {
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.sendText(message, true);
        }
    }

    /**
     * 处理业务事件
     */
    @SuppressWarnings("unchecked")
    private void handleEvent(String eventType,
            Map<String, Object> data) {
        if (data == null) {
            return;
        }
        try {
            BotInboundMessage inbound = mapEventToMessage(
                    eventType, data);
            if (inbound != null) {
                String fromUser = inbound.getFromUser();
                if (fromUser != null && !fromUser.isBlank()) {
                    Map<String, Object> author
                            = (Map<String, Object>) data.get(
                            "author");
                    String username = author != null
                            ? (String) author.get("nick")
                            : fromUser;
                    userStore.upsert(BotUserInfo.builder()
                            .userId(fromUser)
                            .username(username)
                            .nickname(username)
                            .build());
                }
                for (BotMessageListener listener
                        : messageListeners) {
                    try {
                        listener.onMessage(inbound);
                    } catch (Exception e) {
                        notifyError(e);
                    }
                }
            }
        } catch (Exception e) {
            notifyError(e);
            log.error("Error handling event {}: {}",
                    eventType, e.getMessage(), e);
        }
    }

    /**
     * 将事件映射为入站消息
     */
    @SuppressWarnings("unchecked")
    private BotInboundMessage mapEventToMessage(
            String eventType,
            Map<String, Object> data) {
        if (!"C2C_MSG_RECEIVE".equals(eventType)
                && !"GROUP_ATBOT".equals(eventType)
                && !"CHANNEL_MSG_RECEIVE".equals(eventType)
                && !"FRIEND_MSG_RECEIVE".equals(eventType)
                && !"GROUP_MSG_RECEIVE".equals(eventType)) {
            return null;
        }
        String msgId = (String) data.get("id");
        String content = (String) data.get("content");
        Map<String, Object> author
                = (Map<String, Object>) data.get("author");
        String fromUser = author != null
                ? (String) author.get("openid")
                : null;
        String fromUserName = author != null
                ? (String) author.get("nick")
                : null;
        String groupOpenid = (String) data.get(
                "group_openid");
        String channelId = (String) data.get("channel_id");
        String guildId = (String) data.get("guild_id");
        boolean fromGroup = groupOpenid != null
                || guildId != null;
        String chatId = groupOpenid != null
                ? groupOpenid
                : channelId;
        List<String> mentionedList = new ArrayList<>();
        Object mentionsObj = data.get("mentions");
        if (mentionsObj instanceof List) {
            for (Object item : (List<?>) mentionsObj) {
                if (item instanceof Map) {
                    Object idObj = ((Map<?, ?>) item).get("id");
                    if (idObj != null
                            && !idObj.toString().isBlank()) {
                        mentionedList.add(idObj.toString());
                    }
                }
            }
        }
        String timestamp = (String) data.get("timestamp");
        long createTime = timestamp != null
                ? parseTimestamp(timestamp)
                : System.currentTimeMillis();
        return BotInboundMessage.builder()
                .msgId(msgId)
                .type(BotInboundMessage.Type.TEXT)
                .content(content != null ? content : "")
                .fromUser(fromUser)
                .fromUserName(fromUserName)
                .fromGroup(fromGroup)
                .chatId(chatId)
                .createTime(createTime)
                .mentionedList(mentionedList)
                .build();
    }

    /**
     * 解析时间戳
     * @param timestamp 时间戳
     * @return 解析时间戳的结果
     */
    private long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp) * 1000;
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    @Override
    /**
     * 发送文本
     * @param toUser 转为用户
     * @param content 内容
     */
    public BotSendResult sendText(String toUser,
            String content) {
        return send(BotOutboundMessage.text(toUser, content));
    }

    @Override
    /**
     * 发送文本异步
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendTextAsync(
            String toUser,
            String content) {
        return CompletableFuture.supplyAsync(
                () -> sendText(toUser, content));
    }

    @Override
    /**
     * 发送镜像
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    public BotSendResult sendImage(String toUser,
            String mediaPath) {
        return send(BotOutboundMessage.image(toUser, mediaPath));
    }

    @Override
    /**
     * 发送镜像异步
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendImageAsync(
            String toUser,
            String mediaPath) {
        return CompletableFuture.supplyAsync(
                () -> sendImage(toUser, mediaPath));
    }

    @Override
    /**
     * 发送Voice
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    public BotSendResult sendVoice(String toUser,
            String mediaPath) {
        return send(BotOutboundMessage.voice(toUser, mediaPath));
    }

    @Override
    /**
     * 发送视频
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     */
    public BotSendResult sendVideo(String toUser,
            String mediaPath,
            String title,
            String desc) {
        log.warn(
                "QQ bot does not support video messages "
                        + "directly");
        return BotSendResult.fail(-1,
                "QQ bot does not support video messages directly");
    }

    @Override
    /**
     * 发送文件
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    public BotSendResult sendFile(String toUser,
            String mediaPath) {
        return sendFileViaUpload(toUser, mediaPath);
    }

    @Override
    /** 发送 */
    public BotSendResult send(BotOutboundMessage message) {
        if (!running.get()) {
            return BotSendResult.fail(-1,
                    "Client is not running");
        }
        if (message.getType() == null) {
            return BotSendResult.fail(-1,
                    "Message type is required");
        }
        try {
            if (message.isToGroup()) {
                if (message.getMentionedUsers() != null
                        && !message.getMentionedUsers().isEmpty()) {
                    return sendGroupMentionInternal(
                            message.getToUser(),
                            message.getContent(),
                            message.getMentionedUsers());
                }
                return sendGroupTextInternal(
                        message.getToUser(),
                        message.getContent());
            }
            Type type = message.getType();
            if (type == Type.TEXT) {
                return sendTextInternal(message.getToUser(),
                        message.getContent());
            } else if (type == Type.IMAGE) {
                return sendImageInternal(message.getToUser(),
                        message.getMediaPath());
            } else if (type == Type.VOICE) {
                return sendVoiceInternal(message.getToUser(),
                        message.getMediaPath());
            } else if (type == Type.FILE) {
                return sendFileViaUpload(
                        message.getToUser(),
                        message.getMediaPath());
            } else {
                return BotSendResult.fail(-1,
                        "Unsupported message type: " + type);
            }
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send message: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    @Override
    /**
     * 发送异步
     * @param message 消息
     * @param toUser 转为用户
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendAsync(
            BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    /**
     * 发送文本内部
     * @param toUser 转为用户
     * @param content 内容
     */
    private BotSendResult sendTextInternal(String toUser,
            String content) {
        JsonObject body = new JsonObject()
                .fluent("content", content)
                .fluent("msg_type", 0)
                .fluent("idempotency_key",
                        UUID.randomUUID().toString());
        return sendApi("/v2/users/" + toUser + "/messages",
                body);
    }

    /**
     * 发送分组文本内部
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param 0 0
     * @param body 主体
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    private BotSendResult sendGroupTextInternal(
            String groupId, String content) {
        JsonObject body = new JsonObject()
                .fluent("content", content)
                .fluent("msg_type", 0)
                .fluent("idempotency_key",
                        UUID.randomUUID().toString());
        return sendApi("/groups/" + groupId + "/messages",
                body);
    }

    /**
     * 发送分组提及内部
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param 0 0
     * @param messageReference 消息引用
     * @param mentionedUserIds 提及用户标识
     * @param mentionedList 提及列表
     * @param body 主体
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 1 1
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param 2 2
     * @param base64 基础64
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    private BotSendResult sendGroupMentionInternal(
            String groupId,
            String content,
            List<String> mentionedUserIds) {
        JsonObject body = new JsonObject()
                .fluent("content", content)
                .fluent("msg_type", 0)
                .fluent("idempotency_key",
                        UUID.randomUUID().toString());
        JsonObject messageReference = new JsonObject()
                .fluent("idempotency_key",
                        UUID.randomUUID().toString());
        body.fluent("message_reference", messageReference);
        JsonObject mentionedList = new JsonObject();
        mentionedList.fluent("mentioned_id", mentionedUserIds);
        messageReference.fluent("mentioned_list",
                mentionedList);
        return sendApi("/groups/" + groupId + "/messages",
                body);
    }

    /**
     * 发送镜像内部
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    private BotSendResult sendImageInternal(String toUser,
            String mediaPath) {
        try {
            String base64 = Base64.getEncoder()
                    .encodeToString(Files.readAllBytes(
                            Paths.get(mediaPath)));
            JsonObject body = new JsonObject()
                    .fluent("file_info", 1)
                    .fluent("idempotency_key",
                            UUID.randomUUID().toString())
                    .fluent("spec", new JsonObject()
                            .fluent("key", base64));
            return sendApi("/v2/users/" + toUser
                            + "/messages",
                    body);
        } catch (IOException e) {
            log.error("Failed to read image: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1,
                    "Failed to read image: " + e.getMessage());
        }
    }

    /**
     * 发送voice内部
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    private BotSendResult sendVoiceInternal(String toUser,
            String mediaPath) {
        try {
            String base64 = Base64.getEncoder()
                    .encodeToString(Files.readAllBytes(
                            Paths.get(mediaPath)));
            JsonObject body = new JsonObject()
                    .fluent("file_info", 2)
                    .fluent("idempotency_key",
                            UUID.randomUUID().toString())
                    .fluent("spec", new JsonObject()
                            .fluent("key", base64));
            return sendApi("/v2/users/" + toUser
                            + "/messages",
                    body);
        } catch (IOException e) {
            log.error("Failed to read voice file: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1,
                    "Failed to read voice file: "
                            + e.getMessage());
        }
    }

    /**
     * 发送文件viaupload
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param 7 7
     * @param fileUuid 文件uuid
     * @param body 主体
     * @param e e
     * @param e e
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param path 路径
     * @param body 主体
     * @param 0 0
     * @param e e
     * @param e e
     * @param baseUrl baseurl
     * @param useWebhookMode usewebhookmode
     * @param userStore 用户存储
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    private BotSendResult sendFileViaUpload(
            String toUser, String mediaPath) {
        try {
            String fileUuid = uploadFile(toUser, mediaPath);
            if (fileUuid == null) {
                return BotSendResult.fail(-1,
                        "Failed to upload file");
            }
            JsonObject body = new JsonObject()
                    .fluent("file_info", 7)
                    .fluent("idempotency_key",
                            UUID.randomUUID().toString())
                    .fluent("spec", new JsonObject()
                            .fluent("uuid", fileUuid));
            return sendApi("/v2/users/" + toUser
                            + "/messages",
                    body);
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send file: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 上传文件
     */
    private String uploadFile(String toUser,
            String mediaPath) throws Exception {
        String boundary = "--" + UUID.randomUUID()
                .toString().replace("-", "");
        byte[] fileBytes = Files.readAllBytes(
                Paths.get(mediaPath));
        String filename = Paths.get(mediaPath)
                .getFileName().toString();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(("--" + boundary + "\r\n").getBytes(
                StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; "
                + "name=\"file_type\"; filename=\""
                + filename + "\"\r\n").getBytes(
                StandardCharsets.UTF_8));
        baos.write("Content-Type: application/octet-stream\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        baos.write(fileBytes);
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(
                StandardCharsets.UTF_8));
        byte[] multipartBody = baos.toByteArray();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl
                        + "/v2/users/" + toUser + "/files"))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Authorization",
                        getEffectiveAuthToken())
                .header("Content-Type",
                        "multipart/form-data; boundary="
                                + boundary)
                .POST(HttpRequest.BodyPublishers
                        .ofByteArray(multipartBody))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = Json.fromJson(
                response.body(), Map.class);
        return result != null
                ? (String) result.get("uuid")
                : null;
    }

    /**
     * 发送 API 请求
     * @param path 路径
     * @param body 主体
     * @return 发送api的结果
     */
    private BotSendResult sendApi(String path, JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(readTimeoutMillis))
                    .header("Authorization",
                            getEffectiveAuthToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.toJson(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = Json.fromJson(
                    response.body(), Map.class);
            if (result != null) {
                String msgId = (String) result.get("id");
                if (msgId != null) {
                    return BotSendResult.ok(msgId);
                }
                Integer code = toInt(result.get("code"), 0);
                if (code != 0) {
                    return BotSendResult.fail(code,
                            (String) result.getOrDefault(
                                    "message", ""));
                }
            }
            return BotSendResult.ok(null);
        } catch (Exception e) {
            notifyError(e);
            log.error("API request failed: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    @Override
    /** 获取配置 */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("appId", appId != null ? appId : "");
        config.put("baseUrl", baseUrl);
        config.put("running", running.get());
        config.put("useWebhookMode", useWebhookMode);
        config.put("sessionId",
                sessionId != null ? sessionId : "");
        return config;
    }

    @Override
    /** 用户存储 */
    public BotClient userStore(BotUserStore userStore) {
        if (userStore != null) {
            this.userStore = userStore;
        }
        return this;
    }

    @Override
    /** 列表用户 */
    public List<BotUserInfo> listUsers() {
        return userStore.findAll();
    }

    @Override
    /** 列表群体 */
    public List<BotGroupInfo> listGroups() {
        if (!running.get()) {
            return Collections.emptyList();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v2/groups"))
                    .timeout(Duration.ofMillis(readTimeoutMillis))
                    .header("Authorization",
                            getEffectiveAuthToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groupList = Json.fromJson(
                    response.body(),
                    List.class);
            if (groupList == null) {
                return Collections.emptyList();
            }
            List<BotGroupInfo> groups = new ArrayList<>();
            for (Map<String, Object> g : groupList) {
                String groupId = (String) g.getOrDefault(
                        "group_id",
                        g.get("gid"));
                String groupName = (String) g.getOrDefault(
                        "group_name",
                        g.get("name"));
                groups.add(BotGroupInfo.builder()
                        .groupId(groupId)
                        .groupName(groupName)
                        .build());
            }
            return groups;
        } catch (Exception e) {
            log.error("Failed to list groups: {}",
                    e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    /**
     * 发送转为分组
     * @param groupId 群体标识
     * @param content 内容
     */
    public BotSendResult sendToGroup(String groupId,
            String content) {
        return send(BotOutboundMessage.groupText(groupId, content));
    }

    @Override
    /**
     * 发送转为分组异步
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendToGroupAsync(
            String groupId,
            String content) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroup(groupId, content));
    }

    @Override
    /**
     * 发送转为分组提及
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public BotSendResult sendToGroupMention(
            String groupId,
            String content,
            List<String> mentionedUserIds) {
        return send(BotOutboundMessage.groupTextMention(
                groupId, content, mentionedUserIds));
    }

    @Override
    public CompletableFuture<BotSendResult>
    sendToGroupMentionAsync(
            String groupId,
            String content,
            List<String> mentionedUserIds) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroupMention(groupId, content,
                        mentionedUserIds));
    }

    @Override
    /**
     * 添加消息监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public BotClient addMessageListener(
            BotMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
        return this;
    }

    @Override
    /**
     * 移除消息监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param challengeToken challenge令牌
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param e e
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param value 值
     * @param defaultValue 默认值
     * @param n n
     * @param e e
     * @param ignored ignored
     */
    public BotClient removeMessageListener(
            BotMessageListener listener) {
        messageListeners.remove(listener);
        return this;
    }

    @Override
    /** 添加记录错误监听器 */
    public BotClient addErrorListener(BotErrorListener listener) {
        if (listener != null) {
            errorListeners.add(listener);
        }
        return this;
    }

    /**
     * 验证 Webhook 挑战令牌
     *
     * @param challengeToken 挑战令牌
     * @return 验证结果
     */
    public String verifyChallenge(String challengeToken) {
        if (webhookVerifyToken == null) {
            return challengeToken;
        }
        return webhookVerifyToken.equals(challengeToken)
                ? challengeToken
                : null;
    }

    /**
     * 是否使用 Webhook 模式
     *
     * @return true 表示使用 Webhook 模式
     */
    public boolean isUseWebhookMode() {
        return useWebhookMode;
    }

    /**
     * sleep退避
     *
     * @param currentBackoff 当前退避
     * @return sleep退避的结果
     */
    private long sleepBackoff(long currentBackoff) {
        long wait = Math.min(currentBackoff, BACKOFF_MAX_MS);
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (long) Math.min(
                currentBackoff * BACKOFF_MULTIPLIER,
                BACKOFF_MAX_MS);
    }

    /**
     * 转为int
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return 转为int的结果
     */
    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    /**
     * 通知记录错误
     *
     * @param e e
     */
    private void notifyError(Throwable e) {
        for (BotErrorListener listener : errorListeners) {
            try {
                listener.onError(e);
            } catch (Exception ignored) {
                // 忽略监听器异常
            }
        }
    }
}
