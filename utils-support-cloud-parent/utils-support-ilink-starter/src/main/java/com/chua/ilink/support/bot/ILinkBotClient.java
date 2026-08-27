package com.chua.ilink.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotMessageListener;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.ai.bot.BotGroupInfo;
import com.chua.common.support.ai.bot.BotUserInfo;
import com.chua.common.support.ai.bot.QrcodeListener;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import static com.chua.common.support.utils.MapUtils.getString;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink Bot 客户端。
 *
 * <p>基于 ilinkai.weixin.qq.com 的 Bot API，
 * 支持扫码登录、长轮询接收消息、文本/媒体发送。</p>
 *
 * <p>登录流程：{@code loginWithQR()} 获取二维码 → 用户微信扫码确认 → 获得 token/botId。
 * 登录成功后通过长轮询接收消息，使用 contextToken 回复。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ILinkBotClient implements BotClient {

    /** iLink Bot API 基础地址 */
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /** API 基础地址 */
    private String baseUrl = DEFAULT_BASE_URL;

    /** Bot Token（登录后获得或手动配置）*/
    private String token;

    /** 密钥 */
    private String secret;

    /** 加密 AES Key */
    private String encodingAesKey;

    /** 连接超时（毫秒）*/
    private long connectTimeoutMillis = 10_000L;

    /** 读取超时（毫秒）*/
    private long readTimeoutMillis = 35_000L;

    /** 运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 消息监听器列表 */
    private final CopyOnWriteArrayList<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /** 错误监听器列表 */
    private final CopyOnWriteArrayList<Runnable> errorListeners = new CopyOnWriteArrayList<>();

    /** 二维码监听器 */
    private volatile QrcodeListener qrcodeListener;

    /** contextToken 缓存：userId → contextToken */
    private final Map<String, String> contextTokens = new ConcurrentHashMap<>();

    /** Bot ID（登录成功后赋值）*/
    private volatile String botId;

    /** HTTP 客户端 */
    private HttpClient httpClient = HttpClientFactory.getClient();

    /**
     * 创建 ILinkBotClient
     */
    public ILinkBotClient() {
    }

    /**
     * 设置二维码监听器（用于前端展示二维码）。
     *
     * @param listener 监听器
     * @return this
     */
    public ILinkBotClient qrcodeListener(QrcodeListener listener) {
        this.qrcodeListener = listener;
        return this;
    }

    /**
     * 扫码登录并启动消息轮询。
     *
     * @return botId，登录失败返回 null
     */
    public String loginWithQR() {
        try {
            // Step 1: 获取登录二维码 URL
            Map<String, Object> qrResponse = apiGet("/api/bot/qrcode");
            String qrUrl = getString(qrResponse, "url");
            String qrKey = getString(qrResponse, "key");
            if (qrUrl == null || qrUrl.isEmpty()) {
                log.error("[ILink] 获取二维码失败");
                return null;
            }
            if (qrcodeListener != null) {
                qrcodeListener.newQrcode(qrUrl, qrKey);
            }
            log.info("[ILink] 二维码已生成，等待扫描...");

            // Step 2: 轮询等待扫码确认
            int maxAttempts = 60;
            for (int i = 0; i < maxAttempts; i++) {
                Thread.sleep(3000);
                Map<String, Object> status = apiGet("/api/bot/qrcode/status?key=" + qrKey);
                String state = getString(status, "state");
                if ("scanned".equals(state)) {
                    if (qrcodeListener != null) { qrcodeListener.scanned(); }
                    log.info("[ILink] 已扫码，等待确认...");
                } else if ("confirmed".equals(state)) {
                    break;
                } else if ("expired".equals(state)) {
                    if (qrcodeListener != null) { qrcodeListener.expired(); }
                    log.warn("[ILink] 二维码已过期");
                    return null;
                }
            }

            // Step 3: 获取登录凭证
            Map<String, Object> loginResult = apiPost("/api/bot/login", Map.of("key", qrKey));
            this.token = getString(loginResult, "token");
            this.botId = getString(loginResult, "botId");

            String userId = getString(loginResult, "userId");
            if (qrcodeListener != null) { qrcodeListener.confirmed(token, botId, userId); }

            running.set(true);
            log.info("[ILink] 登录成功 botId={}", botId);
            return botId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("[ILink] 登录异常", e);
            if (qrcodeListener != null) { qrcodeListener.error(e.getMessage(), e); }
            return null;
        }
    }

    /**
     * 启动消息轮询（阻塞式，建议在独立线程调用）。
     */
    public void startPolling() {
        running.set(true);
        while (running.get()) {
            try {
                List<BotInboundMessage> messages = pollMessages();
                for (BotInboundMessage msg : messages) {
                    for (BotMessageListener listener : messageListeners) {
                        try {
                            listener.onMessage(msg);
                        } catch (Exception e) {
                            log.debug("[ILink] 消息处理异常", e);
                        }
                    }
                }
                if (messages.isEmpty()) {
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("[ILink] 轮询异常: {}", e.getMessage());
                sleepQuietly(5000);
            }
        }
    }

    /**
     * 停止运行。
     */
    public void shutdown() {
        running.set(false);
        log.info("[ILink] 已停止");
    }

    // ---- BotClient 接口实现 ----

    @Override
    public ILinkBotClient configure(String token, String secret, String encodingAesKey) {
        this.token = token;
        this.secret = secret;
        this.encodingAesKey = encodingAesKey;
        return this;
    }

    @Override
    public ILinkBotClient token(String t) { this.token = t; return this; }

    @Override
    public ILinkBotClient secret(String s) { this.secret = s; return this; }

    @Override
    public ILinkBotClient encodingAesKey(String k) { this.encodingAesKey = k; return this; }

    @Override
    public ILinkBotClient baseUrl(String url) { this.baseUrl = url; return this; }

    @Override
    public ILinkBotClient connectTimeoutMillis(long ms) { this.connectTimeoutMillis = ms; return this; }

    @Override
    public ILinkBotClient readTimeoutMillis(long ms) { this.readTimeoutMillis = ms; return this; }

    @Override
    public ILinkBotClient configSaveOrLoader(com.chua.common.support.config.loader.ConfigSaveOrLoader loader) { return this; }

    @Override
    public ILinkBotClient start() {
        String id = loginWithQR();
        if (id != null) {
            startPolling();
        }
        return this;
    }

    @Override
    public void stop() {
        shutdown();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public BotSendResult sendText(String toUser, String content) {
        return sendWithToken(toUser, buildTextBody(content));
    }

    @Override
    public CompletableFuture<BotSendResult> sendTextAsync(String toUser, String content) {
        return CompletableFuture.supplyAsync(() -> sendText(toUser, content));
    }

    @Override
    public BotSendResult sendImage(String toUser, String mediaPath) {
        return sendMedia(toUser, "image", mediaPath);
    }

    @Override
    public CompletableFuture<BotSendResult> sendImageAsync(String toUser, String mediaPath) {
        return CompletableFuture.supplyAsync(() -> sendImage(toUser, mediaPath));
    }

    @Override
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        return sendMedia(toUser, "voice", mediaPath);
    }

    @Override
    public BotSendResult sendVideo(String toUser, String mediaPath, String title, String desc) {
        return sendMedia(toUser, "video", mediaPath);
    }

    @Override
    public BotSendResult sendFile(String toUser, String mediaPath) {
        return sendMedia(toUser, "file", mediaPath);
    }

    @Override
    public BotSendResult send(BotOutboundMessage message) {
        return sendText(message.getToUser(), message.getContent());
    }

    @Override
    public CompletableFuture<BotSendResult> sendAsync(BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    @Override
    public List<BotGroupInfo> listGroups() { return List.of(); }

    @Override
    public BotSendResult sendToGroup(String groupId, String content) {
        return sendText(groupId, content);
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupAsync(String groupId, String content) {
        return CompletableFuture.supplyAsync(() -> sendToGroup(groupId, content));
    }

    @Override
    public BotSendResult sendToGroupMention(String groupId, String content, List<String> mentionedUserIds) {
        return sendToGroup(groupId, content);
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupMentionAsync(String groupId, String content, List<String> mentionedUserIds) {
        return CompletableFuture.supplyAsync(() -> sendToGroupMention(groupId, content, mentionedUserIds));
    }

    @Override
    public ILinkBotClient userStore(com.chua.common.support.ai.bot.BotUserStore userStore) { return this; }

    @Override
    public List<BotUserInfo> listUsers() { return List.of(); }

    @Override
    public ILinkBotClient addMessageListener(BotMessageListener listener) {
        messageListeners.add(listener);
        return this;
    }

    @Override
    public ILinkBotClient removeMessageListener(BotMessageListener listener) {
        messageListeners.remove(listener);
        return this;
    }

    @Override
    public ILinkBotClient addErrorListener(com.chua.common.support.ai.bot.BotErrorListener listener) { return this; }

    @Override
    public Map<String, Object> getConfig() { return Map.of("baseUrl", baseUrl); }

    // ==================== 私有方法 ====================

    /**
     * 发送带 contextToken 的文本消息到指定用户。
     *
     * @param toUser 目标用户
     * @param body   请求体 JSON
     * @return 发送结果
     */
    private BotSendResult sendWithToken(String toUser, JsonObject body) {
        try {
            body.fluentPut("toUserId", toUser);
            String ctx = contextTokens.get(toUser);
            if (ctx != null) { body.fluentPut("contextToken", ctx); }
            Map<String, Object> resp = apiPost("/api/bot/send/text", Json.fromJson(
                    body.toJSONString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            boolean ok = "0".equals(getString(resp, "errCode"));
            return ok ? BotSendResult.ok("0") : BotSendResult.fail(-1, getString(resp, "errMsg"));
        } catch (Exception e) {
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 构建文本消息体。
     *
     * @param content 文本内容
     * @return JSON 对象
     */
    private JsonObject buildTextBody(String content) {
        JsonObject text = new JsonObject();
        text.fluentPut("content", content);
        JsonObject body = new JsonObject();
        body.fluentPut("msgtype", "text");
        body.fluentPut("text", text);
        return body;
    }

    /**
     * 发送媒体消息。
     *
     * @param toUser    目标用户
     * @param mediaType 媒体类型
     * @param mediaPath 媒体路径
     * @return 发送结果
     */
    private BotSendResult sendMedia(String toUser, String mediaType, String mediaPath) {
        return BotSendResult.fail(-1, "媒体发送暂不支持");
    }

    /**
     * 长轮询获取新消息。
     *
     * @return 入站消息列表
     */
    private List<BotInboundMessage> pollMessages() {
        List<BotInboundMessage> result = new ArrayList<>();
        try {
            Map<String, Object> resp = apiGet("/api/bot/getupdates?timeout=25000");
            var items = (List<?>) resp.getOrDefault("messages", List.of());
            for (var item : items) {
                if (!(item instanceof Map)) { continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) item;
                String fromUser = getString(msg, "fromUserId");
                String ctxToken = getString(msg, "contextToken");
                if (ctxToken != null) { contextTokens.put(fromUser, ctxToken); }
                String textContent = extractNestedText(msg);
                result.add(BotInboundMessage.builder()
        .msgId(String.valueOf(System.nanoTime()))
        .type(BotInboundMessage.Type.TEXT)
        .content(textContent)
        .fromUser(fromUser)
        .toUser(botId)
        .createTime(System.currentTimeMillis())
        .build());
            }
        } catch (Exception e) {
            log.debug("[ILink] 轮询异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 从嵌套结构提取文本内容。
     *
     * @param msg 消息映射
     * @return 文本内容或空字符串
     */
    private String extractNestedText(Map<String, Object> msg) {
        var itemList = msg.get("item_list");
        if (!(itemList instanceof List<?> list)) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (var o : list) {
            if (!(o instanceof Map<?, ?> item)) { continue; }
            var textItem = item.get("text_item");
            if (textItem instanceof Map<?, ?> tm && tm.get("text") != null) {
                sb.append(tm.get("text").toString());
            }
        }
        return sb.toString();
    }

    /**
     * 发送 POST 请求并解析 JSON 响应。
     *
     * @param path API 路径
     * @param body 请求体对象
     * @return 响应映射，解析失败返回空 Map
     */
    private Map<String, Object> apiPost(String path, Object body) {
        ClientRequest request = ClientRequest.of(baseUrl + path, HttpMethod.POST)
                .header("Content-Type", "application/json");
        if (token != null && !token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        request.setBody(Json.toJson(body));
        HttpClient client = HttpClientFactory.getClient();
        ClientResponse response = client.execute(request);
        return safeParse(response.getBodyString());
    }

    /**
     * 发送 GET 请求并解析 JSON 响应。
     *
     * @param pathAndQuery 带查询参数的路径
     * @return 响应映射，解析失败返回空 Map
     */
    private Map<String, Object> apiGet(String pathAndQuery) {
        ClientRequest request = ClientRequest.of(baseUrl + pathAndQuery, HttpMethod.GET);
        if (token != null && !token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpClient client = HttpClientFactory.getClient();
        ClientResponse response = client.execute(request);
        return safeParse(response.getBodyString());
    }

    /**
     * 安全解析 JSON 为 Map，失败时返回空 Map。
     *
     * @param body JSON 字符串
     * @return 解析结果，异常时返回 {@link Map#of()}
     */
    private static Map<String, Object> safeParse(String body) {
        try {
            return Json.fromJson(body);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 安静休眠。
     *
     * @param millis 毫秒数
     */
    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
