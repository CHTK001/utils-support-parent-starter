package com.chua.dingding.support.bot;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.alibaba.fastjson2.JSON;
import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotErrorListener;
import com.chua.common.support.ai.bot.BotGroupInfo;
import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotInboundMessage.Type;
import com.chua.common.support.ai.bot.BotMessageListener;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.ai.bot.BotUserInfo;
import com.chua.common.support.ai.bot.BotUserStore;
import com.chua.common.support.ai.bot.InMemoryBotUserStore;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
   * 钉钉 机器人 客户端，实现 {@link BotClient} 接口。
 * <p>支持 Webhook 模式（接收消息通过回调）和发送消息（文本、图片等）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DingTalkBotClient implements BotClient {

    /**
     * HMAC SHA256 算法名称
     */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * JSON 媒体类型
     */
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

    /**
     * Webhook URL
     */
    private String webhookUrl;

    /**
     * 签名密钥
     */
    private String secret;

    /**
      * 应用 键
     */
    private String appKey;

    /**
     * 应用 Secret
     */
    private String appSecret;

    /**
     * API 基础地址
     */
    private String baseUrl =
            "https://oapi.dingtalk.com/robot/send?access_token=";

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
     * 运行状态标识
     */
    private volatile boolean running;

    /**
     * HTTP 客户端实例
     */
    private OkHttpClient httpClient;

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
        if (token != null && !token.isBlank()) {
            this.webhookUrl = baseUrl + token;
        }
        if (secret != null && !secret.isBlank()) {
            this.secret = secret;
        }
        return this;
    }

    @Override
    /** 令牌 */
    public BotClient token(String token) {
        if (token != null && !token.isBlank()) {
            this.webhookUrl = baseUrl + token;
        }
        return this;
    }

    @Override
    /** Secret */
    public BotClient secret(String secret) {
        this.secret = secret;
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
        if (baseUrl != null && !baseUrl.isBlank()) {
            this.baseUrl = baseUrl;
        }
        return this;
    }

    @Override
    /** 连接超时millis */
    public BotClient connectTimeoutMillis(long connectTimeoutMillis) {
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
    /** 配置保存或加载 */
    public BotClient configSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        return this;
    }

    /**
     * 设置 Webhook URL
     *
     * @param webhookUrl Webhook 地址
     * @return this
     */
    public DingTalkBotClient webhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        return this;
    }

    /**
      * 设置 Webhook 验证 令牌
     *
     * @param token 验证 令牌
     * @return this
     */
    public DingTalkBotClient webhookVerifyToken(String token) {
        this.webhookVerifyToken = token;
        return this;
    }

    @Override
    /** 开始 */
    public BotClient start() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(connectTimeoutMillis,
                            TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                    .build();
        }
        running = true;
        log.info("DingTalk Bot client started, webhookUrl={}",
                webhookUrl);
        return this;
    }

    @Override
    /** 停止 */
    public void stop() {
        running = false;
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            httpClient = null;
        }
        log.info("DingTalk Bot client stopped");
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running;
    }

    @Override
    /** 发送文本 */
    public BotSendResult sendText(String toUser, String content) {
        Map<String, String> text
                = Collections.singletonMap("content", content);
        Map<String, Object> msg = new ConcurrentHashMap<>();
        msg.put("msgtype", "text");
        msg.put("text", text);
        return sendInternal(msg);
    }

    @Override
    /** 发送镜像 */
    public BotSendResult sendImage(String toUser, String mediaPath) {
        try {
            byte[] imageBytes = Files.readAllBytes(
                    Paths.get(mediaPath));
            String base64 = Base64.getEncoder()
                    .encodeToString(imageBytes);
            // 钉钉 webhook 不支持直接发图片，需要通过 markdown 方式
            String markdownContent = "![image](data:image/png;base64,"
                    + base64 + ")";
            Map<String, String> markdown
                    = new ConcurrentHashMap<>();
            markdown.put("title", "image");
            markdown.put("text", markdownContent);
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgtype", "markdown");
            msg.put("markdown", markdown);
            return sendInternal(msg);
        } catch (IOException e) {
            notifyError(e);
            log.error("Failed to send image: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    @Override
    /** 发送Voice */
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        log.warn(
                "DingTalk bot does not support voice messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "DingTalk bot does not support voice messages via webhook");
    }

    @Override
    /**
      * 发送视频
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param title title
     * @param desc desc
     */
    public BotSendResult sendVideo(String toUser, String mediaPath,
            String title, String desc) {
        log.warn(
                "DingTalk bot does not support video messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "DingTalk bot does not support video messages via webhook");
    }

    @Override
    /** 发送文件 */
    public BotSendResult sendFile(String toUser, String mediaPath) {
        log.warn(
                "DingTalk bot does not support file messages "
                        + "via webhook");
        return BotSendResult.fail(-1,
                "DingTalk bot does not support file messages via webhook");
    }

    @Override
    /** 发送 */
    public BotSendResult send(BotOutboundMessage message) {
        if (message.getType() == null) {
            return BotSendResult.fail(-1,
                    "Message type is required");
        }
        Type type = message.getType();
        if (type == Type.TEXT) {
            return sendText(message.getToUser(),
                    message.getContent());
        } else if (type == Type.IMAGE) {
            return sendImage(message.getToUser(),
                    message.getMediaPath());
        } else {
            return BotSendResult.fail(-1,
                    "Unsupported message type: " + type);
        }
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
     * @param message 消息
     * @param groupId 群体标识
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param false false
     * @param text 文本
     * @param at at
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param userStore 用户存储
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendTextAsync(
            String toUser, String content) {
        return CompletableFuture.supplyAsync(
                () -> sendText(toUser, content));
    }

    @Override
    /**
      * 发送镜像异步
     * @param toUser 转为用户
     * @param mediaPath media路径
     * @param mediaPath media路径
     * @param message 消息
     * @param groupId 群体标识
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param false false
     * @param text 文本
     * @param at at
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param userStore 用户存储
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendImageAsync(
            String toUser, String mediaPath) {
        return CompletableFuture.supplyAsync(
                () -> sendImage(toUser, mediaPath));
    }

    @Override
    /**
      * 发送异步
     * @param message 消息
     * @param groupId 群体标识
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param content 内容
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param false false
     * @param text 文本
     * @param at at
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param userStore 用户存储
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    public CompletableFuture<BotSendResult> sendAsync(
            BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    @Override
    /** 列表群体 */
    public List<BotGroupInfo> listGroups() {
        return Collections.emptyList();
    }

    @Override
    /**
      * 发送转为分组
     * @param groupId 群体标识
     * @param content 内容
     */
    public BotSendResult sendToGroup(String groupId,
            String content) {
        log.warn("Use webhookUrl to target specific group");
        return BotSendResult.fail(-1,
                "Use webhookUrl to target specific group");
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
     * @param false false
     * @param text 文本
     * @param at at
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param userStore 用户存储
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
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
     * @param false false
     * @param text 文本
     * @param at at
     * @param groupId 群体标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param content 内容
     * @param mentionedUserIds 提及用户标识
     * @param userStore 用户存储
     * @param listener 监听器
     * @param listener 监听器
     * @param listener 监听器
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    public BotSendResult sendToGroupMention(
            String groupId,
            String content,
            List<String> mentionedUserIds) {
        Map<String, String> text = new ConcurrentHashMap<>();
        text.put("content", content);
        Map<String, Object> at = new ConcurrentHashMap<>();
        at.put("atMobiles", mentionedUserIds);
        at.put("isAtAll", false);
        Map<String, Object> msg = new ConcurrentHashMap<>();
        msg.put("msgtype", "text");
        msg.put("text", text);
        msg.put("at", at);
        return sendInternal(msg);
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
    /** 添加消息监听器 */
    public BotClient addMessageListener(BotMessageListener listener) {
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
     * @param running running
     * @param jsonBody json主体
     * @param e e
     * @param e e
     * @param e e
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
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

    @Override
    /** 获取配置 */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("webhookUrl",
                webhookUrl != null ? webhookUrl : null);
        config.put("running", running);
        return config;
    }

    /**
     * 处理回调请求
     *
     * @param jsonBody 请求体 JSON 字符串
     * @return true 表示处理成功
     */
    public boolean handleCallback(String jsonBody) {
        try {
            Map<String, Object> data = JSON.parseObject(jsonBody);
            String msgType = (String) data.get("msgtype");
            if (msgType == null) {
                return false;
            }
            BotInboundMessage inbound = parseInbound(data);
            if (inbound != null) {
                for (BotMessageListener listener
                        : messageListeners) {
                    try {
                        listener.onMessage(inbound);
                    } catch (Exception e) {
                        notifyError(e);
                    }
                }
                return true;
            }
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to handle callback: {}",
                    e.getMessage(), e);
        }
        return false;
    }

    /**
      * 发送内部
     * @param message 消息
     * @param JSON_MEDIA_TYPE JSON_MEDIA_类型
     * @param errcode errcode
     * @param errmsg errmsg
     * @param e e
     * @param e e
     * @param HMAC_SHA256 HMAC_SHA256
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    private BotSendResult sendInternal(
            Map<String, Object> message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return BotSendResult.fail(-1,
                    "webhookUrl is not configured");
        }
        try {
            String requestUrl = buildRequestUrl();
            String bodyJson = JSON.toJSONString(message);
            Request request = new Request.Builder()
                    .url(requestUrl)
                    .post(RequestBody.create(bodyJson,
                            JSON_MEDIA_TYPE))
                    .build();
            try (Response response = httpClient.newCall(request)
                    .execute()) {
                if (response.isSuccessful()
                        && response.body() != null) {
                    String respBody = response.body().string();
                    Map<String, Object> resp = JSON.parseObject(
                            respBody);
                    int errcode = resp.get("errcode") != null
                            ? ((Number) resp.get("errcode"))
                                    .intValue()
                            : -1;
                    if (errcode == 0) {
                        log.debug("Message sent successfully");
                        return BotSendResult.ok(null);
                    } else {
                        String errmsg = (String) resp.get(
                                "errmsg");
                        log.error("DingTalk API error: code={}, msg={}",
                                errcode, errmsg);
                        return BotSendResult.fail(errcode,
                                errmsg != null ? errmsg : "");
                    }
                } else {
                    log.error("HTTP error: code={}, message={}",
                            response.code(), response.message());
                    return BotSendResult.fail(response.code(),
                            "" + response.message());
                }
            }
        } catch (Exception e) {
            notifyError(e);
            log.error("Failed to send message: {}",
                    e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 构建请求url
     *
     * @return 构建请求url的结果
     */
    private String buildRequestUrl() throws Exception {
        if (secret == null || secret.isBlank()) {
            return webhookUrl;
        }
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256));
        byte[] signData = mac.doFinal(
                stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(
                Base64.getEncoder().encodeToString(signData),
                StandardCharsets.UTF_8.name());
        return webhookUrl + "&timestamp=" + timestamp
                + "&sign=" + sign;
    }

    /**
     * 解析Inbound
     * @param data 数据
     * @param type 类型
     * @param e e
     * @param ignored ignored
     */
    private BotInboundMessage parseInbound(
            Map<String, Object> data) {
        String msgType = (String) data.get("msgtype");
        if (msgType == null) {
            return null;
        }
        String content = null;
        if ("text".equals(msgType)) {
            Map<String, Object> text
                    = (Map<String, Object>) data.get("text");
            if (text != null) {
                content = (String) text.get("content");
            }
        }
        Map<String, Object> sender
                = (Map<String, Object>) data.get("senderId");
        String fromUser = sender != null
                ? (String) sender.get("senderId")
                : null;
        if (fromUser == null) {
            fromUser = (String) data.get("admin");
        }
        String chatId = (String) data.get("chatid");
        boolean isGroup = chatId != null && !chatId.isEmpty();
        return BotInboundMessage.builder()
                .msgId((String) data.get("msgId"))
                .type(mapMsgType(msgType))
                .content(content != null ? content : "")
                .fromUser(fromUser)
                .fromGroup(isGroup)
                .chatId(isGroup ? chatId : null)
                .createTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 映射msg类型
     *
     * @param type 类型
     * @return 映射msg类型的结果
     */
    private Type mapMsgType(String type) {
        if (type == null) {
            return Type.UNKNOWN;
        }
        if ("text".equals(type)) {
            return Type.TEXT;
        } else if ("picture".equals(type)) {
            return Type.IMAGE;
        } else {
            return Type.UNKNOWN;
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
