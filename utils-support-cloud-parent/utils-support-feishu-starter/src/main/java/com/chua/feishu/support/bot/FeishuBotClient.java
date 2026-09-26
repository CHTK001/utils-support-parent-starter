package com.chua.feishu.support.bot;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.*;
import com.chua.common.support.ai.bot.BotInboundMessage.Type;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.lark.oapi.Client;
import com.lark.oapi.core.cache.LocalCache;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.response.EventResp;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;

import lombok.extern.slf4j.Slf4j;

/**
 * 飞书 机器人 客户端，实现 {@link BotClient} 接口。
 * <p>使用飞书开放平台 SDK（oapi-sdk）发送消息。接收有两种模式：</p>
 * <ul>
 *   <li>长连接（默认）：{@link #start()} 建立 WebSocket 订阅事件，无需公网地址；</li>
 *   <li>Webhook：配置了验证令牌或 Encrypt Key 时启用，调用方需把 HTTP 回调体
 *       与请求头交给 {@link #handleCallback(String, Map)} 转发进来。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FeishuBotClient implements BotClient {

    /**
     * 应用 标识
     */
    private String appId;

    /**
     * 应用密钥
     */
    private String appSecret;

    /**
     * API 基础地址
     */
    private String baseUrl = "https://open.feishu.cn/open-apis";

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
     * Webhook 加密密钥，飞书后台「Encrypt Key」
     * <p>配置后回调体为 {@code {"encrypt":"..."}}，且请求头携带
     * {@code X-Lark-Signature}。</p>
     */
    private String webhookEncryptKey;

    /**
     * 飞书开放平台客户端实例
     */
    private volatile Client client;

    /**
     * 运行状态标识
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 事件长连接客户端
     */
    private volatile com.lark.oapi.ws.Client wsClient;

    /**
     * Bot 自身的 open_id，start 时解析，用于判定 mentionedBot
     */
    private volatile String botOpenId;

    /**
     * 是否使用 Webhook 模式
     */
    private volatile boolean useWebhookMode;

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

    @Override
    /**
     * 配置
     * @param token 令牌
     * @param secret secret
     * @param encodingAesKey 编码aes键
     */
    public BotClient configure(String token, String secret,
            String encodingAesKey) {
        if (token != null && !token.isEmpty()) {
            this.appId = token;
        }
        if (secret != null && !secret.isEmpty()) {
            this.appSecret = secret;
        }
        if (encodingAesKey != null && !encodingAesKey.isEmpty()) {
            this.webhookEncryptKey = encodingAesKey;
        }
        return this;
    }

    @Override
    /**
     * 令牌
    */
    public BotClient token(String token) {
        this.appId = token;
        return this;
    }

    @Override
    /**
     * Secret
    */
    public BotClient secret(String secret) {
        this.appSecret = secret;
        return this;
    }

    @Override
    /**
     * 编码aes键，对应飞书事件订阅的 Encrypt Key
     * @param encodingAesKey 编码aes键
    */
    public BotClient encodingAesKey(String encodingAesKey) {
        this.webhookEncryptKey = encodingAesKey;
        return this;
    }

    @Override
    /**
     * baseurl
    */
    public BotClient baseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
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
     * @param token 令牌
     * @param appSecret appsecret
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
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public BotClient connectTimeoutMillis(
            long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    @Override
    /**
     * 读取超时millis
    */
    public BotClient readTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
        return this;
    }

    @Override
    /**
     * 配置保存或加载
     * @param configSaveOrLoader 配置保存或加载
     * @param token 令牌
     * @param appSecret appsecret
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
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public BotClient configSaveOrLoader(
            ConfigSaveOrLoader configSaveOrLoader) {
        return this;
    }

    /**
     * 设置 Webhook 验证 令牌
     *
     * @param token 验证 令牌
     * @return this
     */
    public FeishuBotClient webhookVerifyToken(String token) {
        this.webhookVerifyToken = token;
        return this;
    }

    /**
     * 设置 Webhook 加密密钥（飞书后台「Encrypt Key」）
     * <p>配置后 {@link #handleCallback(String, Map)} 会解密
     * {@code {"encrypt":"..."}} 回调体并按 {@code X-Lark-Signature} 验签。</p>
     *
     * @param encryptKey 加密密钥
     * @return this
     */
    public FeishuBotClient webhookEncryptKey(String encryptKey) {
        this.webhookEncryptKey = encryptKey;
        return this;
    }

    @Override
    /**
     * 开始
    */
    public BotClient start() {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException("appId is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                    "appSecret is required");
        }

        // SDK 的 openBaseUrl 期望域名根(如 https://open.feishu.cn)，
        // 路径自带 /open-apis 后缀时需剥离，否则重复拼接 404
        String sdkBaseUrl = baseUrl.endsWith("/open-apis")
                ? baseUrl.substring(0,
                        baseUrl.length() - "/open-apis".length())
                : baseUrl;

        this.client = Client.newBuilder(appId, appSecret)
                .openBaseUrl(sdkBaseUrl)
                .tokenCache(LocalCache.getInstance())
                .requestTimeout(readTimeoutMillis,
                        TimeUnit.MILLISECONDS)
                .build();

        resolveBotOpenId();

        running.set(true);

        boolean webhookConfigured
                = (webhookVerifyToken != null
                        && !webhookVerifyToken.isBlank())
                        || (webhookEncryptKey != null
                                && !webhookEncryptKey.isBlank());
        if (!webhookConfigured) {
            useWebhookMode = false;
            com.lark.oapi.ws.Client ws
                    = new com.lark.oapi.ws.Client
                            .Builder(appId, appSecret)
                            .eventHandler(
                                    newEventDispatcher("", ""))
                            .autoReconnect(true)
                            .build();
            wsClient = ws;
            ws.start();
            log.info("Feishu Bot client started"
                    + " in long-connection mode");
        } else {
            useWebhookMode = true;
            log.info("Feishu Bot client started in webhook mode,"
                    + " hand callback bodies to"
                    + " handleCallback(body, headers)");
        }

        return this;
    }

    /**
     * 构造消息事件分发器
     *
     * @param verificationToken 交给 SDK 验签用的验证令牌，空串表示跳过验签
     * @param encryptKey        加密密钥，空串表示回调体不加密
     * @return 事件分发器
     */
    private EventDispatcher newEventDispatcher(
            String verificationToken, String encryptKey) {
        return EventDispatcher
                .newBuilder(verificationToken, encryptKey)
                .onP2MessageReceiveV1(
                        new ImService.P2MessageReceiveV1Handler() {
                            @Override
                            public void handle(
                                    P2MessageReceiveV1 event) {
                                handleReceiveEvent(event);
                            }
                        })
                .build();
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        running.set(false);
        // SDK 2.4.19 的 ws.Client 只暴露 start()，disconnect() 为 protected 且不关闭线程池，
        // 这里通过反射：先 disconnect() 关闭 WebSocket，再 shutdownNow() 终止心跳/重连任务。
        com.lark.oapi.ws.Client ws = wsClient;
        if (ws != null) {
            try {
                java.lang.reflect.Method disconnect = com.lark.oapi.ws.Client.class
                        .getDeclaredMethod("disconnect");
                disconnect.setAccessible(true);
                disconnect.invoke(ws);
            } catch (Exception e) {
                log.warn("Feishu ws disconnect failed: {}", e.getMessage());
            }
            try {
                java.lang.reflect.Field executorField = com.lark.oapi.ws.Client.class
                        .getDeclaredField("executor");
                executorField.setAccessible(true);
                Object executor = executorField.get(ws);
                if (executor instanceof java.util.concurrent.ExecutorService pool) {
                    pool.shutdownNow();
                }
            } catch (Exception e) {
                log.warn("Feishu ws executor shutdown failed: {}", e.getMessage());
            }
        }
        wsClient = null;
        client = null;
        log.info("Feishu Bot client stopped");
    }

    /**
     * 仅校验 appId / appSecret 是否有效，<b>不建立长连接</b>：通过获取 tenant_access_token 验证。
     *
     * <p>绑定校验阶段若调用 {@link #start()}，会异步建立一条没有消息监听器的长连接，
     * 与随后 {@code AiBotChatService.startBot} 建立的真实托管连接并存；飞书可能把消息事件
     * 推给先连上、却无监听器的校验连接，导致真实客户端收不到消息。因此校验只走轻量 HTTP。</p>
     *
     * @param appId     飞书应用 App ID
     * @param appSecret 飞书应用 App Secret
     * @return 有效的 tenant_access_token（至少返回非空串）
     * @throws IllegalArgumentException 凭据无效或请求异常时抛出
     */
    public static String fetchTenantAccessToken(String appId, String appSecret) {
        try {
            String payload = "{\"app_id\":\"" + appId
                    + "\",\"app_secret\":\"" + appSecret + "\"}";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload,
                            java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            boolean ok = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*0\\b")
                    .matcher(body).find();
            if (ok) {
                java.util.regex.Matcher tokenMatcher = java.util.regex.Pattern.compile(
                        "\"tenant_access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (tokenMatcher.find()) {
                    return tokenMatcher.group(1);
                }
                return "ok";
            }
            java.util.regex.Matcher msgMatcher = java.util.regex.Pattern.compile(
                    "\"msg\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            String message = msgMatcher.find() ? msgMatcher.group(1) : body;
            throw new IllegalArgumentException("飞书凭据校验失败：" + message);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("飞书凭据校验请求失败：" + e.getMessage(), e);
        }
    }

    @Override
    /**
     * 是否Running
    */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    /**
     * 发送文本
    */
    public BotSendResult sendText(String toUser, String content) {
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
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public CompletableFuture<BotSendResult> sendTextAsync(
            String toUser, String content) {
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
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param message 消息
     * @param e e
     * @param e e
     * @param message 消息
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public CompletableFuture<BotSendResult> sendImageAsync(
            String toUser, String mediaPath) {
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
        log.warn("Feishu bot does not support voice messages");
        return BotSendResult.fail(-1,
                "Feishu bot does not support voice messages");
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
            String mediaPath, String title, String desc) {
        log.warn(
                "Feishu bot does not support video messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "Feishu bot does not support video messages via webhook");
    }

    @Override
    /**
     * 发送文件
     * @param toUser 转为用户
     * @param mediaPath media路径
     */
    public BotSendResult sendFile(String toUser,
            String mediaPath) {
        log.warn(
                "Feishu bot does not support file messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "Feishu bot does not support file messages via webhook");
    }

    @Override
    /**
     * 发送
    */
    public BotSendResult send(BotOutboundMessage message) {
        if (client == null) {
            return BotSendResult.fail(-1, "Client not started");
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public CompletableFuture<BotSendResult> sendAsync(
            BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    @Override
    /**
     * 获取配置
    */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("appId", appId != null ? appId : "");
        config.put("baseUrl", baseUrl);
        config.put("running", running.get());
        config.put("useWebhookMode", useWebhookMode);
        return config;
    }

    @Override
    /**
     * 用户存储
    */
    public BotClient userStore(BotUserStore userStore) {
        if (userStore != null) {
            this.userStore = userStore;
        }
        return this;
    }

    @Override
    /**
     * 列表用户
    */
    public List<BotUserInfo> listUsers() {
        return userStore.findAll();
    }

    @Override
    /**
     * 列表群体
    */
    public List<BotGroupInfo> listGroups() {
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            var req = new com.lark.oapi.service.im.v1.model
                    .ListChatReq();
            var resp = client.im().chat().list(req);
            if (resp.getCode() != 0) {
                return Collections.emptyList();
            }
            var data = resp.getData();
            if (data == null || data.getItems() == null) {
                return Collections.emptyList();
            }
            List<BotGroupInfo> groups = new ArrayList<>();
            for (var chat : data.getItems()) {
                groups.add(BotGroupInfo.builder()
                        .groupId(chat.getChatId())
                        .groupName(chat.getName())
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public CompletableFuture<BotSendResult> sendToGroupAsync(
            String groupId, String content) {
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
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
     * @param null 空
     * @param e e
     * @param e e
     * @param e e
     * @param currentBackoff 当前退避
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param BACKOFF_MAX_MS 退避_最大_MS
     * @param event 事件
     * @param Map 映射
     * @param msgType msg类型
     * @param e e
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    public BotClient removeMessageListener(
            BotMessageListener listener) {
        messageListeners.remove(listener);
        return this;
    }

    @Override
    /**
     * 添加记录错误监听器
    */
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
     * 处理飞书事件订阅的 Webhook 回调，长连接模式无需调用
     * <p>把 HTTP 请求体和请求头原样交给本方法，返回值即需回写给飞书的响应，
     * 用于完成 URL 验证握手并确认事件已受理。</p>
     * <p>未配置 Encrypt Key 时，飞书不下发签名头，只按请求体里的
     * {@code token} 与验证令牌比对；配置了 Encrypt Key 时，回调体为
     * {@code {"encrypt":"..."}}，由 SDK 解密并按 {@code X-Lark-Signature} 验签。</p>
     *
     * @param body    回调请求体原文
     * @param headers 回调请求头，键大小写不敏感
     * @return 需回写给飞书的响应
     */
    public FeishuCallbackResult handleCallback(
            String body, Map<String, String> headers) {
        if (body == null || body.isBlank()) {
            return FeishuCallbackResult.of(400, "{\"msg\":\"empty body\"}");
        }
        Map<String, Object> payload = parseCallbackPayload(body);
        if (payload == null) {
            return FeishuCallbackResult.of(400, "{\"msg\":\"invalid json\"}");
        }
        String encryptKey = webhookEncryptKey == null
                ? "" : webhookEncryptKey;
        String verifyToken = webhookVerifyToken == null
                ? "" : webhookVerifyToken;
        boolean encrypted = payload.get("encrypt") != null;
        if (!encrypted && !verifyRequestBodyToken(payload)) {
            return FeishuCallbackResult.of(401, "{\"msg\":\"invalid token\"}");
        }
        if (!encrypted && "url_verification".equals(payload.get("type"))) {
            return challengeResponse(payload.get("challenge"));
        }
        EventReq request = new EventReq();
        request.setBody(body.getBytes(StandardCharsets.UTF_8));
        request.setHeaders(normalizeHeaders(headers));
        try {
            // 飞书仅在配置 Encrypt Key 时加密回调体并下发签名头，
            // 明文体若交给带密钥的 SDK 会直接抛解密异常
            EventResp response = newEventDispatcher(
                    encrypted ? verifyToken : "",
                    encrypted ? encryptKey : "")
                    .handle(request);
            return FeishuCallbackResult.of(response.getStatusCode(),
                    response.getBody() == null
                            ? ""
                            : new String(response.getBody(),
                                    StandardCharsets.UTF_8));
        } catch (Throwable e) {
            notifyError(e);
            log.error("Feishu Bot callback failed: {}",
                    e.getMessage(), e);
            return FeishuCallbackResult.of(500,
                    "{\"msg\":\"internal error\"}");
        }
    }

    /**
     * 解析回调请求体
     *
     * @param body 回调请求体原文
     * @return 回调体对象，非法 JSON 或非对象时返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCallbackPayload(String body) {
        try {
            Object parsed = Jsons.DEFAULT.fromJson(body, Map.class);
            return parsed instanceof Map
                    ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 比对回调体携带的验证令牌
     *
     * @param payload 回调体对象
     * @return true 表示无需比对或比对通过
     */
    private boolean verifyRequestBodyToken(
            Map<String, Object> payload) {
        if (webhookVerifyToken == null || webhookVerifyToken.isBlank()) {
            return true;
        }
        Object header = payload.get("header");
        Object token = header instanceof Map
                ? ((Map<?, ?>) header).get("token")
                : payload.get("token");
        return token != null && webhookVerifyToken.equals(
                token.toString());
    }

    /**
     * 回显 URL 验证握手的 challenge
     * <p>SDK 用同一个 verificationToken 字段既校验握手令牌又决定是否验签，
     * 而明文体没有签名头可验，故握手在此应答。</p>
     *
     * @param challenge 挑战值
     * @return 需回写给飞书的响应
     */
    private static FeishuCallbackResult challengeResponse(
            Object challenge) {
        if (challenge == null || challenge.toString().isBlank()) {
            return FeishuCallbackResult.of(400,
                    "{\"msg\":\"challenge missing\"}");
        }
        return FeishuCallbackResult.of(200, Jsons.DEFAULT.toJson(
                Collections.singletonMap("challenge",
                        challenge.toString())));
    }

    /**
     * 归一化请求头键名
     * <p>SDK 的 EventReq 用全小写键取头，而 Servlet 容器给出的键大小写
     * 随实现而定，不转小写会取不到签名头。</p>
     *
     * @param headers 原始请求头
     * @return 键为小写的请求头
     */
    private static Map<String, List<String>> normalizeHeaders(
            Map<String, String> headers) {
        Map<String, List<String>> normalized = new HashMap<>();
        if (headers == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            normalized.put(entry.getKey()
                    .toLowerCase(Locale.ROOT),
                    Collections.singletonList(entry.getValue()));
        }
        return normalized;
    }

    /**
     * 处理长连接推送的接收消息事件
     *
     * @param event 消息接收事件
     */
    private void handleReceiveEvent(P2MessageReceiveV1 event) {
        try {
            P2MessageReceiveV1Data data = event.getEvent();
            if (data == null || data.getMessage() == null) {
                return;
            }
            EventMessage message = data.getMessage();
            String fromUser = null;
            if (data.getSender() != null
                    && data.getSender().getSenderId() != null) {
                fromUser = data.getSender()
                        .getSenderId().getOpenId();
            }
            boolean group
                    = "group".equals(message.getChatType());
            List<String> mentionedIds = new ArrayList<>();
            MentionEvent[] mentions = message.getMentions();
            if (mentions != null) {
                for (MentionEvent mention : mentions) {
                    mentionedIds.add(mention.getId() != null
                            ? mention.getId().getOpenId()
                            : mention.getKey());
                }
            }
            BotInboundMessage.BotInboundMessageBuilder builder
                    = BotInboundMessage.builder()
                            .msgId(message.getMessageId())
                            .type(mapMessageType(
                                    message.getMessageType()))
                            .content(resolveMentionPlaceholders(
                                    extractContent(
                                            message.getContent(),
                                            message.getMessageType()),
                                    mentions))
                            .fromUser(fromUser)
                            .fromGroup(group)
                            .chatId(group
                                    ? message.getChatId()
                                    : null)
                            .eventType("im.message.receive_v1")
                            .mentionedList(mentionedIds)
                            .createTime(parseMillis(
                                    message.getCreateTime(),
                                    System.currentTimeMillis()));
            String self = botOpenId;
            if (!group || self != null) {
                // 单聊不存在 @ 机器人；群聊未解析出机器人 open_id 时无法判定
                builder.mentionedBot(
                        self != null && mentionedIds.contains(self));
            }
            BotInboundMessage inbound = builder.build();
            if (fromUser != null) {
                userStore.upsert(BotUserInfo.builder()
                        .userId(fromUser)
                        .username(fromUser)
                        .nickname(fromUser)
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
        } catch (Exception e) {
            notifyError(e);
            log.error("Error handling event: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * 发送文本内部
     * @param receiveId 接收标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    private BotSendResult sendTextInternal(
            String receiveId, String content) {
        try {
            CreateMessageReq req = new CreateMessageReq();
            req.setReceiveIdType(receiveIdType(receiveId));
            Map<String, String> contentMap = new HashMap<>();
            contentMap.put("text", content);
            CreateMessageReqBody body = new CreateMessageReqBody();
            body.setReceiveId(receiveId);
            body.setMsgType("text");
            body.setContent(Jsons.DEFAULT.toJson(contentMap));
            req.setCreateMessageReqBody(body);
            CreateMessageResp resp = client.im().message()
                    .create(req);
            if (resp.getCode() == 0) {
                return BotSendResult.ok(
                        resp.getData() != null
                                ? resp.getData()
                                        .getMessageId()
                                : null);
            }
            return BotSendResult.fail(resp.getCode(),
                    resp.getMsg());
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send text: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 发送分组文本内部
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param e e
     * @param e e
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param userId 用户标识
     * @param atList at列表
     * @param e e
     * @param e e
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    private BotSendResult sendGroupTextInternal(
            String groupId, String content) {
        try {
            Map<String, String> contentMap = new HashMap<>();
            contentMap.put("text", content);
            CreateMessageReq req = new CreateMessageReq();
            req.setReceiveIdType("chat_id");
            CreateMessageReqBody body = new CreateMessageReqBody();
            body.setReceiveId(groupId);
            body.setMsgType("text");
            body.setContent(Jsons.DEFAULT.toJson(contentMap));
            req.setCreateMessageReqBody(body);
            CreateMessageResp resp = client.im().message()
                    .create(req);
            if (resp.getCode() == 0) {
                return BotSendResult.ok(
                        resp.getData() != null
                                ? resp.getData()
                                        .getMessageId()
                                : null);
            }
            return BotSendResult.fail(resp.getCode(),
                    resp.getMsg());
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send group text: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 发送群组 @ 提及消息
     */
    @SuppressWarnings("unchecked")
    private BotSendResult sendGroupMentionInternal(
            String groupId,
            String content,
            List<String> mentionedUserIds) {
        try {
            Map<String, Object> contentMap = new LinkedHashMap<>();
            contentMap.put("text", content);
            List<Map<String, String>> atList = new ArrayList<>();
            for (String userId : mentionedUserIds) {
                Map<String, String> at = new HashMap<>();
                at.put("open_id", userId);
                at.put("key", "@" + userId);
                atList.add(at);
            }
            contentMap.put("at", atList);
            CreateMessageReq req = new CreateMessageReq();
            req.setReceiveIdType("chat_id");
            CreateMessageReqBody body = new CreateMessageReqBody();
            body.setReceiveId(groupId);
            body.setMsgType("interactive");
            body.setContent(Jsons.DEFAULT.toJson(contentMap));
            req.setCreateMessageReqBody(body);
            CreateMessageResp resp = client.im().message()
                    .create(req);
            if (resp.getCode() == 0) {
                return BotSendResult.ok(
                        resp.getData() != null
                                ? resp.getData()
                                        .getMessageId()
                                : null);
            }
            return BotSendResult.fail(resp.getCode(),
                    resp.getMsg());
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send group mention: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 发送镜像内部
     * @param receiveId 接收标识
     * @param mediaPath media路径
     * @param imageBytes 镜像bytes
     * @param imageKey 镜像键
     * @param e e
     * @param e e
     * @param message 消息
     * @param msgType msg类型
     * @param e e
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    private BotSendResult sendImageInternal(
            String receiveId, String mediaPath) {
        try {
            byte[] imageBytes = Files.readAllBytes(
                    Paths.get(mediaPath));
            File imageFile = File.createTempFile("feishu_img_",
                    ".tmp");
            imageFile.deleteOnExit();
            Files.write(imageFile.toPath(), imageBytes);
            CreateImageReq uploadReq = new CreateImageReq();
            CreateImageReqBody uploadBody
                    = new CreateImageReqBody();
            uploadBody.setImageType("message");
            uploadBody.setImage(imageFile);
            uploadReq.setCreateImageReqBody(uploadBody);
            CreateImageResp uploadResp = client.im().image()
                    .create(uploadReq);
            if (uploadResp.getCode() != 0) {
                return BotSendResult.fail(uploadResp.getCode(),
                        uploadResp.getMsg());
            }
            String imageKey = uploadResp.getData().getImageKey();
            Map<String, String> contentMap = new HashMap<>();
            contentMap.put("image_key", imageKey);
            CreateMessageReq req = new CreateMessageReq();
            req.setReceiveIdType(receiveIdType(receiveId));
            CreateMessageReqBody body = new CreateMessageReqBody();
            body.setReceiveId(receiveId);
            body.setMsgType("image");
            body.setContent(Jsons.DEFAULT.toJson(contentMap));
            req.setCreateMessageReqBody(body);
            CreateMessageResp resp = client.im().message()
                    .create(req);
            if (resp.getCode() == 0) {
                return BotSendResult.ok(
                        resp.getData() != null
                                ? resp.getData()
                                        .getMessageId()
                                : null);
            }
            return BotSendResult.fail(resp.getCode(),
                    resp.getMsg());
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send image: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 提取消息内容
     *
     * @param contentStr 内容 JSON 字符串
     * @param msgType    消息类型
     * @return 提取出的文本内容
     */
    @SuppressWarnings("unchecked")
    private String extractContent(String contentStr,
            String msgType) {
        if (contentStr == null || msgType == null) {
            return "";
        }
        try {
            Map<String, Object> contentMap = Jsons.DEFAULT
                    .fromJson(contentStr, Map.class);
            if (contentMap == null) {
                return contentStr;
            }
            if ("text".equals(msgType)) {
                Object text = contentMap.get("text");
                return text != null ? text.toString()
                        : contentStr;
            }
            return contentStr;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 将正文里的 @ 占位符（如 {@code @_user_1}）还原为被 @ 者的真实名字。
     *
     * <p>飞书消息正文用 {@code @_user_N} 占位，真实名称在 {@code message.mentions}
     * （{@code key → name/open_id}）。这里据 mentions 把占位符替换成「@名字」；
     * 取不到名字时直接去掉占位符，避免把 {@code @_user_N} 原样发给模型。</p>
     *
     * @param text     已提取的正文
     * @param mentions 消息携带的提及列表（可空）
     * @return 占位符还原后的正文
     */
    private String resolveMentionPlaceholders(String text, MentionEvent[] mentions) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (mentions != null) {
            for (MentionEvent mention : mentions) {
                String key = mention.getKey();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                String name = mention.getName();
                String replacement = (name != null && !name.isBlank()) ? "@" + name.trim() : "";
                result = result.replace(key, replacement);
            }
        }
        // 清理未在 mentions 中列出的孤立占位符并收敛多余空白
        result = result.replaceAll("@_user_\\d+", "")
                .replaceAll("[ \t]{2,}", " ")
                .trim();
        return result;
    }

    /**
     * 映射消息类型
     * @param msgType msg类型
     * @param value 值
     * @param defaultValue 默认值
     * @param Number 数字
     * @param e e
     * @param ignored ignored
     * @param millis millis
     * @param e e
     */
    private static BotInboundMessage.Type mapMessageType(
            String msgType) {
        if (msgType == null) {
            return BotInboundMessage.Type.UNKNOWN;
        }
        if ("text".equals(msgType)) {
            return BotInboundMessage.Type.TEXT;
        } else if ("image".equals(msgType)) {
            return BotInboundMessage.Type.IMAGE;
        } else if ("audio".equals(msgType)) {
            return BotInboundMessage.Type.VOICE;
        } else if ("file".equals(msgType)) {
            return BotInboundMessage.Type.FILE;
        } else if ("video".equals(msgType)) {
            return BotInboundMessage.Type.VIDEO;
        } else {
            return BotInboundMessage.Type.UNKNOWN;
        }
    }

    /**
     * 调 bot/v3/info 解析 Bot 自身 open_id，用于判定 mentionedBot；失败仅记日志
     */
    @SuppressWarnings("unchecked")
    private void resolveBotOpenId() {
        try {
            RawResponse resp = client.get(
                    baseUrl + "/bot/v3/info",
                    null,
                    AccessTokenType.Tenant);
            Map<String, Object> body = Jsons.DEFAULT.fromJson(
                    new String(resp.getBody(),
                            StandardCharsets.UTF_8),
                    Map.class);
            if (body != null
                    && body.get("bot") instanceof Map) {
                Object id = ((Map<?, ?>) body.get("bot"))
                        .get("open_id");
                if (id instanceof String) {
                    botOpenId = (String) id;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve bot open_id: {}",
                    e.getMessage());
        }
    }

    /**
     * 按 ID 前缀推断 receive_id_type
     * <p>飞书三类标识前缀不同：{@code oc_} 群 chat_id、{@code on_} 用户
     * union_id、{@code ou_} 用户 open_id。</p>
     *
     * @param receiveId 接收者 ID
     * @return receive_id_type 取值
     */
    private static String receiveIdType(String receiveId) {
        if (receiveId == null) {
            return "open_id";
        }
        if (receiveId.startsWith("oc_")) {
            return "chat_id";
        }
        return receiveId.startsWith("on_") ? "union_id" : "open_id";
    }

    /**
     * 解析飞书的毫秒时间戳字符串
     *
     * @param millis   时间戳字符串，飞书以字符串下发
     * @param fallback 缺失或非法时的兜底值
     * @return 毫秒时间戳
     */
    private static long parseMillis(String millis, long fallback) {
        if (millis == null || millis.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(millis.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
