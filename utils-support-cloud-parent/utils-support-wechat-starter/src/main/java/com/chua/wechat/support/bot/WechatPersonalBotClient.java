package com.chua.wechat.support.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 个人微信机器人客户端，实现 {@link BotClient} 接口。
 *
 * <p>个人微信没有官方 API，本类不直接对接微信服务器，而是对接<b>本机运行的 Hook
 * 服务</b>（如 wxhelper 注入 PC 微信后的 HTTP 服务，默认 {@code 127.0.0.1:19088}）：
 * 出站走 Hook 服务的发送接口，入站由 Hook 服务回调本机的 Spring 控制器，再交给
 * {@link #deliver(String)} 解析并分发给监听器。</p>
 *
 * <h3>接入方式</h3>
 * <pre>{@code
 * WechatPersonalBotClient client = (WechatPersonalBotClient) BotClient.auto("wechat-personal");
 * client.callbackUrl("http://127.0.0.1:8080/wechat/callback");
 * client.start();
 * client.addMessageListener(msg -> reply(msg));   // 回调控制器内调用 client.deliver(body)
 * }</pre>
 *
 * <h3>契约可移植性</h3>
 * <p>端点路径与请求字段名取自 wxhelper；换 Hook 服务实现时只需改
 * {@code xxxPath} 常量与入站键名列表 {@code FROM_USER_KEYS} 等，其余不动。
 * 本类不承诺任何官方兼容性。</p>
 *
 * <p><b>风险：</b>注入个人微信进程违反《微信个人账号使用规范》，可能被封号。只应在
 * 小号上使用，并在上层监听器里自行加入白名单与频控。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WechatPersonalBotClient implements BotClient {

    /** Hook 服务默认监听地址 */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:19088";

    /** 发送文本端点 */
    public static final String SEND_TEXT_PATH = "/api/sendTextMsg";

    /** 发送图片端点 */
    public static final String SEND_IMAGE_PATH = "/api/sendImageMsg";

    /** 发送文件端点 */
    public static final String SEND_FILE_PATH = "/api/sendFileMsg";

    /** 注册消息回调地址端点 */
    public static final String CALLBACK_REGISTER_PATH = "/api/http/plus";

    /** 查询当前登录账号端点 */
    public static final String USER_INFO_PATH = "/api/userInfo";

    /** 群聊 wxid 后缀 */
    public static final String ROOM_SUFFIX = "@chatroom";

    /** 默认连接超时（毫秒）*/
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L;

    /** 默认读取超时（毫秒）*/
    public static final long DEFAULT_READ_TIMEOUT_MILLIS = 15_000L;

    /** 微信消息类型：文本 */
    private static final int MSG_TYPE_TEXT = 1;

    /** 微信消息类型：图片 */
    private static final int MSG_TYPE_IMAGE = 3;

    /** 微信消息类型：语音 */
    private static final int MSG_TYPE_VOICE = 34;

    /** 微信消息类型：视频 */
    private static final int MSG_TYPE_VIDEO = 43;

    /** 微信消息类型：文件/表情等复合消息 */
    private static final int MSG_TYPE_FILE = 49;

    /** 消息去重窗口大小，Hook 服务重推时避免重复回调 */
    private static final int SEEN_CAPACITY = 512;

    /** 鉴权头值前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 回调注册接口的启用标记值 */
    private static final int CALLBACK_ENABLED = 1;

    /** 秒级与毫秒级时间戳的分界值，小于此值按秒处理 */
    private static final long SECONDS_TIMESTAMP_THRESHOLD = 10_000_000_000L;

    /** 每秒毫秒数 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * 入站消息中"会话对端"的候选键名，不同 Hook 服务命名不一致。
     */
    private static final String[] FROM_USER_KEYS = {
            "talker", "senderWxid", "sender_wxid", "wxid", "fromUser", "strTalker"};

    /**
     * 入站消息中"群 wxid"的候选键名。
     */
    private static final String[] ROOM_KEYS = {
            "roomWxid", "chatRoomId", "room_wxid", "chatroom"};

    /**
     * 入站消息中"群内真实发言人"的候选键名。
     */
    private static final String[] ROOM_SENDER_KEYS = {
            "sender", "roomSender", "msgSender", "actualSender"};

    /**
     * 入站消息中"接收方"的候选键名，用于反推群会话。
     */
    private static final String[] TO_USER_KEYS = {
            "toUserWxid", "toUser", "receiver", "to_wxid"};

    /** API 基础地址 */
    private String baseUrl = DEFAULT_BASE_URL;

    /** Hook 服务鉴权令牌，非空时以 Bearer 头下发 */
    private String token;

    /** 密钥，未使用，仅为对齐 {@link BotClient} 接口 */
    private String secret;

    /** 加密密钥，未使用，仅为对齐 {@link BotClient} 接口 */
    private String encodingAesKey;

    /** 连接超时（毫秒）*/
    private long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;

    /** 读取超时（毫秒）*/
    private long readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;

    /** 注册给 Hook 服务的入站回调地址，为空表示由外部自行调用 deliver */
    private volatile String callbackUrl;

    /** 当前登录账号 wxid，用于过滤自己发出的消息 */
    private volatile String selfWxid;

    /** 运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 消息监听器 */
    private final List<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /** 错误监听器 */
    private final List<BotErrorListener> errorListeners = new CopyOnWriteArrayList<>();

    /** 用户存储 */
    private BotUserStore userStore = new InMemoryBotUserStore();

    /** 已处理消息 id 的 LRU 索引 */
    private final Map<String, Boolean> seenMsgIds = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CAPACITY;
                }
            });

    /** HTTP 客户端 */
    private final HttpClient httpClient = HttpClientFactory.getClient();

    /**
     * 设置入站回调地址，{@code start()} 时注册给 Hook 服务。
     *
     * @param callbackUrl 本机可被 Hook 服务访问到的地址
     * @return this
     */
    public WechatPersonalBotClient callbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        return this;
    }

    /**
     * 手动指定当前登录账号 wxid；不指定时由 {@code start()} 向 Hook 服务查询。
     *
     * @param selfWxid 自身 wxid
     * @return this
     */
    public WechatPersonalBotClient selfWxid(String selfWxid) {
        this.selfWxid = selfWxid;
        return this;
    }

    /**
     * 获取当前登录账号 wxid。
     *
     * @return 当前登录账号的 wxid，未解析成功时为 null
     */
    public String getSelfWxid() {
        return selfWxid;
    }

    @Override
    public BotClient configure(String token, String secret, String encodingAesKey) {
        if (StringUtils.isNotBlank(token)) {
            this.token = token;
        }
        this.secret = secret;
        this.encodingAesKey = encodingAesKey;
        return this;
    }

    @Override
    public BotClient token(String token) {
        this.token = token;
        return this;
    }

    @Override
    public BotClient secret(String secret) {
        this.secret = secret;
        return this;
    }

    @Override
    public BotClient encodingAesKey(String encodingAesKey) {
        this.encodingAesKey = encodingAesKey;
        return this;
    }

    @Override
    public BotClient baseUrl(String baseUrl) {
        if (StringUtils.isNotBlank(baseUrl)) {
            this.baseUrl = stripTrailingSlash(baseUrl);
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
     * 校验 Hook 服务可达、解析自身 wxid，并向 Hook 服务注册回调地址。
     *
     * <p>不启动线程：入站消息由外部回调 {@link #deliver(String)} 注入。</p>
     */
    @Override
    public BotClient start() {
        resolveSelfWxid();
        registerCallbackUrl();
        running.set(true);
        log.info("[WechatPersonal] 启动完成 url={} selfWxid={}", baseUrl, selfWxid);
        return this;
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("[WechatPersonal] 已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public BotSendResult sendText(String toUser, String content) {
        if (StringUtils.isBlank(toUser)) {
            return BotSendResult.fail(-1, "接收人 wxid 不能为空");
        }
        JsonObject body = new JsonObject();
        body.fluentPut("wxid", toUser);
        body.fluentPut("msg", content == null ? "" : content);
        return post(SEND_TEXT_PATH, body);
    }

    @Override
    public BotSendResult sendImage(String toUser, String mediaPath) {
        return sendMedia(toUser, SEND_IMAGE_PATH, mediaPath);
    }

    @Override
    public BotSendResult sendFile(String toUser, String mediaPath) {
        return sendMedia(toUser, SEND_FILE_PATH, mediaPath);
    }

    @Override
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        return BotSendResult.fail(-1, "Hook 服务暂不支持语音消息");
    }

    @Override
    public BotSendResult sendVideo(String toUser, String mediaPath, String title, String desc) {
        return BotSendResult.fail(-1, "Hook 服务暂不支持视频消息");
    }

    @Override
    public BotSendResult send(BotOutboundMessage message) {
        BotInboundMessage.Type type = message.getType();
        if (type == null || type == BotInboundMessage.Type.TEXT) {
            return sendText(message.getToUser(), message.getContent());
        }
        if (type == BotInboundMessage.Type.IMAGE) {
            return sendImage(message.getToUser(), message.getMediaPath());
        }
        if (type == BotInboundMessage.Type.FILE) {
            return sendFile(message.getToUser(), message.getMediaPath());
        }
        return BotSendResult.fail(-1, "不支持的消息类型: " + type);
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
        // Hook 服务无稳定的会话列表接口，群与联系人由入站消息逐步沉淀到 userStore
        return Collections.emptyList();
    }

    @Override
    public BotSendResult sendToGroup(String groupId, String content) {
        return sendText(groupId, content);
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupAsync(String groupId, String content) {
        return CompletableFuture.supplyAsync(() -> sendToGroup(groupId, content));
    }

    @Override
    public BotSendResult sendToGroupMention(String groupId, String content,
            List<String> mentionedUserIds) {
        // 个人微信 hook 不支持真实 @，只能把昵称拼进文本
        return sendText(groupId, withMentions(content, mentionedUserIds));
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupMentionAsync(String groupId, String content,
            List<String> mentionedUserIds) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroupMention(groupId, content, mentionedUserIds));
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
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", baseUrl);
        config.put("callbackUrl", callbackUrl == null ? "" : callbackUrl);
        config.put("selfWxid", selfWxid == null ? "" : selfWxid);
        config.put("running", running.get());
        return config;
    }

    /**
     * 注入 Hook 服务推送的一条或多条原始消息，供回调控制器调用。
     *
     * @param rawBody 回调请求体，单个 JSON 对象或 JSON 数组
     * @return 本次实际分发给监听器的消息，重复、自发、解析失败的条目不在其中
     */
    public List<BotInboundMessage> deliver(String rawBody) {
        List<BotInboundMessage> accepted = new ArrayList<>();
        if (StringUtils.isBlank(rawBody)) {
            return accepted;
        }
        try {
            for (Map<String, Object> item : parsePayload(rawBody)) {
                BotInboundMessage inbound = toInbound(item);
                if (inbound == null) {
                    continue;
                }
                remember(inbound);
                accepted.add(inbound);
                dispatch(inbound);
            }
        } catch (Exception e) {
            notifyError(e);
            log.error("[WechatPersonal] 回调体解析失败: {}", e.getMessage());
        }
        return accepted;
    }

    // ==================== 私有方法 ====================

    /**
     * 解析媒体消息，图片与文件共用同一套请求字段。
     *
     * @param toUser 接收者 wxid，不允许为空白
     * @param path Hook 服务媒体接口路径
     * @param mediaPath 本地媒体文件路径，不允许为空白
     * @return 发送结果，参数缺失时返回失败结果
     */
    private BotSendResult sendMedia(String toUser, String path, String mediaPath) {
        if (StringUtils.isBlank(toUser) || StringUtils.isBlank(mediaPath)) {
            return BotSendResult.fail(-1, "接收人 wxid 与媒体文件路径不能为空");
        }
        JsonObject body = new JsonObject();
        body.fluentPut("wxid", toUser);
        body.fluentPut("path", mediaPath);
        return post(path, body);
    }

    /**
     * 向 Hook 服务下发一次 JSON POST，并把响应折算成 {@link BotSendResult}。
     *
     * @param path Hook 服务接口路径
     * @param body 请求 JSON 体，不允许为 null
     * @return 发送结果，调用异常时返回携带错误信息的失败结果
     */
    private BotSendResult post(String path, JsonObject body) {
        try {
            String responseBody = execute(path, HttpMethod.POST, body);
            return parseSendResult(responseBody);
        } catch (Exception e) {
            notifyError(e);
            log.error("[WechatPersonal] {} 调用失败: {}", path, e.getMessage());
            return BotSendResult.fail(-1, e.getMessage());
        }
    }

    /**
     * Hook 服务返回体不统一，按 status / code / errcode 任一为 0 判定成功。
     *
     * @param responseBody Hook 服务响应体原文，可为空白
     * @return 发送结果，非 JSON 响应按成功处理
     */
    private BotSendResult parseSendResult(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return BotSendResult.ok(null);
        }
        JsonObject json;
        try {
            json = Json.getJsonObject(responseBody);
        } catch (Exception e) {
            // 部分 Hook 服务直接返回纯文本 ok
            return BotSendResult.ok(null);
        }
        if (json == null) {
            return BotSendResult.ok(null);
        }
        Integer status = firstInt(json, "status", "code", "errcode", "ret");
        if (status != null && status != 0) {
            String message = firstString(json, "message", "msg", "errmsg", "err_msg");
            return BotSendResult.fail(status, message == null ? responseBody : message);
        }
        String msgId = firstString(json, "msgId", "msgid", "msg_id", "serverId");
        return BotSendResult.ok(msgId);
    }

    /**
     * 执行一次 Hook 服务 HTTP 请求，自动附带 JSON 头与 Bearer 令牌。
     *
     * @param path Hook 服务接口路径
     * @param method HTTP 方法，不允许为 null
     * @param body 请求 JSON 体，为 null 时不携带请求体
     * @return 响应体字符串
     */
    private String execute(String path, HttpMethod method, JsonObject body) {
        ClientRequest request = ClientRequest.of(baseUrl + path, method)
                .header("Content-Type", "application/json");
        if (StringUtils.isNotBlank(token)) {
            request.header("Authorization", BEARER_PREFIX + token);
        }
        request.setConnectTimeout(connectTimeoutMillis);
        request.setReadTimeout(readTimeoutMillis);
        if (body != null) {
            request.setBody(body.toJSONString());
        }
        ClientResponse response = httpClient.execute(request);
        return response.getBodyString();
    }

    /**
     * 查询当前登录 wxid，失败只降级为不过滤自发消息。
     */
    private void resolveSelfWxid() {
        if (StringUtils.isNotBlank(selfWxid)) {
            return;
        }
        try {
            String responseBody = execute(USER_INFO_PATH, HttpMethod.POST, new JsonObject());
            JsonObject json = Json.getJsonObject(responseBody);
            if (json == null) {
                return;
            }
            JsonObject data = json.getJsonObject("data");
            String wxid = firstString(data != null ? data : json,
                    "wxid", "wid", "userName", "account", "ilink_user_id");
            if (StringUtils.isBlank(wxid)) {
                wxid = firstString(json, "wxid", "wid", "userName", "account");
            }
            this.selfWxid = wxid;
        } catch (Exception e) {
            log.warn("[WechatPersonal] 获取登录账号失败，将不过滤自发消息: {}", e.getMessage());
        }
    }

    /**
     * 向 Hook 服务注册回调地址；Hook 服务不支持该接口时只告警，回调仍可由外部手工注入。
     */
    private void registerCallbackUrl() {
        if (StringUtils.isBlank(callbackUrl)) {
            return;
        }
        try {
            JsonObject body = new JsonObject();
            body.fluentPut("url", callbackUrl);
            body.fluentPut("enable", CALLBACK_ENABLED);
            String responseBody = execute(CALLBACK_REGISTER_PATH, HttpMethod.POST, body);
            log.info("[WechatPersonal] 回调地址已注册: {} -> {}", callbackUrl, responseBody);
        } catch (Exception e) {
            log.warn("[WechatPersonal] 回调地址注册失败，需自行调用 deliver(): {}", e.getMessage());
        }
    }

    /**
     * 回调体可能是单个对象，也可能是数组。
     *
     * @param rawBody 回调 JSON 原文，不允许为 null
     * @return 解析后的条目列表，解析失败时返回空列表
     */
    private List<Map<String, Object>> parsePayload(String rawBody) {
        List<Map<String, Object>> items = new ArrayList<>();
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("[")) {
            JsonArray array = Json.getJsonArray(trimmed);
            for (int i = 0; i < array.size(); i++) {
                JsonObject item = array.getJsonObject(i);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        }
        JsonObject json = Json.getJsonObject(trimmed);
        if (json != null) {
            items.add(json);
        }
        return items;
    }

    /**
     * 把 Hook 服务的原始消息映射为 {@link BotInboundMessage}，无有效消息体时返回空。
     *
     * @param item 原始消息键值对，不允许为 null
     * @return 入站消息；自发消息、重复消息或无有效消息体时返回 null
     */
    private BotInboundMessage toInbound(Map<String, Object> item) {
        Integer msgType = firstInt(item, "type", "msgType", "msg_type");
        String content = firstString(item, "msg", "content", "message");
        String msgId = firstString(item, "msgId", "msg_id", "serverId", "msgSvrId", "id");
        if (msgType == null && StringUtils.isBlank(content)) {
            return null;
        }
        if (StringUtils.isNotBlank(msgId) && seenMsgIds.containsKey(msgId)) {
            return null;
        }

        String talker = firstString(item, FROM_USER_KEYS);
        String room = firstString(item, ROOM_KEYS);
        if (StringUtils.isBlank(room)) {
            String toWxid = firstString(item, TO_USER_KEYS);
            if (toWxid != null && toWxid.endsWith(ROOM_SUFFIX)) {
                room = toWxid;
            }
        }
        boolean fromGroup = room != null && room.endsWith(ROOM_SUFFIX);
        String sender = firstString(item, ROOM_SENDER_KEYS);
        String fromUser = fromGroup ? defaultIfBlank(sender, room) : talker;
        if (StringUtils.isBlank(fromUser) || fromUser.equals(selfWxid)) {
            // 自己发出的消息，以及系统回执，不触发回复
            return null;
        }

        return BotInboundMessage.builder()
                .msgId(defaultIfBlank(msgId, talker + "-" + msgType + "-" + System.nanoTime()))
                .type(mapType(msgType))
                .content(content == null ? "" : content)
                .fromUser(fromUser)
                .fromUserName(firstString(item, "senderName", "nickname", "nickName"))
                .toUser(selfWxid)
                .createTime(createTime(item))
                .chatId(fromGroup ? room : null)
                .fromGroup(fromGroup)
                .eventType("wechat.personal.message")
                .rawField("msgType", msgType)
                .rawField("talker", talker)
                .rawField("roomWxid", room)
                .build();
    }

    /**
     * 把 Hook 服务的消息类型码映射为统一的入站消息类型。
     *
     * @param msgType 消息类型码，可为 null
     * @return 消息类型，未知类型返回 UNKNOWN
     */
    private BotInboundMessage.Type mapType(Integer msgType) {
        if (msgType == null) {
            return BotInboundMessage.Type.UNKNOWN;
        }
        switch (msgType) {
            case MSG_TYPE_TEXT:
                return BotInboundMessage.Type.TEXT;
            case MSG_TYPE_IMAGE:
                return BotInboundMessage.Type.IMAGE;
            case MSG_TYPE_VOICE:
                return BotInboundMessage.Type.VOICE;
            case MSG_TYPE_VIDEO:
                return BotInboundMessage.Type.VIDEO;
            case MSG_TYPE_FILE:
                return BotInboundMessage.Type.FILE;
            default:
                return BotInboundMessage.Type.UNKNOWN;
        }
    }

    /**
     * 分发给全部消息监听器，单个监听器异常不影响其余监听器。
     *
     * @param inbound 入站消息，不允许为 null
     */
    private void dispatch(BotInboundMessage inbound) {
        if (StringUtils.isNotBlank(inbound.getFromUser())) {
            userStore.upsert(BotUserInfo.builder()
                    .userId(inbound.getFromUser())
                    .username(inbound.getFromUser())
                    .nickname(inbound.getFromUserName())
                    .build());
        }
        for (BotMessageListener listener : messageListeners) {
            try {
                listener.onMessage(inbound);
            } catch (Exception e) {
                notifyError(e);
                log.error("[WechatPersonal] 监听器处理异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 记录已处理的消息 id，用于去重。
     *
     * @param inbound 入站消息，不允许为 null
     */
    private void remember(BotInboundMessage inbound) {
        if (StringUtils.isNotBlank(inbound.getMsgId())) {
            seenMsgIds.put(inbound.getMsgId(), Boolean.TRUE);
        }
    }

    /**
     * 通知全部错误监听器，监听器自身异常被忽略。
     *
     * @param e 异常原因，不允许为 null
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

    /**
     * 把被 @ 用户拼进文本，模拟 @ 效果。
     *
     * @param content 消息文本，可为 null
     * @param mentionedUserIds 被 @ 的用户 id 列表，可为 null
     * @return 拼接 @ 前缀后的文本
     */
    private static String withMentions(String content, List<String> mentionedUserIds) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder();
        for (String userId : mentionedUserIds) {
            builder.append('@').append(userId).append(' ');
        }
        builder.append(content == null ? "" : content);
        return builder.toString();
    }

    /**
     * 解析消息创建时间，秒级时间戳自动换算为毫秒。
     *
     * @param item 原始消息键值对，不允许为 null
     * @return 毫秒级时间戳，字段缺失时取当前时间
     */
    private static long createTime(Map<String, Object> item) {
        Number timestamp = firstNumber(item, "timestamp", "createTime", "create_time");
        if (timestamp == null) {
            return System.currentTimeMillis();
        }
        long value = timestamp.longValue();
        // 微信侧给的是秒级时间戳
        return value < SECONDS_TIMESTAMP_THRESHOLD ? value * MILLIS_PER_SECOND : value;
    }

    /**
     * 按候选键名依次取第一个非空字符串值。
     *
     * @param map 键值对，可为 null
     * @param keys 候选键名，按优先级排列
     * @return 命中的字符串值，全部未命中时返回 null
     */
    private static String firstString(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (StringUtils.isNotBlank(text) && !"null".equals(text)) {
                return text;
            }
        }
        return null;
    }

    /**
     * 按候选键名依次取第一个数值并转为 Integer。
     *
     * @param map 键值对，可为 null
     * @param keys 候选键名，按优先级排列
     * @return 整数值，未命中时返回 null
     */
    private static Integer firstInt(Map<String, Object> map, String... keys) {
        Number number = firstNumber(map, keys);
        return number == null ? null : number.intValue();
    }

    /**
     * 按候选键名依次取第一个数值（字符串数字也可解析）。
     *
     * @param map 键值对，可为 null
     * @param keys 候选键名，按优先级排列
     * @return 数值，未命中时返回 null
     */
    private static Number firstNumber(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return (Number) value;
            }
            if (value instanceof String && StringUtils.isNotBlank((String) value)) {
                try {
                    return Long.valueOf(((String) value).trim());
                } catch (NumberFormatException ignored) {
                    // 非数字字段，继续尝试下一个键
                }
            }
        }
        return null;
    }

    /**
     * 值为空白时返回兜底值。
     *
     * @param value 待检查的值，可为 null
     * @param fallback 兜底值
     * @return 非空白的原值或兜底值
     */
    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    /**
     * 去掉 URL 末尾的斜杠，避免拼接路径重复。
     *
     * @param url 原始 URL，不允许为 null
     * @return 去除末尾斜杠后的 URL
     */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
