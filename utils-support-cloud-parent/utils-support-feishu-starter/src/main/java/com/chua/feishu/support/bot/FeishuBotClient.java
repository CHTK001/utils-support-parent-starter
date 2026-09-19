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
 * <p>使用飞书开放平台 SDK（oapi-sdk）发送消息，
 * 通过事件出站轮询（Outbox）或 Webhook 回调接收用户消息。</p>
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
        return this;
    }

    @Override
    /** baseurl */
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
    /** 读取超时millis */
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

    @Override
    /** 开始 */
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

        if (webhookVerifyToken == null
                || webhookVerifyToken.isBlank()) {
            useWebhookMode = false;
            EventDispatcher dispatcher = EventDispatcher
                    .newBuilder("", "")
                    .onP2MessageReceiveV1(
                            new ImService.P2MessageReceiveV1Handler() {
                                @Override
                                public void handle(
                                        P2MessageReceiveV1 event) {
                                    handleReceiveEvent(event);
                                }
                            })
                    .build();
            com.lark.oapi.ws.Client ws
                    = new com.lark.oapi.ws.Client
                            .Builder(appId, appSecret)
                            .eventHandler(dispatcher)
                            .autoReconnect(true)
                            .build();
            wsClient = ws;
            ws.start();
            log.info("Feishu Bot client started"
                    + " in long-connection mode");
        } else {
            useWebhookMode = true;
            log.info("Feishu Bot client started in webhook mode");
        }

        return this;
    }

    @Override
    /** 停止 */
    public void stop() {
        running.set(false);
        // SDK 2.4.19 的 ws.Client 未暴露 close,只能释放引用
        wsClient = null;
        client = null;
        log.info("Feishu Bot client stopped");
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    /** 发送文本 */
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
    /** 发送 */
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
    /** 获取配置 */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("appId", appId != null ? appId : "");
        config.put("baseUrl", baseUrl);
        config.put("running", running.get());
        config.put("useWebhookMode", useWebhookMode);
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
                            .content(extractContent(
                                    message.getContent(),
                                    message.getMessageType()))
                            .fromUser(fromUser)
                            .fromGroup(group)
                            .chatId(group
                                    ? message.getChatId()
                                    : null)
                            .eventType("im.message.receive_v1")
                            .mentionedList(mentionedIds)
                            .createTime(
                                    System.currentTimeMillis());
            String self = botOpenId;
            if (group && self != null) {
                builder.mentionedBot(
                        mentionedIds.contains(self));
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
     * 按 ID 前缀推断 receive_id_type：oc_ 开头为群 chat_id，其余按用户 open_id
     *
     * @param receiveId 接收者 ID
     * @return receive_id_type 取值
     */
    private static String receiveIdType(String receiveId) {
        return receiveId != null && receiveId.startsWith("oc_")
                ? "chat_id" : "open_id";
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
