package com.chua.qq.support.bot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

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
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * QQ 机器人客户端，实现 {@link BotClient}。
 * <p>
 * 对接 QQ 开放平台机器人 API v2：REST 侧以 {@code Authorization: QQBot {access_token}} 调用
 * {@code /gateway}、{@code /v2/users/{openid}/*}、{@code /v2/groups/{openid}/*}；
 * 事件侧走 WebSocket 长连接（op10 Hello → op2 Identify 或 op6 Resume → op0 Dispatch，
 * 并按 Hello 下发的间隔以 op1 心跳上报最新 seq）。
 * </p>
 * <p>
 * 平台未提供群列表接口，{@link #listGroups()} 返回长连接期间观测到的群会话；
 * 富媒体消息须先经 {@code /files}（URL 上传）或分片上传流程换取 {@code file_info}。
 * </p>
 * <pre>{@code
 * BotClient client = BotClient.auto("qq");
 * client.configure(appId, appSecret, null);
 * client.addMessageListener(msg -> client.sendToGroup(msg.getChatId(), "已收到"));
 * client.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class QqBotClient implements BotClient {

    /**
     * 正式环境 API 域名
     */
    private static final String DEFAULT_BASE_URL = "https://api.bot.qq.com";

    /**
     * 令牌颁发地址（官方仅此域名签发 access_token）
     */
    private static final String DEFAULT_TOKEN_URL =
            "https://bots.qq.com/app/getAppAccessToken";

    /**
     * 令牌接口路径
     */
    private static final String TOKEN_PATH = "/app/getAppAccessToken";

    /**
     * 剩余有效期低于该秒数时才换取新令牌，过早换取会被平台拒绝
     */
    private static final long TOKEN_REFRESH_AHEAD_SECONDS = 600L;

    /**
     * 换取令牌失败时的兜底有效期
     */
    private static final long TOKEN_FALLBACK_EXPIRES_SECONDS = 7200L;

    /**
     * 令牌最短保留可用秒数，避免到期临界点抖动
     */
    private static final long TOKEN_MIN_VALID_SECONDS = 60L;

    /**
     * 服务端下发事件
     */
    private static final int WS_OP_DISPATCH = 0;

    /**
     * 客户端心跳
     */
    private static final int WS_OP_HEARTBEAT = 1;

    /**
     * 鉴权识别
     */
    private static final int WS_OP_IDENTIFY = 2;

    /**
     * 断线恢复
     */
    private static final int WS_OP_RESUME = 6;

    /**
     * 服务端要求重连
     */
    private static final int WS_OP_RECONNECT = 7;

    /**
     * 会话失效
     */
    private static final int WS_OP_INVALID_SESSION = 9;

    /**
     * 握手成功并下发心跳间隔
     */
    private static final int WS_OP_HELLO = 10;

    /**
     * 心跳应答
     */
    private static final int WS_OP_HEARTBEAT_ACK = 11;

    /**
     * 群聊与单聊事件意图
     */
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;

    /**
     * 默认订阅意图：官方要求未申请到权限的意图不得上报，否则长连接会被拒绝
     */
    private static final int DEFAULT_INTENTS = INTENT_GROUP_AND_C2C;

    /**
     * 服务端未下发心跳间隔时的缺省间隔
     */
    private static final long HEARTBEAT_DEFAULT_MS = 45_000L;

    /**
     * 心跳最小间隔
     */
    private static final long HEARTBEAT_MIN_MS = 1_000L;

    /**
     * 重连退避起始
     */
    private static final long BACKOFF_INITIAL_MS = 1_000L;

    /**
     * 重连退避上限
     */
    private static final long BACKOFF_MAX_MS = 30_000L;

    /**
     * 退避倍数
     */
    private static final double BACKOFF_MULTIPLIER = 2.0D;

    /**
     * md5_10m 的计算窗口
     */
    private static final int MD5_HEAD_WINDOW_BYTES = 10 * 1024 * 1024;

    /**
     * 文本消息类型
     */
    private static final int MSG_TYPE_TEXT = 0;

    /**
     * 富媒体消息类型
     */
    private static final int MSG_TYPE_MEDIA = 7;

    /**
     * 平台文件类型：图片
     */
    private static final int FILE_TYPE_IMAGE = 1;

    /**
     * 平台文件类型：视频
     */
    private static final int FILE_TYPE_VIDEO = 2;

    /**
     * 平台文件类型：语音
     */
    private static final int FILE_TYPE_VOICE = 3;

    /**
     * 平台文件类型：文件
     */
    private static final int FILE_TYPE_FILE = 4;

    /**
     * 群聊被动回复有效窗口
     */
    private static final long PASSIVE_TTL_GROUP_MS = 5 * 60 * 1000L;

    /**
     * 单聊被动回复有效窗口
     */
    private static final long PASSIVE_TTL_C2C_MS = 60 * 60 * 1000L;

    /**
     * 需丢弃会话重新 identify 的关闭码，其余关闭码允许 resume
     */
    private static final Set<Integer> REIDENTIFY_CLOSE_CODES =
            Set.of(4001, 4002, 4004, 4005, 4007, 4009);

    /**
     * 机器人被封禁，禁止重连
     */
    private static final int CLOSE_CODE_BANNED = 4914;

    /**
     * 正常退出关闭码
     */
    private static final int CLOSE_CODE_NORMAL = 1000;

    /**
     * 会话就绪事件
     */
    private static final String EVENT_READY = "READY";

    /**
     * 消息类事件名（单聊、群聊、频道）
     */
    private static final Set<String> MESSAGE_EVENTS = Set.of(
            "C2C_MESSAGE_CREATE",
            "GROUP_AT_MESSAGE_CREATE",
            "AT_MESSAGE_CREATE",
            "MESSAGE_CREATE",
            "DIRECT_MESSAGE_CREATE");

    /**
     * 群聊类事件名
     */
    private static final Set<String> GROUP_MESSAGE_EVENTS = Set.of(
            "GROUP_AT_MESSAGE_CREATE",
            "AT_MESSAGE_CREATE",
            "MESSAGE_CREATE");

    /**
     * 含 @ 提及语义的事件名。单聊与私信事件（C2C_MESSAGE_CREATE、DIRECT_MESSAGE_CREATE）
     * 天然面向机器人但不存在 @ 动作，故不计入，口径与 FeishuBotClient 一致
     */
    private static final Set<String> MENTION_EVENTS = Set.of(
            "GROUP_AT_MESSAGE_CREATE",
            "AT_MESSAGE_CREATE");

    /**
     * 无需转发给监听器的会话与回执类事件名
     */
    private static final Set<String> SILENT_EVENTS = Set.of(
            "READY_OPENID",
            "RESUMED",
            "READ_C2C_MSG",
            "READ_GROUP_MSG",
            "C2C_MSG_SEND_STATE",
            "GROUP_MSG_SEND_STATE");

    /**
     * 应用 ID
     */
    private String appId;

    /**
     * 应用密钥
     */
    private String appSecret;

    /**
     * 预先获取的 access_token，非空时跳过令牌换取
     */
    private String botToken;

    /**
     * API 基础地址
     */
    private String baseUrl = DEFAULT_BASE_URL;

    /**
     * 令牌地址覆盖值，沙箱或代理环境使用
     */
    private String tokenUrl;

    /**
     * 连接超时（毫秒）
     */
    private long connectTimeoutMillis = 10_000L;

    /**
     * 读取超时（毫秒）
     */
    private long readTimeoutMillis = 30_000L;

    /**
     * Webhook 回调验证令牌
     */
    private String webhookVerifyToken;

    /**
     * 订阅意图位掩码
     */
    private int intents = DEFAULT_INTENTS;

    /**
     * 当前分片序号
     */
    private int shardId;

    /**
     * 分片总数
     */
    private int shardCount = 1;

    /**
     * HTTP 客户端
     */
    private volatile HttpClient httpClient;

    /**
     * 长连接
     */
    private volatile WebSocket webSocket;

    /**
     * 运行标记
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 会话结束闩
     */
    private volatile CountDownLatch sessionLatch;

    /**
     * 长连接线程
     */
    private volatile Thread wsThread;

    /**
     * 是否 Webhook 模式
     */
    private volatile boolean useWebhookMode;

    /**
     * 是否允许继续重连
     */
    private volatile boolean reconnectAllowed = true;

    /**
     * 换取到的 access_token
     */
    private volatile String accessToken;

    /**
     * access_token 到期时间戳（毫秒）
     */
    private volatile long tokenExpireAt;

    /**
     * 长连接会话 ID，用于 resume
     */
    private volatile String sessionId;

    /**
     * 最近一次 Dispatch 的 seq，-1 表示尚未收到
     */
    private final AtomicInteger lastSeq = new AtomicInteger(-1);

    /**
     * 心跳线程池
     */
    private volatile ScheduledExecutorService heartbeatExecutor;

    /**
     * 心跳任务
     */
    private volatile ScheduledFuture<?> heartbeatTask;

    /**
     * 长连接发送锁，避免并发写同一连接
     */
    private final Object wsSendLock = new Object();

    /**
     * 配置持久化器
     */
    private ConfigSaveOrLoader configSaveOrLoader;

    /**
     * 消息监听器
     */
    private final List<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 错误监听器
     */
    private final List<BotErrorListener> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * 用户存储
     */
    private BotUserStore userStore = new InMemoryBotUserStore();

    /**
     * 被动回复窗口，键为会话 ID
     */
    private final Map<String, PassiveContext> passiveContexts = new ConcurrentHashMap<>();

    /**
     * 观测到的群会话
     */
    private final Map<String, BotGroupInfo> observedGroups = new ConcurrentHashMap<>();

    /**
     * 观测到的群成员
     */
    private final Map<String, Set<String>> observedGroupMembers = new ConcurrentHashMap<>();

    // ==================== 配置 ====================

    @Override
    public BotClient configure(String token, String secret, String encodingAesKey) {
        this.appId = token;
        this.appSecret = secret;
        this.botToken = encodingAesKey;
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
        this.botToken = encodingAesKey;
        return this;
    }

    @Override
    public BotClient baseUrl(String baseUrl) {
        if (StringUtils.isNotBlank(baseUrl)) {
            this.baseUrl = trimTrailingSlash(baseUrl);
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
        this.configSaveOrLoader = configSaveOrLoader;
        return this;
    }

    @Override
    public BotClient userStore(BotUserStore userStore) {
        this.userStore = userStore == null ? new InMemoryBotUserStore() : userStore;
        return this;
    }

    /**
     * 设置订阅意图，多个意图按位或合并；不传时恢复默认意图（仅群聊与单聊 1&lt;&lt;25）。
     * <p>频道公域消息为 {@code 1 << 30}，仅在应用已申请到该权限时才可上报，
     * 否则长连接会被平台以关闭码 4002 拒绝。</p>
     *
     * @param values 意图位掩码
     * @return this
     */
    public QqBotClient intents(int... values) {
        this.intents = mergeIntents(values);
        return this;
    }

    /**
     * 设置令牌颁发地址，沙箱环境需与 API 域名分别指定
     *
     * @param tokenUrl 令牌地址
     * @return this
     */
    public QqBotClient tokenUrl(String tokenUrl) {
        this.tokenUrl = trimTrailingSlash(tokenUrl);
        return this;
    }

    /**
     * 设置分片
     *
     * @param shardId    当前分片序号
     * @param shardCount 分片总数
     * @return this
     */
    public QqBotClient shard(int shardId, int shardCount) {
        this.shardId = shardId;
        this.shardCount = Math.max(1, shardCount);
        return this;
    }

    /**
     * 设置 Webhook 回调验证令牌，非空时以 Webhook 模式启动
     *
     * @param token 验证令牌
     * @return this
     */
    public QqBotClient webhookVerifyToken(String token) {
        this.webhookVerifyToken = token;
        return this;
    }

    // ==================== 生命周期 ====================

    @Override
    public BotClient start() {
        if (StringUtils.isBlank(appId)) {
            throw new IllegalStateException("QQ 机器人需要 appId（token）");
        }
        if (running.get()) {
            return this;
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        running.set(true);
        this.reconnectAllowed = true;
        if (StringUtils.isNotBlank(webhookVerifyToken)) {
            this.useWebhookMode = true;
            log.info("QQ 机器人以 Webhook 模式启动, appId={}", appId);
            return this;
        }
        this.useWebhookMode = false;
        startWebSocketLoop();
        log.info("QQ 机器人以 WebSocket 长连接模式启动, appId={}, baseUrl={}", appId, baseUrl);
        return this;
    }

    @Override
    public void stop() {
        running.set(false);
        reconnectAllowed = false;
        stopHeartbeat();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(CLOSE_CODE_NORMAL, "stopped");
            } catch (Exception ignored) {
                // 连接已断开，无需处理
            }
        }
        Thread thread = wsThread;
        wsThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        CountDownLatch latch = sessionLatch;
        sessionLatch = null;
        if (latch != null) {
            latch.countDown();
        }
        this.sessionId = null;
        this.accessToken = null;
        this.tokenExpireAt = 0L;
        this.lastSeq.set(-1);
        this.passiveContexts.clear();
        log.info("QQ 机器人已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 是否 Webhook 模式
     *
     * @return true 表示 Webhook 模式
     */
    public boolean isUseWebhookMode() {
        return useWebhookMode;
    }

    /**
     * 校验 Webhook 回调令牌，匹配时回显 challenge
     *
     * @param challengeToken 平台下发的 challenge
     * @return 验证通过返回 challengeToken，否则 null
     */
    public String verifyChallenge(String challengeToken) {
        if (webhookVerifyToken == null || challengeToken == null
                || !webhookVerifyToken.equals(challengeToken)) {
            return null;
        }
        return challengeToken;
    }

    // ==================== 长连接 ====================

    /**
     * 启动长连接线程
     */
    private void startWebSocketLoop() {
        Thread thread = new Thread(this::runWebSocketLoop, "qq-bot-ws");
        thread.setDaemon(true);
        wsThread = thread;
        thread.start();
    }

    /**
     * 长连接主循环，异常后指数退避重连
     */
    private void runWebSocketLoop() {
        long backoff = BACKOFF_INITIAL_MS;
        while (running.get() && reconnectAllowed) {
            CountDownLatch latch = new CountDownLatch(1);
            sessionLatch = latch;
            try {
                openSession();
                backoff = BACKOFF_INITIAL_MS;
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                notifyError(e);
                if (!running.get() || !reconnectAllowed) {
                    return;
                }
                log.warn("QQ 机器人长连接中断, {}ms 后重试: {}", backoff, e.getMessage());
                backoff = sleepBackoff(backoff);
            }
        }
    }

    /**
     * 建立一次会话：取令牌 → 取网关地址 → 建连
     *
     * @throws Exception 建连失败
     */
    private void openSession() throws Exception {
        String gateway = getGatewayUrl(getAuthToken());
        WebSocket ws = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .buildAsync(URI.create(gateway), new QqWebSocketListener())
                .get(connectTimeoutMillis, TimeUnit.MILLISECONDS);
        webSocket = ws;
    }

    /**
     * 拉取 WebSocket 网关地址
     *
     * @param authorization 认证头
     * @return 网关地址
     */
    private String getGatewayUrl(String authorization) {
        Map<String, Object> result = request("GET", "/gateway", null, authorization);
        String url = stringValue(result.get("url"));
        if (StringUtils.isBlank(url)) {
            throw new QqApiException(-1, "网关响应缺少 url 字段: " + Json.toJson(result));
        }
        return url;
    }

    /**
     * 长连接监听器
     */
    private class QqWebSocketListener implements WebSocket.Listener {

        /**
         * 分片文本缓冲
         */
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            log.debug("QQ 机器人长连接已建立");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                try {
                    handleWsMessage(frame);
                } catch (Exception e) {
                    notifyError(e);
                    log.error("QQ 机器人处理下行帧失败: {}", e.getMessage(), e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            webSocket = null;
            stopHeartbeat();
            if (statusCode == CLOSE_CODE_BANNED) {
                reconnectAllowed = false;
                log.error("QQ 机器人被封禁, 停止重连: {}", reason);
            } else if (REIDENTIFY_CLOSE_CODES.contains(statusCode)) {
                sessionId = null;
                lastSeq.set(-1);
                log.warn("QQ 机器人会话失效(关闭码 {}), 重连后重新鉴权: {}", statusCode, reason);
            } else {
                log.warn("QQ 机器人长连接关闭(关闭码 {}): {}", statusCode, reason);
            }
            endSession();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            webSocket = null;
            stopHeartbeat();
            notifyError(error);
            log.error("QQ 机器人长连接异常: {}", error.getMessage());
            endSession();
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            ws.sendPong(message);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            return null;
        }
    }

    /**
     * 结束当前会话，唤醒主循环
     */
    private void endSession() {
        CountDownLatch latch = sessionLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 处理一条下行帧
     *
     * @param rawMessage 帧原文
     */
    private void handleWsMessage(String rawMessage) {
        if (StringUtils.isBlank(rawMessage)) {
            return;
        }
        Map<String, Object> frame = Json.fromJson(rawMessage, Map.class);
        if (frame == null) {
            return;
        }
        int op = intValue(frame.get("op"), -1);
        Object payload = frame.get("d");
        switch (op) {
            case WS_OP_HELLO -> onHello(payload);
            case WS_OP_DISPATCH -> onDispatch(frame, payload);
            case WS_OP_HEARTBEAT_ACK -> log.debug("QQ 机器人心跳已应答");
            case WS_OP_RECONNECT -> {
                log.info("QQ 机器人收到重连指令, 保留会话后重连");
                closeCurrentSocket("reconnect");
            }
            case WS_OP_INVALID_SESSION -> onInvalidSession(payload);
            default -> log.debug("QQ 机器人忽略下行帧 op={}", op);
        }
    }

    /**
     * Hello 帧：按下发间隔启动心跳并鉴权
     *
     * @param payload 帧载荷
     */
    private void onHello(Object payload) {
        long interval = HEARTBEAT_DEFAULT_MS;
        if (payload instanceof Map<?, ?> map) {
            interval = longValue(map.get("heartbeat_interval"), HEARTBEAT_DEFAULT_MS);
        }
        startHeartbeat(interval);
        if (StringUtils.isNotBlank(sessionId)) {
            resume();
        } else {
            identify();
        }
    }

    /**
     * Invalid Session 帧：d 为 false 时可 resume，否则重新 identify
     *
     * @param payload 帧载荷
     */
    private void onInvalidSession(Object payload) {
        boolean resumable = Boolean.FALSE.equals(payload);
        if (!resumable) {
            sessionId = null;
            lastSeq.set(-1);
        }
        log.info("QQ 机器人会话被判定失效, 可恢复={}, 重连", resumable);
        closeCurrentSocket("invalid-session");
    }

    /**
     * Dispatch 帧：记录 seq 并分派事件
     *
     * @param frame   帧
     * @param payload 事件体
     */
    private void onDispatch(Map<String, Object> frame, Object payload) {
        int seq = intValue(frame.get("s"), -1);
        if (seq >= 0) {
            lastSeq.set(seq);
        }
        String event = stringValue(frame.get("t"));
        if (StringUtils.isBlank(event) || !(payload instanceof Map<?, ?> body)) {
            return;
        }
        if (EVENT_READY.equals(event)) {
            String id = stringValue(body.get("session_id"));
            if (StringUtils.isNotBlank(id)) {
                this.sessionId = id;
            }
            log.info("QQ 机器人会话就绪, session_id={}", sessionId);
            return;
        }
        if (SILENT_EVENTS.contains(event)) {
            return;
        }
        Map<String, Object> eventBody = asMap(body);
        if (MESSAGE_EVENTS.contains(event)) {
            dispatchMessage(event, eventBody);
        } else {
            dispatchEvent(event, eventBody);
        }
    }

    /**
     * 消息类事件转领域模型并广播
     *
     * @param event 事件名
     * @param body  事件体
     */
    private void dispatchMessage(String event, Map<String, Object> body) {
        Map<String, Object> author = asMap(body.get("author"));
        String fromUser = firstNotBlank(
                stringValue(author.get("member_openid")),
                stringValue(author.get("user_openid")),
                stringValue(author.get("union_openid")),
                stringValue(author.get("id")));
        String fromUserName = stringValue(author.get("username"));
        boolean fromGroup = GROUP_MESSAGE_EVENTS.contains(event)
                || body.get("group_openid") != null;
        String chatId = firstNotBlank(
                fromGroup ? stringValue(body.get("group_openid")) : null,
                stringValue(body.get("channel_id")),
                fromUser);
        String msgId = stringValue(body.get("id"));
        Map<String, Object> attachment = firstMap(body.get("attachments"));
        BotInboundMessage.Type type = BotInboundMessage.Type.TEXT;
        String mediaUrl = null;
        if (attachment != null) {
            type = mediaType(intValue(attachment.get("file_type"), 0));
            mediaUrl = stringValue(attachment.get("url"));
        }
        String rawContent = stringValue(body.get("content"));
        BotInboundMessage message = BotInboundMessage.builder()
                .msgId(msgId)
                .type(type)
                .content(rawContent == null ? null : rawContent.trim())
                .fromUser(fromUser)
                .fromUserName(fromUserName)
                .toUser(chatId)
                .createTime(parseTimestamp(stringValue(body.get("timestamp"))))
                .mediaUrl(mediaUrl)
                .chatId(chatId)
                .fromGroup(fromGroup)
                .mentionedList(readMentions(body.get("mentions")))
                .mentionedBot(MENTION_EVENTS.contains(event))
                .rawFields(body)
                .rawField("event", event)
                .rawField("msg_seq", body.get("msg_seq"))
                .build();
        rememberConversation(fromUser, fromUserName, chatId, fromGroup);
        rememberPassive(chatId, msgId, fromGroup);
        notifyMessage(message);
    }

    /**
     * 非消息类事件转领域模型并广播
     *
     * @param event 事件名
     * @param body  事件体
     */
    private void dispatchEvent(String event, Map<String, Object> body) {
        String groupOpenid = stringValue(body.get("group_openid"));
        BotInboundMessage message = BotInboundMessage.builder()
                .msgId(stringValue(body.get("id")))
                .type(BotInboundMessage.Type.EVENT)
                .eventType(event)
                .eventKey(firstNotBlank(stringValue(body.get("op_type")),
                        stringValue(body.get("type"))))
                .fromUser(firstNotBlank(stringValue(body.get("operator_openid")),
                        stringValue(body.get("openid"))))
                .chatId(firstNotBlank(groupOpenid, stringValue(body.get("channel_id")),
                        stringValue(body.get("openid"))))
                .fromGroup(groupOpenid != null)
                .createTime(System.currentTimeMillis())
                .rawFields(body)
                .rawField("event", event)
                .build();
        notifyMessage(message);
    }

    /**
     * 发送 identify 帧
     */
    private void identify() {
        JsonObject properties = new JsonObject()
                .fluent("$os", "linux")
                .fluent("$browser", "utils-support")
                .fluent("$device", "utils-support");
        JsonObject payload = new JsonObject()
                .fluent("token", getAuthToken())
                .fluent("intents", intents)
                .fluent("shard", List.of(shardId, shardCount))
                .fluent("properties", properties);
        sendFrame(WS_OP_IDENTIFY, payload);
    }

    /**
     * 发送 resume 帧
     */
    private void resume() {
        JsonObject payload = new JsonObject()
                .fluent("token", getAuthToken())
                .fluent("session_id", sessionId)
                .fluent("seq", Math.max(0, lastSeq.get()));
        sendFrame(WS_OP_RESUME, payload);
    }

    /**
     * 启动心跳
     *
     * @param intervalMs 心跳间隔毫秒
     */
    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();
        long period = Math.max(HEARTBEAT_MIN_MS, intervalMs);
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qq-bot-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.cancel(false);
        }
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 以 op1 上报最新 seq
     */
    private void sendHeartbeat() {
        int seq = lastSeq.get();
        sendFrame(WS_OP_HEARTBEAT, seq < 0 ? null : seq);
    }

    /**
     * 发送一个操作帧
     *
     * @param op      操作码
     * @param payload 载荷，可为 null
     */
    private void sendFrame(int op, Object payload) {
        JsonObject frame = new JsonObject();
        frame.fluent("op", op);
        frame.fluent("d", payload);
        sendWsMessage(Json.toJson(frame));
    }

    /**
     * 发送一条文本帧
     *
     * @param message 帧原文
     */
    private void sendWsMessage(String message) {
        WebSocket ws = webSocket;
        if (ws == null) {
            log.warn("QQ 机器人长连接未就绪, 丢弃帧: {}", message);
            return;
        }
        try {
            synchronized (wsSendLock) {
                ws.sendText(message, true)
                        .get(readTimeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            notifyError(e);
            log.warn("QQ 机器人发送帧失败: {}", e.getMessage());
        }
    }

    /**
     * 主动关闭当前长连接，交由主循环重连
     *
     * @param reason 关闭原因
     */
    private void closeCurrentSocket(String reason) {
        WebSocket ws = webSocket;
        webSocket = null;
        stopHeartbeat();
        if (ws != null) {
            try {
                ws.sendClose(CLOSE_CODE_NORMAL, reason);
            } catch (Exception ignored) {
                // 已由服务端断开，无需处理
            }
        }
        endSession();
    }

    // ==================== 发送 ====================

    @Override
    public BotSendResult sendText(String toUser, String content) {
        return sendTextInternal(toUser, content, false, null);
    }

    @Override
    public BotSendResult sendImage(String toUser, String mediaPath) {
        return sendMediaInternal(toUser, mediaPath, FILE_TYPE_IMAGE, false, null);
    }

    @Override
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        return sendMediaInternal(toUser, mediaPath, FILE_TYPE_VOICE, false, null);
    }

    @Override
    public BotSendResult sendVideo(String toUser, String mediaPath, String title, String desc) {
        return sendMediaInternal(toUser, mediaPath, FILE_TYPE_VIDEO, false, null);
    }

    @Override
    public BotSendResult sendFile(String toUser, String mediaPath) {
        return sendMediaInternal(toUser, mediaPath, FILE_TYPE_FILE, false, null);
    }

    @Override
    public BotSendResult send(BotOutboundMessage message) {
        if (message == null) {
            return BotSendResult.fail(-1, "消息不能为空");
        }
        String target = message.getToUser();
        boolean toGroup = message.isToGroup();
        String msgId = message.extension("msg_id");
        BotInboundMessage.Type type = message.getType();
        if (type == null) {
            type = StringUtils.isNotBlank(message.getMediaPath())
                    ? BotInboundMessage.Type.IMAGE : BotInboundMessage.Type.TEXT;
        }
        return switch (type) {
            case IMAGE -> sendMediaInternal(target, message.getMediaPath(),
                    FILE_TYPE_IMAGE, toGroup, msgId);
            case VOICE -> sendMediaInternal(target, message.getMediaPath(),
                    FILE_TYPE_VOICE, toGroup, msgId);
            case VIDEO -> sendMediaInternal(target, message.getMediaPath(),
                    FILE_TYPE_VIDEO, toGroup, msgId);
            case FILE -> sendMediaInternal(target, message.getMediaPath(),
                    FILE_TYPE_FILE, toGroup, msgId);
            case TEXT -> sendTextInternal(target, message.getContent(), toGroup, msgId);
            default -> BotSendResult.fail(-1, "QQ 机器人不支持的消息类型: " + type);
        };
    }

    @Override
    public BotSendResult sendToGroup(String groupId, String content) {
        return sendTextInternal(groupId, content, true, null);
    }

    @Override
    public BotSendResult sendToGroupMention(String groupId, String content,
                                            List<String> mentionedUserIds) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) {
            return sendTextInternal(groupId, content, true, null);
        }
        return BotSendResult.fail(-1, "QQ 机器人消息接口无 @ 提及参数, 无法定向提醒成员");
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
    public CompletableFuture<BotSendResult> sendToGroupAsync(String groupId, String content) {
        return CompletableFuture.supplyAsync(() -> sendToGroup(groupId, content));
    }

    @Override
    public CompletableFuture<BotSendResult> sendToGroupMentionAsync(
            String groupId, String content, List<String> mentionedUserIds) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroupMention(groupId, content, mentionedUserIds));
    }

    /**
     * 发送文本消息
     *
     * @param target  单聊 openid 或群 openid
     * @param content 文本内容
     * @param toGroup 是否群聊
     * @param msgId   指定被动回复的消息 ID，可为 null
     * @return 发送结果
     */
    private BotSendResult sendTextInternal(String target, String content, boolean toGroup,
                                           String msgId) {
        if (!running.get()) {
            return notRunning();
        }
        JsonObject body = new JsonObject()
                .fluent("msg_type", MSG_TYPE_TEXT)
                .fluent("content", content);
        applyPassive(body, target, msgId);
        return postMessage(messagePath(target, toGroup), body);
    }

    /**
     * 发送富媒体消息：先上传取 file_info，再以 msg_type=7 下发
     *
     * @param target    单聊 openid 或群 openid
     * @param mediaPath 资源地址或本地文件路径
     * @param fileType  平台文件类型
     * @param toGroup   是否群聊
     * @param msgId     指定被动回复的消息 ID，可为 null
     * @return 发送结果
     */
    private BotSendResult sendMediaInternal(String target, String mediaPath, int fileType,
                                            boolean toGroup, String msgId) {
        if (!running.get()) {
            return notRunning();
        }
        if (StringUtils.isBlank(mediaPath)) {
            return BotSendResult.fail(-1, "资源路径不能为空");
        }
        try {
            String fileInfo = uploadMedia(target, mediaPath, fileType, toGroup);
            if (StringUtils.isBlank(fileInfo)) {
                return BotSendResult.fail(-1, "上传未返回 file_info");
            }
            JsonObject body = new JsonObject()
                    .fluent("msg_type", MSG_TYPE_MEDIA)
                    .fluent("media", new JsonObject().fluent("file_info", fileInfo));
            applyPassive(body, target, msgId);
            return postMessage(messagePath(target, toGroup), body);
        } catch (QqApiException e) {
            log.warn("QQ 机器人富媒体发送失败 path={} code={} message={}",
                    mediaPath, e.getCode(), e.getMessage());
            return BotSendResult.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            notifyError(e);
            return BotSendResult.fail(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 上传资源并返回 file_info
     *
     * @param target    会话目标
     * @param mediaPath 资源地址或本地文件路径
     * @param fileType  平台文件类型
     * @param toGroup   是否群聊
     * @return file_info
     * @throws IOException 本地文件读取失败
     */
    private String uploadMedia(String target, String mediaPath, int fileType, boolean toGroup)
            throws IOException {
        if (isRemoteUrl(mediaPath)) {
            JsonObject body = new JsonObject()
                    .fluent("file_type", fileType)
                    .fluent("url", mediaPath)
                    .fluent("srv_send_msg", false);
            return stringValue(request("POST", filePath(target, toGroup), body).get("file_info"));
        }
        return uploadLocalFile(target, mediaPath, fileType, toGroup);
    }

    /**
     * 本地文件分片上传：upload_prepare → PUT 分片 → upload_part_finish → /files 合并
     *
     * @param target    会话目标
     * @param mediaPath 本地文件路径
     * @param fileType  平台文件类型
     * @param toGroup   是否群聊
     * @return file_info
     * @throws IOException 文件读取失败
     */
    private String uploadLocalFile(String target, String mediaPath, int fileType,
                                   boolean toGroup) throws IOException {
        Path file = Paths.get(mediaPath);
        byte[] data = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        Map<String, Object> prepared = request("POST", uploadPreparePath(target, toGroup),
                new JsonObject()
                        .fluent("file_type", fileType)
                        .fluent("file_size", String.valueOf(data.length))
                        .fluent("md5", DigestUtils.md5(data))
                        .fluent("sha1", DigestUtils.sha1(data))
                        .fluent("md5_10m", DigestUtils.md5(headBytes(data)))
                        .fluent("file_name", fileName));
        String uploadId = stringValue(prepared.get("upload_id"));
        long blockSize = longValue(prepared.get("block_size"), data.length);
        for (Map<String, Object> part : mapList(prepared.get("parts"))) {
            if (Boolean.TRUE.equals(part.get("uploaded"))) {
                continue;
            }
            int index = intValue(part.get("index"), 0);
            putChunk(stringValue(part.get("presigned_url")),
                    slice(data, index, longValue(part.get("block_size"), blockSize)));
            request("POST", uploadPartFinishPath(target, toGroup), new JsonObject()
                    .fluent("upload_id", uploadId)
                    .fluent("file_type", fileType)
                    .fluent("file_name", fileName)
                    .fluent("part_index", index));
        }
        Map<String, Object> merged = request("POST", filePath(target, toGroup), new JsonObject()
                .fluent("file_type", fileType)
                .fluent("url", "")
                .fluent("file_info", "")
                .fluent("srv_send_msg", false)
                .fluent("upload_id", uploadId));
        return stringValue(merged.get("file_info"));
    }

    /**
     * PUT 单个分片到预签名地址
     *
     * @param presignedUrl 预签名地址
     * @param chunk        分片字节
     */
    private void putChunk(String presignedUrl, byte[] chunk) {
        if (StringUtils.isBlank(presignedUrl)) {
            throw new QqApiException(-1, "分片上传响应缺少 presigned_url");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new QqApiException(response.statusCode(),
                        "分片上传失败 HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new QqApiException(-1, "分片上传失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqApiException(-1, "分片上传被中断");
        }
    }

    /**
     * 提交消息发送请求
     *
     * @param path 接口路径
     * @param body 请求体
     * @return 发送结果
     */
    private BotSendResult postMessage(String path, JsonObject body) {
        try {
            Map<String, Object> result = request("POST", path, body);
            return BotSendResult.ok(stringValue(result.get("id")));
        } catch (QqApiException e) {
            log.warn("QQ 机器人消息发送失败 path={} code={} message={}",
                    path, e.getCode(), e.getMessage());
            return BotSendResult.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            notifyError(e);
            return BotSendResult.fail(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 补上被动回复所需的 msg_id 与 msg_seq；无有效窗口时按主动消息发送
     *
     * @param body   请求体
     * @param target 会话目标
     * @param msgId  显式指定的消息 ID，可为 null
     */
    private void applyPassive(JsonObject body, String target, String msgId) {
        if (target == null) {
            body.fluent("msg_seq", ThreadLocalRandom.current().nextInt(1, 0x7FFFFFFF));
            return;
        }
        PassiveContext context = passiveContexts.get(target);
        if (context == null) {
            PassiveContext fresh = new PassiveContext(null, 0L);
            PassiveContext prev = passiveContexts.putIfAbsent(target, fresh);
            context = prev != null ? prev : fresh;
        }
        boolean withinWindow = context.expireAt >= System.currentTimeMillis()
                && StringUtils.isNotBlank(context.msgId);
        String passiveMsgId = StringUtils.isNotBlank(msgId) ? msgId
                : (withinWindow ? context.msgId : null);
        int seq = context.seq.incrementAndGet();
        if (StringUtils.isNotBlank(passiveMsgId)) {
            body.fluent("msg_id", passiveMsgId);
        } else {
            body.fluent("srv_send_msg", Boolean.TRUE);
        }
        body.fluent("msg_seq", seq);
    }

    /**
     * 记录入站消息的被动回复窗口
     *
     * @param chatId    会话 ID
     * @param msgId     消息 ID
     * @param fromGroup 是否群聊
     */
    private void rememberPassive(String chatId, String msgId, boolean fromGroup) {
        if (StringUtils.isBlank(chatId) || StringUtils.isBlank(msgId)) {
            return;
        }
        long expireAt = System.currentTimeMillis()
                + (fromGroup ? PASSIVE_TTL_GROUP_MS : PASSIVE_TTL_C2C_MS);
        PassiveContext context = passiveContexts.get(chatId);
        if (context == null) {
            PassiveContext fresh = new PassiveContext(msgId, expireAt);
            PassiveContext prev = passiveContexts.putIfAbsent(chatId, fresh);
            if (prev != null) {
                prev.renew(msgId, expireAt);
            }
        } else {
            context.renew(msgId, expireAt);
        }
    }

    /**
     * @return 客户端未启动的失败结果
     */
    private static BotSendResult notRunning() {
        return BotSendResult.fail(-1, "客户端未启动");
    }

    // ==================== 会话与用户 ====================

    @Override
    public List<BotGroupInfo> listGroups() {
        return new ArrayList<>(observedGroups.values());
    }

    @Override
    public List<BotUserInfo> listUsers() {
        return userStore.findAll();
    }

    /**
     * 记录入站消息涉及的成员与群会话
     *
     * @param userId    成员 openid
     * @param userName  成员昵称
     * @param chatId    会话 ID
     * @param fromGroup 是否群聊
     */
    private void rememberConversation(String userId, String userName, String chatId,
                                      boolean fromGroup) {
        if (StringUtils.isNotBlank(userId)) {
            userStore.upsert(BotUserInfo.builder()
                    .userId(userId)
                    .username(userName)
                    .nickname(userName)
                    .build());
        }
        if (!fromGroup || StringUtils.isBlank(chatId)) {
            return;
        }
        Set<String> members = observedGroupMembers.computeIfAbsent(chatId,
                key -> ConcurrentHashMap.newKeySet());
        if (StringUtils.isNotBlank(userId)) {
            members.add(userId);
        }
        observedGroups.put(chatId, BotGroupInfo.builder()
                .groupId(chatId)
                .memberIds(new ArrayList<>(members))
                .memberCount(members.size())
                .build());
    }

    // ==================== 令牌与 REST ====================

    /**
     * 取 REST 与长连接鉴权用的认证头
     *
     * @return {@code QQBot {access_token}}
     */
    private synchronized String getAuthToken() {
        if (StringUtils.isNotBlank(botToken)) {
            return "QQBot " + botToken;
        }
        if (StringUtils.isBlank(accessToken) || System.currentTimeMillis() >= tokenExpireAt) {
            fetchAccessToken();
        }
        return "QQBot " + accessToken;
    }

    /**
     * 换取 access_token 并按有效期安排续期
     */
    private void fetchAccessToken() {
        if (StringUtils.isBlank(appSecret)) {
            throw new IllegalStateException("未提供 botToken 时必须配置 appSecret");
        }
        Map<String, Object> result = request("POST", tokenPath(), new JsonObject()
                .fluent("appId", appId)
                .fluent("clientSecret", appSecret), null);
        String token = stringValue(result.get("access_token"));
        if (StringUtils.isBlank(token)) {
            throw new QqApiException(-1, "获取 access_token 失败: " + Json.toJson(result));
        }
        long expiresInSeconds = longValue(result.get("expires_in"),
                TOKEN_FALLBACK_EXPIRES_SECONDS);
        long validSeconds = Math.max(TOKEN_MIN_VALID_SECONDS,
                expiresInSeconds - TOKEN_REFRESH_AHEAD_SECONDS);
        this.accessToken = token;
        this.tokenExpireAt = System.currentTimeMillis() + validSeconds * 1000L;
        log.debug("QQ 机器人已换取 access_token, 有效期 {} 秒", expiresInSeconds);
    }

    /**
     * @return 令牌接口地址
     */
    private String tokenPath() {
        if (StringUtils.isNotBlank(tokenUrl)) {
            return tokenUrl;
        }
        // 令牌仅由 bots.qq.com 签发；自定义 baseUrl（测试网关、代理、私有部署）时同源发起
        return DEFAULT_BASE_URL.equals(baseUrl) ? DEFAULT_TOKEN_URL : baseUrl + TOKEN_PATH;
    }

    /**
     * 调用平台 REST 接口
     *
     * @param method HTTP 方法
     * @param path   路径
     * @param body   请求体，可为 null
     * @return 响应体
     */
    private Map<String, Object> request(String method, String path, JsonObject body) {
        return request(method, path, body, getAuthToken());
    }

    /**
     * 调用平台 REST 接口
     *
     * @param method        HTTP 方法
     * @param path          路径
     * @param body          请求体，可为 null
     * @param authorization 认证头，null 表示匿名
     * @return 响应体
     * @throws QqApiException HTTP 非 2xx 或平台返回业务错误码
     */
    private Map<String, Object> request(String method, String path, JsonObject body,
                                        String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(absolute(path)))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "application/json;charset=utf-8");
        if (StringUtils.isNotBlank(authorization)) {
            builder.header("Authorization", authorization);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return unwrap(method, path, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqApiException(-1, "请求被中断: " + method + " " + path);
        } catch (IOException e) {
            throw new QqApiException(-1, "请求失败: " + method + " " + path + " " + e.getMessage());
        }
    }

    /**
     * 校验响应状态与业务错误码
     *
     * @param method   HTTP 方法
     * @param path     路径
     * @param response 响应
     * @return 响应体
     */
    private static Map<String, Object> unwrap(String method, String path,
                                              HttpResponse<String> response) {
        String raw = response.body();
        Map<String, Object> parsed = StringUtils.isBlank(raw)
                ? new LinkedHashMap<>() : Json.fromJson(raw, Map.class);
        if (parsed == null) {
            parsed = new LinkedHashMap<>();
        }
        int code = intValue(parsed.get("code"), 0);
        int ret = intValue(parsed.get("ret"), 0);
        if (response.statusCode() < 300 && code == 0 && ret == 0) {
            return parsed;
        }
        int bizCode = code != 0 ? code : (ret != 0 ? ret : response.statusCode());
        String message = firstNotBlank(stringValue(parsed.get("message")),
                stringValue(parsed.get("error")), raw);
        throw new QqApiException(bizCode, "QQ 平台返回错误 " + method + " " + path
                + " (HTTP " + response.statusCode() + "): " + message);
    }

    /**
     * @param path 路径
     * @return 绝对地址
     */
    private String absolute(String path) {
        return path.startsWith("http") ? path : baseUrl + path;
    }

    /**
     * @param target  会话目标
     * @param toGroup 是否群聊
     * @return 会话资源路径前缀
     */
    private static String conversationPath(String target, boolean toGroup) {
        return (toGroup ? "/v2/groups/" : "/v2/users/") + target;
    }

    /**
     * @param target  会话目标
     * @param toGroup 是否群聊
     * @return 消息发送路径
     */
    private static String messagePath(String target, boolean toGroup) {
        return conversationPath(target, toGroup) + "/messages";
    }

    /**
     * @param target  会话目标
     * @param toGroup 是否群聊
     * @return 整文件上传/合并路径
     */
    private static String filePath(String target, boolean toGroup) {
        return conversationPath(target, toGroup) + "/files";
    }

    /**
     * @param target  会话目标
     * @param toGroup 是否群聊
     * @return 分片预上传路径
     */
    private static String uploadPreparePath(String target, boolean toGroup) {
        return conversationPath(target, toGroup) + "/upload_prepare";
    }

    /**
     * @param target  会话目标
     * @param toGroup 是否群聊
     * @return 分片完成通知路径
     */
    private static String uploadPartFinishPath(String target, boolean toGroup) {
        return conversationPath(target, toGroup) + "/upload_part_finish";
    }

    // ==================== 配置与监听 ====================

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appId", appId);
        config.put("baseUrl", baseUrl);
        config.put("tokenUrl", tokenPath());
        config.put("intents", intents);
        config.put("connectTimeoutMillis", connectTimeoutMillis);
        config.put("readTimeoutMillis", readTimeoutMillis);
        config.put("running", running.get());
        config.put("webhookMode", useWebhookMode);
        config.put("websocketConnected", webSocket != null);
        config.put("sessionId", sessionId);
        config.put("lastSeq", lastSeq.get());
        config.put("observedGroups", observedGroups.size());
        return config;
    }

    @Override
    public BotClient addMessageListener(BotMessageListener listener) {
        if (listener != null && !messageListeners.contains(listener)) {
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
        if (listener != null && !errorListeners.contains(listener)) {
            errorListeners.add(listener);
        }
        return this;
    }

    /**
     * 广播入站消息
     *
     * @param message 入站消息
     */
    private void notifyMessage(BotInboundMessage message) {
        for (BotMessageListener listener : messageListeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                notifyError(e);
                log.error("QQ 机器人消息监听器异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 广播异常
     *
     * @param error 异常
     */
    private void notifyError(Throwable error) {
        for (BotErrorListener listener : errorListeners) {
            try {
                listener.onError(error);
            } catch (Exception ignored) {
                // 错误监听器自身异常不再传播
            }
        }
    }

    /**
     * 睡眠退避并返回下一次退避时长
     *
     * @param currentBackoff 当前退避
     * @return 下一次退避
     */
    private long sleepBackoff(long currentBackoff) {
        try {
            Thread.sleep(currentBackoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.min((long) (currentBackoff * BACKOFF_MULTIPLIER), BACKOFF_MAX_MS);
    }

    // ==================== 解析辅助 ====================

    /**
     * 合并意图位掩码
     *
     * @param values 意图数组
     * @return 合并结果，空数组返回默认意图
     */
    private static int mergeIntents(int... values) {
        if (values == null || values.length == 0) {
            return DEFAULT_INTENTS;
        }
        int merged = 0;
        for (int value : values) {
            merged |= value;
        }
        return merged;
    }

    /**
     * 解析 RFC3339 时间戳
     *
     * @param timestamp 时间戳文本
     * @return 毫秒时间戳，解析失败返回 0
     */
    private static long parseTimestamp(String timestamp) {
        if (StringUtils.isBlank(timestamp)) {
            return 0L;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 兼容秒级时间戳
        }
        try {
            return Long.parseLong(timestamp.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 平台文件类型映射领域类型
     *
     * @param fileType 平台文件类型
     * @return 消息类型
     */
    private static BotInboundMessage.Type mediaType(int fileType) {
        return switch (fileType) {
            case FILE_TYPE_IMAGE -> BotInboundMessage.Type.IMAGE;
            case FILE_TYPE_VIDEO -> BotInboundMessage.Type.VIDEO;
            case FILE_TYPE_VOICE -> BotInboundMessage.Type.VOICE;
            case FILE_TYPE_FILE -> BotInboundMessage.Type.FILE;
            default -> BotInboundMessage.Type.UNKNOWN;
        };
    }

    /**
     * 解析被 @ 成员列表
     *
     * @param mentions 原始 mentions
     * @return openid 列表
     */
    private static List<String> readMentions(Object mentions) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> mention : mapList(mentions)) {
            String id = firstNotBlank(stringValue(mention.get("member_openid")),
                    stringValue(mention.get("user_openid")),
                    stringValue(mention.get("id")));
            if (StringUtils.isNotBlank(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 取列表首个 Map 元素
     *
     * @param value 原始值
     * @return 首个元素，无则 null
     */
    private static Map<String, Object> firstMap(Object value) {
        List<Map<String, Object>> list = mapList(value);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 转 Map 列表
     *
     * @param value 原始值
     * @return Map 列表，非列表返回空列表
     */
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> maps = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                maps.add(asMap(map));
            }
        }
        return maps;
    }

    /**
     * 转 Map
     *
     * @param value 原始值
     * @return Map，非 Map 返回空 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map
                : Collections.emptyMap();
    }

    /**
     * @param value 原始值
     * @return 文本，null 返回 null
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 整数
     */
    private static int intValue(Object value, int defaultValue) {
        return (int) longValue(value, defaultValue);
    }

    /**
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 长整数
     */
    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * @param values 候选文本
     * @return 首个非空白文本，全部为空返回 null
     */
    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 取文件头部窗口字节，用于 md5_10m
     *
     * @param data 全量字节
     * @return 窗口内字节
     */
    private static byte[] headBytes(byte[] data) {
        return data.length <= MD5_HEAD_WINDOW_BYTES
                ? data : Arrays.copyOfRange(data, 0, MD5_HEAD_WINDOW_BYTES);
    }

    /**
     * 按下标与块大小切出分片
     *
     * @param data      全量字节
     * @param index     分片下标
     * @param blockSize 块大小
     * @return 分片字节
     */
    private static byte[] slice(byte[] data, int index, long blockSize) {
        int block = (int) Math.max(1L, blockSize);
        int from = Math.min(index * block, data.length);
        int to = Math.min(from + block, data.length);
        return Arrays.copyOfRange(data, from, to);
    }

    /**
     * @param value 资源地址
     * @return 是否为远程资源
     */
    private static boolean isRemoteUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * @param value 地址
     * @return 去掉结尾斜杠的地址
     */
    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 被动回复窗口
     */
    private static final class PassiveContext {

        /**
         * 入站消息 ID
         */
        private String msgId;

        /**
         * 窗口到期时间戳
         */
        private long expireAt;

        /**
         * 会话目标维度的单调回复序号，跨消息/跨窗口保持递增，避免平台 msg_seq 去重
         */
        private final AtomicInteger seq;

        private PassiveContext(String msgId, long expireAt) {
            this.msgId = msgId;
            this.expireAt = expireAt;
            // 随机起始序号，避免重启后从 1 重新计数触发平台去重
            this.seq = new AtomicInteger(1 + ThreadLocalRandom.current().nextInt(0x10000));
        }

        /**
         * 更新为新的入站消息与窗口，保留既有单调序号
         *
         * @param newMsgId    新入站消息 ID
         * @param newExpireAt 新窗口到期时间戳
         */
        private void renew(String newMsgId, long newExpireAt) {
            this.msgId = newMsgId;
            this.expireAt = newExpireAt;
        }
    }

    /**
     * 平台错误异常
     */
    private static final class QqApiException extends RuntimeException {

        /**
         * 序列化标识
         */
        private static final long serialVersionUID = 1L;

        /**
         * 平台错误码
         */
        private final int code;

        private QqApiException(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * @return 平台错误码
         */
        private int getCode() {
            return code;
        }
    }
}
