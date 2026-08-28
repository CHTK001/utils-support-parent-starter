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
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink Bot 客户端。
 *
 * <p>基于 {@code https://ilinkai.weixin.qq.com} 的 Bot API，
 * 支持扫码登录、长轮询接收消息、文本/媒体发送。</p>
 *
 * <p>登录流程：{@code loginWithQR()} 获取二维码（bot_type=3）→ 用户微信扫码确认 →
 * 获得 botToken / botId。登录成功后通过长轮询接收消息，使用 context_token 回复。</p>
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
    private long readTimeoutMillis = 40_000L;

    /** 运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 消息监听器列表 */
    private final CopyOnWriteArrayList<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /** 错误监听器列表 */
    private final CopyOnWriteArrayList<Runnable> errorListeners = new CopyOnWriteArrayList<>();

    /** 二维码监听器 */
    private volatile QrcodeListener qrcodeListener;

    /** context_token 缓存：userId → contextToken */
    private final Map<String, String> contextTokens = new ConcurrentHashMap<>();

    /** getupdates 游标（会话内的消息拉取续接标记） */
    private volatile String getUpdatesBuf = "";

    /** Bot ID（登录成功后赋值）*/
    private volatile String botId;

    /** HTTP 客户端 */
    private final HttpClient httpClient = HttpClientFactory.getClient();

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
     * <p>基于 iLink 官方协议：
     * <ol>
     *   <li>{@code GET /ilink/bot/get_bot_qrcode?bot_type=3} 获取二维码；</li>
     *   <li>长轮询 {@code GET /ilink/bot/get_qrcode_status?qrcode=xxx} 等待用户扫码确认
     *   （单次最长约 35s，状态机 wait → scanned → confirmed / expired）；</li>
     *   <li>confirmed 响应直接携带 botToken / botId，无需额外登录接口。</li>
     * </ol></p>
     *
     * @return botId，登录失败返回 null
     */
    public String loginWithQR() {
        try {
            // Step 1: 获取登录二维码
            Map<String, Object> qrResponse = apiGet("/ilink/bot/get_bot_qrcode?bot_type=3");
            int ret = intVal(qrResponse, "ret", -1);
            String qrCode = getString(qrResponse, "qrcode");
            String qrImgUrl = getString(qrResponse, "qrcode_img_content");
            if (ret != 0 || qrCode == null || qrCode.isEmpty()) {
                log.error("[ILink] 获取二维码失败 ret={} err={}",
                        ret, getString(qrResponse, "err_msg"));
                if (qrcodeListener != null) {
                    qrcodeListener.error("获取二维码失败", null);
                }
                return null;
            }
            if (qrcodeListener != null) {
                qrcodeListener.newQrcode(qrImgUrl, qrCode);
            }
            log.info("[ILink] 二维码已生成，等待扫描...");

            // Step 2: 长轮询扫码确认（每次最多阻塞 ~35s，最多约 3 分钟）
            int maxAttempts = 6;
            for (int i = 0; i < maxAttempts; i++) {
                Map<String, Object> status = apiGet(
                        "/ilink/bot/get_qrcode_status?qrcode=" + qrCode);
                int sret = intVal(status, "ret", -1);
                if (sret != 0) {
                    log.warn("[ILink] 轮询状态异常 ret={}", sret);
                    sleepQuietly(2000);
                    continue;
                }
                String state = getString(status, "status");
                if ("scanned".equals(state)) {
                    if (qrcodeListener != null) { qrcodeListener.scanned(); }
                    log.info("[ILink] 已扫码，等待确认...");
                } else if ("confirmed".equals(state)) {
                    // confirmed 响应携带凭证（实测字段：ilink_bot_id / ilink_user_id / bot_token）
                    log.info("[ILink] confirmed 响应: {}", status);
                    this.token = firstNonBlank(status, "bot_token", "botToken");
                    this.botId = firstNonBlank(status, "ilink_bot_id", "bot_id", "botId");
                    String userId = firstNonBlank(status, "ilink_user_id", "user_id", "userId");
                    if (qrcodeListener != null) {
                        qrcodeListener.confirmed(token, botId, userId);
                    }
                    running.set(true);
                    log.info("[ILink] 登录成功 botId={} token={}", botId, token != null);
                    return botId;
                } else if ("expired".equals(state)) {
                    if (qrcodeListener != null) { qrcodeListener.expired(); }
                    log.warn("[ILink] 二维码已过期");
                    return null;
                }
                // "wait"：继续长轮询
            }
            log.warn("[ILink] 扫码超时未确认");
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
        // 已有 token（登录后配置/手动导入）则直接用 token 启动消息轮询，不再扫码
        if (token == null || token.isEmpty()) {
            String id = loginWithQR();
            if (id == null) {
                return this;
            }
        }
        startPolling();
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
     * 发送带 context_token 的文本消息到指定用户。
     *
     * <p>iLink 协议要求 POST /ilink/bot/sendmessage，
     * 请求头需携带 bot_token 与 X-WECHAT-UIN。</p>
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
            Map<String, Object> resp = apiPost("/ilink/bot/sendmessage", Json.fromJson(
                    body.toJSONString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            int ret = intVal(resp, "ret", -1);
            boolean ok = ret == 0;
            return ok ? BotSendResult.ok("0") : BotSendResult.fail(-1, getString(resp, "errmsg"));
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
     * <p>iLink 协议要求 POST /ilink/bot/getupdates，
     * 请求体携带 base_info 与 get_updates_buf 游标。</p>
     *
     * @return 入站消息列表
     */
    private List<BotInboundMessage> pollMessages() {
        List<BotInboundMessage> result = new ArrayList<>();
        try {
            JsonObject body = new JsonObject();
            body.fluentPut("base_info", new JsonObject().fluentPut("channel_version", "2.0.0"));
            body.fluentPut("get_updates_buf", getUpdatesBuf);
            Map<String, Object> resp = apiPost("/ilink/bot/getupdates", Json.fromJson(
                    body.toJSONString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            // 兼容两种字段：成功返回 ret=0，错误返回 errcode（如 -14 会话过期）
            int ret = intVal(resp, "ret", 0);
            int errcode = intVal(resp, "errcode", 0);
            log.info("[ILink] getupdates 响应: {}", resp);
            if (ret == -14 || errcode == -14) {
                // 会话过期
                log.warn("[ILink] 会话过期 (-14)，停止轮询");
                running.set(false);
                return result;
            }
            if (ret != 0 && errcode != 0) {
                log.warn("[ILink] getupdates 返回错误 ret={} errcode={}", ret, errcode);
                return result;
            }
            String nextBuf = getString(resp, "get_updates_buf");
            if (nextBuf != null) { getUpdatesBuf = nextBuf; }
            // 消息列表字段为 msgs（兼容 messages）
            Object itemsObj = resp.get("msgs");
            if (itemsObj == null) { itemsObj = resp.get("messages"); }
            if (!(itemsObj instanceof List<?> list)) {
                log.debug("[ILink] getupdates 响应无 msgs: {}", resp);
                return result;
            }
            log.info("[ILink] getupdates 返回 {} 条消息", list.size());
            for (var item : list) {
                if (!(item instanceof Map)) { continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) item;
                // 字段为 snake_case：from_user_id / context_token
                String fromUser = firstNonBlank(msg, "from_user_id", "fromUserId");
                String ctxToken = firstNonBlank(msg, "context_token", "contextToken");
                if (ctxToken != null) { contextTokens.put(fromUser, ctxToken); }
                String textContent = extractNestedText(msg);
                log.info("[ILink] 收到消息 from={} content={}", fromUser, textContent);
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
            log.warn("[ILink] 轮询异常: {}", e.getMessage(), e);
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
     * <p>携带 iLink 协议要求的鉴权头（ilink_bot_token + X-WECHAT-UIN）。</p>
     *
     * @param path API 路径
     * @param body 请求体对象
     * @return 响应映射，解析失败返回空 Map
     */
    private Map<String, Object> apiPost(String path, Object body) {
        ClientRequest request = ClientRequest.of(baseUrl + path, HttpMethod.POST)
                .header("Content-Type", "application/json");
        applyAuthHeaders(request);
        request.setBody(Json.toJson(body));
        ClientResponse response = httpClient.execute(request);
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
        request.setConnectTimeout(connectTimeoutMillis);
        request.setReadTimeout(readTimeoutMillis);
        if (token != null && !token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        ClientResponse response = httpClient.execute(request);
        return safeParse(response.getBodyString());
    }

    /**
     * 为业务 POST 请求附加 iLink 协议鉴权头。
     *
     * <p>仅当已登录（token 非空）时附加 bot_token 鉴权；X-WECHAT-UIN 每次随机生成。</p>
     */
    private void applyAuthHeaders(ClientRequest request) {
        request.setConnectTimeout(connectTimeoutMillis);
        request.setReadTimeout(readTimeoutMillis);
        if (token != null && !token.isEmpty()) {
            request.header("AuthorizationType", "ilink_bot_token");
            request.header("Authorization", "Bearer " + token);
        }
        request.header("X-WECHAT-UIN", wechatUin());
    }

    /**
     * 生成 X-WECHAT-UIN：随机 uint32 → base64。
     */
    private static String wechatUin() {
        // 协议：4 random bytes → uint32 十进制字符串 → base64
        int v = new Random().nextInt();
        long unsigned = Integer.toUnsignedLong(v);
        String decimal = Long.toString(unsigned);
        return Base64.getEncoder().encodeToString(decimal.getBytes(StandardCharsets.UTF_8));
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
     * 从映射中提取字符串值。
     */
    private static String getString(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 从映射中提取 int 值，缺省返回 defaultVal。
     */
    private static int intVal(Map<String, Object> map, String key, int defaultVal) {
        if (map == null || !map.containsKey(key)) {
            return defaultVal;
        }
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * 从映射中提取第一个非空字符串值（兼容 snake_case 与驼峰字段名）。
     */
    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
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
