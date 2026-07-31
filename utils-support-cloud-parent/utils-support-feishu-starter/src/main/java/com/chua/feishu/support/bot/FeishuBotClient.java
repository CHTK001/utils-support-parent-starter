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
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;

import lombok.extern.slf4j.Slf4j;

/**
 * 飞书 Bot 客户端，实现 {@link BotClient} 接口。
 * <p>使用飞书开放平台 SDK（oapi-sdk）发送消息，
 * 通过事件出站轮询（Outbox）或 Webhook 回调接收用户消息。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Slf4j
public class FeishuBotClient implements BotClient {

    /**
     * 应用 ID
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
     * Webhook 验证 Token
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
     * 事件轮询线程
     */
    private volatile Thread eventThread;

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

    /**
     * 事件轮询间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MS = 1_000;

    @Override
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
    public BotClient token(String token) {
        this.appId = token;
        return this;
    }

    @Override
    public BotClient secret(String secret) {
        this.appSecret = secret;
        return this;
    }

    @Override
    public BotClient encodingAesKey(String encodingAesKey) {
        return this;
    }

    @Override
    public BotClient baseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            this.baseUrl = baseUrl;
        }
        return this;
    }

    @Override
    public BotClient connectTimeoutMillis(
            long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    @Override
    public BotClient readTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
        return this;
    }

    @Override
    public BotClient configSaveOrLoader(
            ConfigSaveOrLoader configSaveOrLoader) {
        return this;
    }

    /**
     * 设置 Webhook 验证 Token
     *
     * @param token 验证 Token
     * @return this
     */
    public FeishuBotClient webhookVerifyToken(String token) {
        this.webhookVerifyToken = token;
        return this;
    }

    @Override
    public BotClient start() {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException("appId is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                    "appSecret is required");
        }

        this.client = Client.newBuilder(appId, appSecret)
                .openBaseUrl(baseUrl)
                .tokenCache(LocalCache.getInstance())
                .requestTimeout(readTimeoutMillis,
                        TimeUnit.MILLISECONDS)
                .build();

        running.set(true);

        if (webhookVerifyToken == null
                || webhookVerifyToken.isBlank()) {
            useWebhookMode = false;
            eventThread = new Thread(this::pollEvents,
                    "feishu-event-poller");
            eventThread.setDaemon(true);
            eventThread.start();
            log.info("Feishu Bot client started in polling mode");
        } else {
            useWebhookMode = true;
            log.info("Feishu Bot client started in webhook mode");
        }

        return this;
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = eventThread;
        if (t != null) {
            t.interrupt();
            eventThread = null;
        }
        client = null;
        log.info("Feishu Bot client stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public BotSendResult sendText(String toUser, String content) {
        return send(BotOutboundMessage.text(toUser, content));
    }

    @Override
    public CompletableFuture<BotSendResult> sendTextAsync(
            String toUser, String content) {
        return CompletableFuture.supplyAsync(
                () -> sendText(toUser, content));
    }

    @Override
    public BotSendResult sendImage(String toUser,
            String mediaPath) {
        return send(BotOutboundMessage.image(toUser, mediaPath));
    }

    @Override
    public CompletableFuture<BotSendResult> sendImageAsync(
            String toUser, String mediaPath) {
        return CompletableFuture.supplyAsync(
                () -> sendImage(toUser, mediaPath));
    }

    @Override
    public BotSendResult sendVoice(String toUser,
            String mediaPath) {
        log.warn("Feishu bot does not support voice messages");
        return BotSendResult.fail(-1,
                "Feishu bot does not support voice messages");
    }

    @Override
    public BotSendResult sendVideo(String toUser,
            String mediaPath, String title, String desc) {
        log.warn(
                "Feishu bot does not support video messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "Feishu bot does not support video messages via webhook");
    }

    @Override
    public BotSendResult sendFile(String toUser,
            String mediaPath) {
        log.warn(
                "Feishu bot does not support file messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "Feishu bot does not support file messages via webhook");
    }

    @Override
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
    public CompletableFuture<BotSendResult> sendAsync(
            BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("appId", appId != null ? appId : "");
        config.put("baseUrl", baseUrl);
        config.put("running", running.get());
        config.put("useWebhookMode", useWebhookMode);
        return config;
    }

    @Override
    public BotClient userStore(BotUserStore userStore) {
        if (userStore != null) {
            this.userStore = userStore;
        }
        return this;
    }

    @Override
    public List<BotUserInfo> listUsers() {
        return userStore.findAll();
    }

    @Override
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
    public BotSendResult sendToGroup(String groupId,
            String content) {
        return send(BotOutboundMessage.groupText(groupId, content));
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupAsync(
            String groupId, String content) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroup(groupId, content));
    }

    @Override
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
    public BotClient addMessageListener(
            BotMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
        return this;
    }

    @Override
    public BotClient removeMessageListener(
            BotMessageListener listener) {
        messageListeners.remove(listener);
        return this;
    }

    @Override
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
     * 轮询事件
     */
    @SuppressWarnings("unchecked")
    private void pollEvents() {
        long backoff = BACKOFF_INITIAL_MS;
        while (running.get()) {
            try {
                RawResponse resp = client.post(
                        baseUrl + "/im/v1/messages/delta",
                        null,
                        AccessTokenType.Tenant);
                if (resp.getStatusCode() != 200) {
                    backoff = sleepBackoff(backoff);
                    continue;
                }
                Map<String, Object> body = Jsons.DEFAULT.fromJson(
                        new String(resp.getBody(),
                                StandardCharsets.UTF_8),
                        Map.class);
                if (body == null) {
                    backoff = sleepBackoff(backoff);
                    continue;
                }
                int code = toInt(body.get("code"), -1);
                if (code != 0) {
                    backoff = sleepBackoff(backoff);
                    continue;
                }
                backoff = BACKOFF_INITIAL_MS;
                Map<String, Object> data
                        = (Map<String, Object>) body.get("data");
                if (data == null) {
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }
                List<Map<String, Object>> events
                        = (List<Map<String, Object>>) data.get(
                        "items");
                if (events == null || events.isEmpty()) {
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }
                for (Map<String, Object> event : events) {
                    handleEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                backoff = sleepBackoff(backoff);
                notifyError(e);
                log.error("Error polling events: {}",
                        e.getMessage(), e);
            }
        }
    }

    private long sleepBackoff(long currentBackoff) {
        long wait = Math.min(currentBackoff, BACKOFF_MAX_MS);
        sleep(wait);
        return (long) Math.min(currentBackoff
                * BACKOFF_MULTIPLIER, BACKOFF_MAX_MS);
    }

    /**
     * 处理单个事件
     */
    @SuppressWarnings("unchecked")
    private void handleEvent(Map<String, Object> event) {
        try {
            String eventType
                    = (String) event.get("event_type");
            if (!"im.message.receive_v1".equals(eventType)) {
                return;
            }
            Map<String, Object> eventData
                    = (Map<String, Object>) event.get("event");
            if (eventData == null) {
                return;
            }
            Map<String, Object> message
                    = (Map<String, Object>) eventData.get(
                    "message");
            if (message == null) {
                return;
            }
            String chatType
                    = (String) message.get("chat_type");
            String msgId = (String) message.get(
                    "message_id");
            String msgType = (String) message.get(
                    "message_type");
            String chatId = (String) message.get("chat_id");
            Map<String, Object> sender
                    = (Map<String, Object>) eventData.get(
                    "sender");
            String fromUser = null;
            if (sender != null) {
                Object senderId = sender.get("sender_id");
                if (senderId instanceof Map) {
                    fromUser = (String) ((Map<?, ?>) senderId)
                            .get("open_id");
                }
            }
            BotInboundMessage.Type type
                    = mapMessageType(msgType);
            String content = extractContent(message, msgType);
            BotInboundMessage inbound = BotInboundMessage
                    .builder()
                    .msgId(msgId)
                    .type(type)
                    .content(content)
                    .fromUser(fromUser)
                    .fromGroup("group".equals(chatType))
                    .chatId("group".equals(chatType)
                            ? chatId
                            : null)
                    .createTime(System.currentTimeMillis())
                    .build();
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

    private BotSendResult sendTextInternal(
            String receiveId, String content) {
        try {
            CreateMessageReq req = new CreateMessageReq();
            req.setReceiveIdType("chat_id");
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
            req.setReceiveIdType("chat_id");
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
     */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> message,
            String msgType) {
        if (message == null || msgType == null) {
            return "";
        }
        try {
            Object contentObj = message.get("content");
            if (contentObj == null) {
                return "";
            }
            String contentStr = contentObj instanceof String
                    ? (String) contentObj
                    : Jsons.DEFAULT.toJson(contentObj);
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

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private void notifyError(Throwable e) {
        for (BotErrorListener listener : errorListeners) {
            try {
                listener.onError(e);
            } catch (Exception ignored) {
                // 忽略监听器异常
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
