package com.chua.whatsapp.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotErrorListener;
import com.chua.common.support.ai.bot.BotGroupInfo;
import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotMessageListener;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.ai.bot.BotUserInfo;
import com.chua.common.support.ai.bot.BotUserStore;
import com.chua.common.support.ai.bot.InMemoryBotUserStore;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.lang.json.Json;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WhatsApp Cloud API（Meta Graph）机器人客户端。
 *
 * <p>发送走 {@code https://graph.facebook.com/v20.0/{phone_number_id}/messages}；
 * 接收走 Meta Webhook：由 ai-starter 暴露回调端点，把原始请求体与请求头交给
 * {@link #handleCallback(String, Map)}。</p>
 *
 * <p>凭证语义：{@code token}=phone_number_id，{@code secret}=access_token，
 * {@code encodingAesKey}=Meta App Secret；Webhook 校验令牌单独通过
 * {@link #verifyToken(String)} 设置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WhatsAppBotClient implements BotClient {

    /**
     * 默认 Graph API 版本地址
     */
    private static final String DEFAULT_BASE_URL = "https://graph.facebook.com/v20.0";

    /**
     * 请求头：Webhook 签名
     */
    private static final String SIGNATURE_HEADER = "x-hub-signature-256";

    private String phoneNumberId;
    private String accessToken;
    private String appSecret;
    private String verifyToken;
    private String baseUrl = DEFAULT_BASE_URL;
    private long connectTimeoutMillis = 10_000L;
    private long readTimeoutMillis = 30_000L;

    /**
     * 出站 HTTP 代理，格式 {@code host:port}。
     *
     * <p>Meta Graph API 在部分网络（如中国大陆）被目的地封锁，需经可达的中转/代理出站；
     * 可用 {@link #httpProxy(String)} 显式设置，或由系统属性
     * {@code whatsapp.http.proxy} / 环境变量 {@code WHATSAPP_HTTP_PROXY} 提供。</p>
     */
    private String httpProxy;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<BotErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private BotUserStore userStore = new InMemoryBotUserStore();
    private volatile HttpClient httpClient;

    @Override
    public BotClient configure(String token, String secret, String encodingAesKey) {
        if (token != null && !token.isBlank()) {
            this.phoneNumberId = token;
        }
        if (secret != null && !secret.isBlank()) {
            this.accessToken = secret;
        }
        if (encodingAesKey != null && !encodingAesKey.isBlank()) {
            this.appSecret = encodingAesKey;
        }
        return this;
    }

    /**
     * 按 WhatsApp Cloud API 语义配置完整凭证。
     *
     * @param phoneNumberId 商务电话号码 ID
     * @param accessToken   Meta access_token
     * @param appSecret     Meta App Secret（签名校验，可空）
     * @param verifyToken   Webhook 校验令牌（可空）
     * @return this
     */
    public WhatsAppBotClient configure(String phoneNumberId, String accessToken,
                                      String appSecret, String verifyToken) {
        configure(phoneNumberId, accessToken, appSecret);
        if (verifyToken != null && !verifyToken.isBlank()) {
            this.verifyToken = verifyToken;
        }
        return this;
    }

    @Override
    public BotClient token(String token) {
        this.phoneNumberId = token;
        return this;
    }

    @Override
    public BotClient secret(String secret) {
        this.accessToken = secret;
        return this;
    }

    @Override
    public BotClient encodingAesKey(String encodingAesKey) {
        this.appSecret = encodingAesKey;
        return this;
    }

    /**
     * 设置 Webhook 校验令牌。
     *
     * @param verifyToken Meta Webhook 验证令牌
     * @return this
     */
    public WhatsAppBotClient verifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
        return this;
    }

    /**
     * 设置出站 HTTP 代理（Meta Graph API 调用走该代理）。
     *
     * @param httpProxy 代理地址，格式 {@code host:port}；空白表示不走代理
     * @return this
     */
    public WhatsAppBotClient httpProxy(String httpProxy) {
        this.httpProxy = httpProxy;
        if (this.httpProxy != null && !this.httpProxy.isBlank()) {
            synchronized (this) {
                this.httpClient = null;
            }
        }
        return this;
    }

    @Override
    public BotClient baseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            this.baseUrl = baseUrl;
        }
        return this;
    }

    @Override
    public BotClient connectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    @Override
    public BotClient readTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
        return this;
    }

    @Override
    public BotClient configSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        return this;
    }

    /**
     * 校验 phone_number_id 与 access_token 是否可用（GET 商务号码信息）。
     *
     * @throws IllegalArgumentException 凭证缺失或校验失败
     */
    public void validate() {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            throw new IllegalArgumentException("WhatsApp phone_number_id 必填");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("WhatsApp access_token 必填");
        }
        try {
            String url = baseUrl + "/" + phoneNumberId
                    + "?fields=display_phone_number,verified_name";
            HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(readTimeoutMillis))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("WhatsApp 凭据校验失败：HTTP "
                        + response.statusCode() + " " + safeBody(response));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("WhatsApp 凭据校验请求失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BotClient start() {
        if (phoneNumberId == null || phoneNumberId.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("WhatsApp phone_number_id 与 access_token 必填");
        }
        running.set(true);
        log.info("[WhatsApp] 客户端已就绪，等待 Webhook 入站 phoneNumberId={}", phoneNumberId);
        return this;
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("[WhatsApp] 客户端已停止 phoneNumberId={}", phoneNumberId);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public BotSendResult sendText(String toUser, String content) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("preview_url", false);
        text.put("body", content);
        Map<String, Object> payload = basePayload(toUser, "text");
        payload.put("text", text);
        return sendPayload(payload);
    }

    @Override
    public BotSendResult sendImage(String toUser, String mediaPath) {
        if (mediaPath == null || !mediaPath.startsWith("http")) {
            return BotSendResult.fail(-1, "WhatsApp 图片发送暂只支持公网 URL（link）");
        }
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("link", mediaPath);
        Map<String, Object> payload = basePayload(toUser, "image");
        payload.put("image", image);
        return sendPayload(payload);
    }

    @Override
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        return BotSendResult.fail(-1, "WhatsApp 语音发送暂未实现");
    }

    @Override
    public BotSendResult sendVideo(String toUser, String mediaPath, String title, String desc) {
        return BotSendResult.fail(-1, "WhatsApp 视频发送暂未实现");
    }

    @Override
    public BotSendResult sendFile(String toUser, String mediaPath) {
        return BotSendResult.fail(-1, "WhatsApp 文件发送暂未实现");
    }

    @Override
    public BotSendResult send(BotOutboundMessage message) {
        if (message == null || message.getType() == null) {
            return BotSendResult.fail(-1, "Message type is required");
        }
        if (message.isToGroup()) {
            return BotSendResult.fail(-1, "WhatsApp Cloud API 不支持群消息");
        }
        return switch (message.getType()) {
            case TEXT -> sendText(message.getToUser(), message.getContent());
            case IMAGE -> sendImage(message.getToUser(), message.getMediaPath());
            default -> BotSendResult.fail(-1,
                    "Unsupported message type: " + message.getType());
        };
    }

    @Override
    public CompletableFuture<BotSendResult> sendTextAsync(String toUser, String content) {
        return CompletableFuture.supplyAsync(() -> sendText(toUser, content));
    }

    @Override
    public CompletableFuture<BotSendResult> sendImageAsync(String toUser, String mediaPath) {
        return CompletableFuture.supplyAsync(() -> sendImage(toUser, mediaPath));
    }

    @Override
    public CompletableFuture<BotSendResult> sendAsync(BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    @Override
    public List<BotGroupInfo> listGroups() {
        return Collections.emptyList();
    }

    @Override
    public BotSendResult sendToGroup(String groupId, String content) {
        return BotSendResult.fail(-1, "WhatsApp Cloud API 不支持群消息");
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupAsync(String groupId, String content) {
        return CompletableFuture.completedFuture(sendToGroup(groupId, content));
    }

    @Override
    public BotSendResult sendToGroupMention(String groupId, String content,
                                            List<String> mentionedUserIds) {
        return sendToGroup(groupId, content);
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupMentionAsync(
            String groupId, String content, List<String> mentionedUserIds) {
        return CompletableFuture.completedFuture(
                sendToGroupMention(groupId, content, mentionedUserIds));
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
    public BotClient addMessageListener(BotMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
        return this;
    }

    @Override
    public BotClient removeMessageListener(BotMessageListener listener) {
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

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("phoneNumberId", phoneNumberId == null ? "" : phoneNumberId);
        config.put("baseUrl", baseUrl);
        config.put("running", running.get());
        return config;
    }

    /**
     * 校验 Webhook GET 握手。
     *
     * @param mode      hub.mode，须为 subscribe
     * @param token     hub.verify_token
     * @param challenge hub.challenge
     * @return 校验通过返回 challenge，否则返回 null
     */
    public String verifyWebhook(String mode, String token, String challenge) {
        if (!"subscribe".equalsIgnoreCase(mode)) {
            return null;
        }
        if (verifyToken == null || verifyToken.isBlank()
                || !verifyToken.equals(token)) {
            return null;
        }
        return challenge;
    }

    /**
     * 处理 Meta Webhook 回调：验签、解析消息、分发给监听器。
     *
     * @param body    原始请求体
     * @param headers 请求头（大小写不敏感）
     * @return HTTP 状态与响应体
     */
    public WhatsAppCallbackResult handleCallback(String body, Map<String, String> headers) {
        if (body == null || body.isBlank()) {
            return new WhatsAppCallbackResult(400, "{\"msg\":\"empty body\"}");
        }
        if (!verifySignature(body, headers)) {
            return new WhatsAppCallbackResult(401, "{\"msg\":\"invalid signature\"}");
        }
        try {
            Map<String, Object> payload = Json.fromJson(body);
            dispatchPayload(payload);
            return new WhatsAppCallbackResult(200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            notifyError(e);
            log.error("[WhatsApp] 解析 Webhook 失败: {}", e.getMessage(), e);
            return new WhatsAppCallbackResult(500, "{\"msg\":\"internal error\"}");
        }
    }

    private void dispatchPayload(Map<String, Object> payload) {
        Object entryObj = payload.get("entry");
        if (!(entryObj instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                continue;
            }
            Object changesObj = entryMap.get("changes");
            if (!(changesObj instanceof List<?> changes)) {
                continue;
            }
            for (Object change : changes) {
                if (!(change instanceof Map<?, ?> changeMap)) {
                    continue;
                }
                Object valueObj = changeMap.get("value");
                if (!(valueObj instanceof Map<?, ?> value)) {
                    continue;
                }
                dispatchMessages(asMap(value));
            }
        }
    }

    private void dispatchMessages(Map<String, Object> value) {
        Object messagesObj = value.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }
        String name = resolveContactName(value);
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            Map<String, Object> msg = asMap(message);
            String from = str(msg.get("from"));
            String msgId = str(msg.get("id"));
            long createTime = parseMillis(msg.get("timestamp"));
            String type = str(msg.get("type"));
            BotInboundMessage.BotInboundMessageBuilder builder = BotInboundMessage.builder()
                    .msgId(msgId)
                    .fromUser(from)
                    .fromUserName(name)
                    .fromGroup(false)
                    .eventType("whatsapp.message")
                    .createTime(createTime);
            if ("text".equals(type)) {
                Object textObj = msg.get("text");
                String content = textObj instanceof Map<?, ?> textMap
                        ? str(asMap(textMap).get("body")) : "";
                builder.type(BotInboundMessage.Type.TEXT).content(content);
            } else {
                builder.type(mapType(type)).content("[" + type + "]");
            }
            builder.rawField("message", msg);
            BotInboundMessage inbound = builder.build();
            for (BotMessageListener listener : messageListeners) {
                try {
                    listener.onMessage(inbound);
                } catch (Exception e) {
                    notifyError(e);
                    log.warn("[WhatsApp] 消息监听器异常: {}", e.getMessage());
                }
            }
        }
    }

    private String resolveContactName(Map<String, Object> value) {
        Object contactsObj = value.get("contacts");
        if (contactsObj instanceof List<?> contacts && !contacts.isEmpty()
                && contacts.get(0) instanceof Map<?, ?> contact) {
            Object profileObj = contact.get("profile");
            if (profileObj instanceof Map<?, ?> profile) {
                return str(profile.get("name"));
            }
        }
        return null;
    }

    private BotInboundMessage.Type mapType(String type) {
        if (type == null) {
            return BotInboundMessage.Type.UNKNOWN;
        }
        return switch (type) {
            case "image" -> BotInboundMessage.Type.IMAGE;
            case "audio", "voice" -> BotInboundMessage.Type.VOICE;
            case "video" -> BotInboundMessage.Type.VIDEO;
            case "document" -> BotInboundMessage.Type.FILE;
            case "location" -> BotInboundMessage.Type.LOCATION;
            default -> BotInboundMessage.Type.UNKNOWN;
        };
    }

    private BotSendResult sendPayload(Map<String, Object> payload) {
        if (phoneNumberId == null || accessToken == null) {
            return BotSendResult.fail(-1, "WhatsApp client not configured");
        }
        try {
            String url = baseUrl + "/" + phoneNumberId + "/messages";
            HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(readTimeoutMillis))
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    Json.toJson(payload), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = safeBody(response);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                BotSendResult result = BotSendResult.ok(extractMessageId(body));
                result.setRawResponse(body);
                return result;
            }
            notifyError(new IllegalStateException("WhatsApp send failed: " + body));
            return BotSendResult.fail(response.statusCode(), body);
        } catch (Exception e) {
            notifyError(e);
            log.error("[WhatsApp] 发送失败: {}", e.getMessage(), e);
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    private Map<String, Object> basePayload(String toUser, String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", toUser);
        payload.put("type", type);
        return payload;
    }

    /**
     * 校验 Meta 回调签名 {@code X-Hub-Signature-256}。
     * 未配置 App Secret 时跳过（仅用于联调）。
     */
    private boolean verifySignature(String body, Map<String, String> headers) {
        if (appSecret == null || appSecret.isBlank()) {
            return true;
        }
        String signature = null;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null
                        && SIGNATURE_HEADER.equals(entry.getKey().toLowerCase(Locale.ROOT))) {
                    signature = entry.getValue();
                    break;
                }
            }
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of()
                    .formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[WhatsApp] 签名校验失败: {}", e.getMessage());
            return false;
        }
    }

    private HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (this) {
                if (httpClient == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(connectTimeoutMillis));
                    ProxySelector proxySelector = resolveProxySelector();
                    if (proxySelector != null) {
                        builder.proxy(proxySelector);
                    }
                    httpClient = builder.build();
                }
                current = httpClient;
            }
        }
        return current;
    }

    /**
     * 解析出站代理：显式设置优先，其次系统属性 {@code whatsapp.http.proxy}，
     * 再次环境变量 {@code WHATSAPP_HTTP_PROXY} / {@code HTTPS_PROXY}。
     *
     * @return 代理选择器；未配置返回 null（直连）
     */
    private ProxySelector resolveProxySelector() {
        String proxy = httpProxy;
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getProperty("whatsapp.http.proxy");
        }
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("WHATSAPP_HTTP_PROXY");
        }
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("HTTPS_PROXY");
        }
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        String value = proxy.trim();
        int idx = value.lastIndexOf(':');
        if (idx <= 0 || idx == value.length() - 1) {
            log.warn("[WhatsApp] 代理格式应为 host:port，已忽略: {}", value);
            return null;
        }
        String host = value.substring(0, idx);
        int port;
        try {
            port = Integer.parseInt(value.substring(idx + 1));
        } catch (NumberFormatException e) {
            log.warn("[WhatsApp] 代理端口非法，已忽略: {}", value);
            return null;
        }
        log.info("[WhatsApp] 出站将经代理 {}:{}", host, port);
        return ProxySelector.of(new InetSocketAddress(host, port));
    }

    private void notifyError(Throwable e) {
        for (BotErrorListener listener : errorListeners) {
            try {
                listener.onError(e);
            } catch (Exception ignored) {
                // 忽略监听器自身异常
            }
        }
    }

    private static String extractMessageId(String body) {
        try {
            Map<String, Object> root = Json.fromJson(body);
            Object messagesObj = root.get("messages");
            if (messagesObj instanceof List<?> messages && !messages.isEmpty()
                    && messages.get(0) instanceof Map<?, ?> msg) {
                return str(msg.get("id"));
            }
        } catch (Exception ignored) {
            // 响应体非预期时不影响发送成功判定
        }
        return null;
    }

    private static String safeBody(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long parseMillis(Object timestamp) {
        if (timestamp == null) {
            return System.currentTimeMillis();
        }
        try {
            String text = String.valueOf(timestamp);
            if (text.length() <= 10) {
                return Long.parseLong(text) * 1000L;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * Webhook 回调结果。
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     */
    public record WhatsAppCallbackResult(int statusCode, String body) {
    }
}