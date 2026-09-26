package com.chua.dingding.support.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 钉钉机器人客户端，实现 {@link BotClient}。
 * <p>
 * 接收侧默认走 <b>Stream 模式</b>（长连接回传），无需公网服务器、无需回调地址与域名白名单：
 * 以 {@code clientId/clientSecret}（即应用的 AppKey/AppSecret）调用
 * {@code POST /v1.0/gateway/connections/open} 换取 {@code endpoint + ticket}，
 * 再连 {@code endpoint?ticket=…}；下行帧分 SYSTEM / EVENT / CALLBACK 三类，
 * SYSTEM 的 ping 须原样回带 {@code opaque}，CALLBACK 须回 200 应答否则平台会重推。
 * </p>
 * <p>
 * 发送侧按优先级择优：入站消息自带的 {@code sessionWebhook}（被动回复）→
 * 开放平台主动消息（{@code /v1.0/robot/oToMessages/batchSend} 单聊、
 * {@code /v1.0/robot/groupMessages/send} 群聊，需 access_token）→
 * 旧版自定义机器人 Webhook（{@code oapi.dingtalk.com/robot/send}，支持加签）。
 * </p>
 * <pre>{@code
 * DingTalkBotClient client = new DingTalkBotClient();
 * client.configure(clientId, clientSecret, null);
 * client.addMessageListener(msg -> client.sendText(msg.getFromUser(), "已收到"));
 * client.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DingTalkBotClient implements BotClient {

    /**
     * 开放平台 API 域名
     */
    private static final String DEFAULT_API_BASE_URL = "https://api.dingtalk.com";

    /**
     * 旧版开放平台域名，自定义机器人 Webhook 与素材上传在此域名下
     */
    private static final String DEFAULT_LEGACY_BASE_URL = "https://oapi.dingtalk.com";

    /**
     * Stream 模式建连地址，响应体给出 wss 接入点与一次性 ticket
     */
    private static final String STREAM_OPEN_PATH = "/v1.0/gateway/connections/open";

    /**
     * access_token 换取地址
     */
    private static final String ACCESS_TOKEN_PATH = "/v1.0/oauth2/accessToken";

    /**
     * 单聊主动消息地址
     */
    private static final String OTO_MESSAGE_PATH = "/v1.0/robot/oToMessages/batchSend";

    /**
     * 群聊主动消息地址
     */
    private static final String GROUP_MESSAGE_PATH = "/v1.0/robot/groupMessages/send";

    /**
     * 消息文件下载地址，downloadCode 换取临时 downloadUrl
     */
    private static final String MEDIA_DOWNLOAD_PATH = "/v1.0/robot/messageFiles/download";

    /**
     * 素材上传地址，返回 media_id
     */
    private static final String MEDIA_UPLOAD_PATH = "/media/upload";

    /**
     * 自定义机器人 Webhook 路径
     */
    private static final String ROBOT_SEND_PATH = "/robot/send?access_token=";

    /**
     * 机器人收消息的订阅主题
     */
    private static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    /**
     * 订阅类型：回调
     */
    private static final String SUBSCRIPTION_CALLBACK = "CALLBACK";

    /**
     * 下行帧类型：系统
     */
    private static final String FRAME_SYSTEM = "SYSTEM";

    /**
     * 下行帧类型：事件
     */
    private static final String FRAME_EVENT = "EVENT";

    /**
     * 下行帧类型：回调
     */
    private static final String FRAME_CALLBACK = "CALLBACK";

    /**
     * 系统帧 ping 主题，客户端须回带 opaque
     */
    private static final String SYSTEM_TOPIC_PING = "ping";

    /**
     * 系统帧 disconnect 主题，收到后须换新 ticket 重连
     */
    private static final String SYSTEM_TOPIC_DISCONNECT = "disconnect";

    /**
     * 回调应答的空响应体，协议要求 data 是 JSON 字符串而非对象
     */
    private static final String ACK_DATA_NULL = "{\"response\":null}";

    /**
     * 帧协议版本
     */
    private static final String SPEC_VERSION = "1.0";

    /**
     * 帧内容类型
     */
    private static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * 建连上报的客户端标识
     */
    private static final String USER_AGENT = "chua-dingtalk-stream/1.0";

    /**
     * 建连上报的占位地址，钉钉仅用于统计
     */
    private static final String LOCAL_IP = "127.0.0.1";

    /**
     * 文本消息模板键
     */
    private static final String MSG_KEY_TEXT = "sampleText";

    /**
     * 图片消息模板键
     */
    private static final String MSG_KEY_IMAGE = "sampleImageMsg";

    /**
     * 文件消息模板键
     */
    private static final String MSG_KEY_FILE = "sampleFile";

    /**
     * 令牌提前刷新毫秒数，避开到期临界点
     */
    private static final long TOKEN_REFRESH_AHEAD_MS = 60_000L;

    /**
     * 换取令牌失败时的兜底有效期毫秒数
     */
    private static final long TOKEN_FALLBACK_EXPIRES_MS = 7_200_000L;

    /**
     * 长连接中断后的初始退避毫秒数
     */
    private static final long BACKOFF_INITIAL_MS = 1_000L;

    /**
     * 长连接中断后的最大退避毫秒数
     */
    private static final long BACKOFF_MAX_MS = 30_000L;

    /**
     * 空闲检测轮询间隔毫秒数
     */
    private static final long IDLE_CHECK_INTERVAL_MS = 30_000L;

    /**
     * 默认空闲超时毫秒数，超时未收到任何下行帧即重连
     */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 300_000L;

    /**
     * 正常关闭码
     */
    private static final int CLOSE_CODE_NORMAL = 1000;

    /**
     * 会话类型：群聊
     */
    private static final String CONVERSATION_GROUP = "2";

    /**
     * HMAC SHA256 算法名称
     */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 多部分表单边界前缀
     */
    private static final String BOUNDARY_PREFIX = "----chuaDingTalk";

    /**
     * 应用凭证 ID，即钉钉 AppKey / Client ID
     */
    private String clientId;

    /**
     * 应用凭证密钥，即钉钉 AppSecret / Client Secret
     */
    private String clientSecret;

    /**
     * 机器人编码，缺省与 clientId 相同
     */
    private String robotCode;

    /**
     * 开放平台 API 域名
     */
    private String apiBaseUrl = DEFAULT_API_BASE_URL;

    /**
     * 旧版域名，自定义机器人 Webhook 与素材上传使用
     */
    private String legacyBaseUrl = DEFAULT_LEGACY_BASE_URL;

    /**
     * 旧版自定义机器人 Webhook 完整地址
     */
    private String webhookUrl;

    /**
     * 旧版自定义机器人加签密钥
     */
    private String webhookSignSecret;

    /**
     * Webhook 回调验证令牌，非空即以 Webhook 接收模式启动
     */
    private String webhookVerifyToken;

    /**
     * 订阅主题列表
     */
    private final List<Map<String, Object>> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * 连接超时时间（毫秒）
     */
    private long connectTimeoutMillis = 10_000L;

    /**
     * 读取超时时间（毫秒）
     */
    private long readTimeoutMillis = 30_000L;

    /**
     * 空闲超时时间（毫秒）
     */
    private long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MS;

    /**
     * 配置保存或加载器
     */
    private ConfigSaveOrLoader configSaveOrLoader;

    /**
     * 运行状态标识
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 是否允许断线重连
     */
    private volatile boolean reconnectAllowed;

    /**
     * 是否以 Stream 长连接模式运行
     */
    private volatile boolean streamMode;

    /**
     * 是否以 Webhook 接收模式运行
     */
    private volatile boolean webhookMode;

    /**
     * 网关下发的 wss 接入点
     */
    private volatile String gatewayEndpoint;

    /**
     * HTTP 客户端
     */
    private volatile HttpClient httpClient;

    /**
     * 长连接实例
     */
    private volatile WebSocket webSocket;

    /**
     * 长连接主循环线程
     */
    private volatile Thread wsThread;

    /**
     * 会话结束闩
     */
    private volatile CountDownLatch sessionLatch;

    /**
     * 帧发送串行化锁，WebSocket 不允许并发写
     */
    private final Object sendLock = new Object();

    /**
     * 缓存的 access_token
     */
    private volatile String accessToken;

    /**
     * access_token 过期时间戳
     */
    private volatile long tokenExpireAt;

    /**
     * 最近一次收到下行帧的时间戳
     */
    private final AtomicLong lastFrameAt = new AtomicLong();

    /**
     * 空闲检测调度器
     */
    private volatile ScheduledExecutorService scheduler;

    /**
     * 空闲检测任务
     */
    private volatile ScheduledFuture<?> idleTask;

    /**
     * 入站派发线程，单线程保证同会话消息有序
     */
    private volatile ExecutorService inboundExecutor;

    /**
     * 被动回复窗口，键为会话 ID 与发送者 ID
     */
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    /**
     * 观测到的群会话
     */
    private final Map<String, BotGroupInfo> observedGroups = new ConcurrentHashMap<>();

    /**
     * 观测到的群成员
     */
    private final Map<String, Set<String>> observedGroupMembers = new ConcurrentHashMap<>();

    /**
     * 消息监听器列表
     */
    private final List<BotMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 错误监听器列表
     */
    private final List<BotErrorListener> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * 用户存储实例
     */
    private BotUserStore userStore = new InMemoryBotUserStore();

    /**
     * 构造客户端，默认订阅机器人收消息主题
     */
    public DingTalkBotClient() {
        subscriptions.add(subscription(SUBSCRIPTION_CALLBACK, BOT_MESSAGE_TOPIC));
    }

    // ==================== 配置 ====================

    /**
     * 配置应用凭证
     *
     * @param token          应用 clientId（AppKey）
     * @param secret         应用 clientSecret（AppSecret）
     * @param encodingAesKey 钉钉不使用 AES 密钥，忽略
     * @return this
     */
    @Override
    public BotClient configure(String token, String secret, String encodingAesKey) {
        return token(token).secret(secret);
    }

    /**
     * 设置应用凭证 ID（AppKey / Client ID）
     *
     * @param token 应用凭证 ID
     * @return this
     */
    @Override
    public BotClient token(String token) {
        if (StringUtils.isNotBlank(token)) {
            this.clientId = token;
        }
        return this;
    }

    /**
     * 设置应用凭证密钥（AppSecret / Client Secret）
     *
     * @param secret 应用凭证密钥
     * @return this
     */
    @Override
    public BotClient secret(String secret) {
        if (StringUtils.isNotBlank(secret)) {
            this.clientSecret = secret;
        }
        return this;
    }

    /**
     * 钉钉无对称加解密密钥需求，保留接口占位
     *
     * @param encodingAesKey 编码 aes 键
     * @return this
     */
    @Override
    public BotClient encodingAesKey(String encodingAesKey) {
        return this;
    }

    /**
     * 设置开放平台 API 域名
     *
     * @param baseUrl 域名根，不含路径
     * @return this
     */
    @Override
    public BotClient baseUrl(String baseUrl) {
        if (StringUtils.isNotBlank(baseUrl)) {
            this.apiBaseUrl = trimTrailingSlash(baseUrl);
        }
        return this;
    }

    /**
     * 设置连接超时时间
     *
     * @param connectTimeoutMillis 连接超时毫秒数
     * @return this
     */
    @Override
    public BotClient connectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    /**
     * 设置读取超时时间
     *
     * @param readTimeoutMillis 读取超时毫秒数
     * @return this
     */
    @Override
    public BotClient readTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
        return this;
    }

    /**
     * 设置配置保存或加载器
     *
     * @param configSaveOrLoader 配置保存或加载器
     * @return this
     */
    @Override
    public BotClient configSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
        return this;
    }

    /**
     * 设置机器人编码，主动消息必填，缺省取 clientId
     *
     * @param robotCode 机器人编码
     * @return this
     */
    public DingTalkBotClient robotCode(String robotCode) {
        this.robotCode = robotCode;
        return this;
    }

    /**
     * 设置旧版域名，自定义机器人 Webhook 与素材上传使用
     *
     * @param legacyBaseUrl 域名根，不含路径
     * @return this
     */
    public DingTalkBotClient legacyBaseUrl(String legacyBaseUrl) {
        if (StringUtils.isNotBlank(legacyBaseUrl)) {
            this.legacyBaseUrl = trimTrailingSlash(legacyBaseUrl);
        }
        return this;
    }

    /**
     * 设置旧版自定义机器人 Webhook 完整地址
     *
     * @param webhookUrl Webhook 地址
     * @return this
     */
    public DingTalkBotClient webhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        return this;
    }

    /**
     * 以 access_token 拼装旧版自定义机器人 Webhook 地址
     *
     * @param webhookAccessToken 自定义机器人的 access_token
     * @return this
     */
    public DingTalkBotClient webhookAccessToken(String webhookAccessToken) {
        if (StringUtils.isNotBlank(webhookAccessToken)) {
            this.webhookUrl = legacyBaseUrl + ROBOT_SEND_PATH + webhookAccessToken;
        }
        return this;
    }

    /**
     * 设置旧版自定义机器人加签密钥
     *
     * @param signSecret 以 SEC 开头的加签密钥
     * @return this
     */
    public DingTalkBotClient webhookSignSecret(String signSecret) {
        this.webhookSignSecret = signSecret;
        return this;
    }

    /**
     * 设置 Webhook 回调验证令牌，非空时以 Webhook 接收模式启动
     *
     * @param webhookVerifyToken 验证令牌
     * @return this
     */
    public DingTalkBotClient webhookVerifyToken(String webhookVerifyToken) {
        this.webhookVerifyToken = webhookVerifyToken;
        return this;
    }

    /**
     * 覆盖订阅主题，须在 {@code start()} 前调用
     *
     * @param topics 主题列表，为空时回落到机器人收消息默认主题
     * @return this
     */
    public DingTalkBotClient topics(List<String> topics) {
        subscriptions.clear();
        if (topics == null || topics.isEmpty()) {
            subscriptions.add(subscription(SUBSCRIPTION_CALLBACK, BOT_MESSAGE_TOPIC));
            return this;
        }
        for (String topic : topics) {
            if (StringUtils.isNotBlank(topic)) {
                subscriptions.add(subscription(SUBSCRIPTION_CALLBACK, topic));
            }
        }
        return this;
    }

    /**
     * 设置空闲超时毫秒数，超时未收到任何下行帧即主动重连
     *
     * @param idleTimeoutMillis 空闲超时毫秒数
     * @return this
     */
    public DingTalkBotClient idleTimeoutMillis(long idleTimeoutMillis) {
        if (idleTimeoutMillis > 0) {
            this.idleTimeoutMillis = idleTimeoutMillis;
        }
        return this;
    }

    /**
     * @return 已订阅主题列表
     */
    public List<String> getTopics() {
        List<String> topics = new ArrayList<>();
        for (Map<String, Object> item : subscriptions) {
            topics.add(stringValue(item.get("topic")));
        }
        return topics;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动客户端：具备应用凭证即走 Stream 长连接，否则退化为 Webhook 模式
     *
     * @return this
     */
    @Override
    public BotClient start() {
        boolean credentialReady = hasCredentials();
        boolean webhookReady = StringUtils.isNotBlank(webhookVerifyToken)
                || StringUtils.isNotBlank(webhookUrl);
        if (!credentialReady && !webhookReady) {
            throw new IllegalStateException("钉钉机器人需要 clientId/clientSecret（Stream 模式）"
                    + "或 webhookUrl / webhookVerifyToken（Webhook 模式）");
        }
        if (!running.compareAndSet(false, true)) {
            return this;
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        this.reconnectAllowed = true;
        this.streamMode = credentialReady;
        this.webhookMode = !credentialReady && StringUtils.isNotBlank(webhookVerifyToken);
        if (streamMode) {
            startDispatch();
            startWebSocketLoop();
            log.info("钉钉机器人以 Stream 长连接模式启动, clientId={}, apiBaseUrl={}",
                    mask(clientId), apiBaseUrl);
        } else {
            log.info("钉钉机器人以 Webhook 模式启动, 接收={}, 发送={}", this.webhookMode,
                    StringUtils.isNotBlank(webhookUrl));
        }
        return this;
    }

    /**
     * 停止客户端并释放长连接
     */
    @Override
    public void stop() {
        running.set(false);
        reconnectAllowed = false;
        stopIdleWatchdog();
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
        ExecutorService inbound = inboundExecutor;
        inboundExecutor = null;
        if (inbound != null) {
            inbound.shutdownNow();
        }
        CountDownLatch latch = sessionLatch;
        sessionLatch = null;
        if (latch != null) {
            latch.countDown();
        }
        this.accessToken = null;
        this.tokenExpireAt = 0L;
        this.gatewayEndpoint = null;
        log.info("钉钉机器人已停止");
    }

    /**
     * @return 客户端是否已启动
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * @return 是否以 Stream 长连接模式运行
     */
    public boolean isStreamMode() {
        return streamMode;
    }

    /**
     * @return 是否以 Webhook 接收模式运行
     */
    public boolean isWebhookMode() {
        return webhookMode;
    }

    /**
     * @return 长连接是否已建立
     */
    public boolean isWebSocketConnected() {
        WebSocket ws = webSocket;
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    /**
     * @return 网关下发的 wss 接入点，未连接时为 null
     */
    public String getGatewayEndpoint() {
        return gatewayEndpoint;
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

    // ==================== Stream 长连接 ====================

    /**
     * 启动入站派发线程，监听器里的耗时操作不得占用 WebSocket 读线程
     */
    private void startDispatch() {
        ExecutorService executor = inboundExecutor;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dingtalk-bot-inbound");
                thread.setDaemon(true);
                return thread;
            });
            inboundExecutor = executor;
        }
    }

    /**
     * 启动长连接线程
     */
    private void startWebSocketLoop() {
        Thread thread = new Thread(this::runWebSocketLoop, "dingtalk-bot-ws");
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
                log.warn("钉钉机器人长连接中断, {}ms 后重试: {}", backoff, e.getMessage());
                backoff = sleepBackoff(backoff);
            }
        }
    }

    /**
     * 建立一次会话：换取一次性 ticket → 建连
     *
     * @throws Exception 建连失败
     */
    private void openSession() throws Exception {
        JsonObject body = new JsonObject()
                .fluent("clientId", clientId)
                .fluent("clientSecret", clientSecret)
                .fluent("ua", USER_AGENT)
                .fluent("localIp", LOCAL_IP);
        body.put("subscriptions", new ArrayList<>(subscriptions));
        Map<String, Object> result = api(STREAM_OPEN_PATH, body, false);
        String endpoint = stringValue(result.get("endpoint"));
        String ticket = stringValue(result.get("ticket"));
        if (StringUtils.isBlank(endpoint) || StringUtils.isBlank(ticket)) {
            throw new DingTalkApiException(-1,
                    "Stream 建连响应缺少 endpoint/ticket: " + Json.toJson(result));
        }
        this.gatewayEndpoint = endpoint;
        String url = normalizeEndpoint(endpoint) + (endpoint.contains("?") ? "&" : "?")
                + "ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8.name());
        WebSocket ws = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .buildAsync(URI.create(url), new DingTalkWebSocketListener())
                .get(connectTimeoutMillis, TimeUnit.MILLISECONDS);
        webSocket = ws;
        lastFrameAt.set(System.currentTimeMillis());
        startIdleWatchdog();
        log.info("钉钉机器人长连接已建立, endpoint={}", endpoint);
    }

    /**
     * 把接入点规范为可拨号的 WebSocket 地址。JDK 的 WebSocket 只接受 ws/wss，而平台可能回
     * https 形式的网关地址（官方 SDK 用 OkHttp，两种都能连）。
     *
     * @param endpoint 平台下发的接入点
     * @return ws/wss 地址
     */
    static String normalizeEndpoint(String endpoint) {
        String url = endpoint.trim();
        if (url.startsWith("https://")) {
            return "wss://" + url.substring("https://".length());
        }
        if (url.startsWith("http://")) {
            return "ws://" + url.substring("http://".length());
        }
        if (url.startsWith("ws://") || url.startsWith("wss://")) {
            return url;
        }
        return "wss://" + url;
    }

    /**
     * 长连接监听器
     */
    private class DingTalkWebSocketListener implements WebSocket.Listener {

        /**
         * 分片文本缓冲
         */
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            lastFrameAt.set(System.currentTimeMillis());
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                lastFrameAt.set(System.currentTimeMillis());
                try {
                    handleFrame(frame);
                } catch (Exception e) {
                    notifyError(e);
                    log.error("钉钉机器人处理下行帧失败: {}", e.getMessage(), e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            webSocket = null;
            stopIdleWatchdog();
            log.warn("钉钉机器人长连接关闭(关闭码 {}): {}", statusCode, reason);
            endSession();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            webSocket = null;
            stopIdleWatchdog();
            notifyError(error);
            log.error("钉钉机器人长连接异常: {}", error.getMessage());
            endSession();
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            return ws.sendPong(message);
        }
    }

    /**
     * 处理一条下行帧
     *
     * @param rawFrame 帧原文
     */
    private void handleFrame(String rawFrame) {
        if (StringUtils.isBlank(rawFrame)) {
            return;
        }
        JsonObject frame = objectValue(rawFrame);
        if (frame == null) {
            return;
        }
        JsonObject headers = objectValue(frame.get("headers"));
        String topic = headers == null ? null : stringValue(headers.get("topic"));
        String type = stringValue(frame.get("type"));
        Object data = frame.get("data");
        if (FRAME_SYSTEM.equalsIgnoreCase(type)) {
            onSystemFrame(headers, topic, data);
        } else if (FRAME_CALLBACK.equalsIgnoreCase(type) || FRAME_EVENT.equalsIgnoreCase(type)) {
            onBusinessFrame(type, headers, topic, data);
        } else {
            log.debug("钉钉机器人忽略下行帧 type={}, topic={}", type, topic);
        }
    }

    /**
     * 处理系统帧：ping 回带 opaque，disconnect 换新 ticket 重连
     *
     * @param headers 帧头
     * @param topic   帧主题
     * @param data    帧体
     */
    private void onSystemFrame(JsonObject headers, String topic, Object data) {
        String normalized = normalizeTopic(topic);
        if (SYSTEM_TOPIC_PING.equals(normalized)) {
            JsonObject payload = objectValue(data);
            JsonObject reply = new JsonObject();
            if (payload != null && payload.containsKey("opaque")) {
                reply.put("opaque", payload.get("opaque"));
            }
            sendAck(FRAME_SYSTEM, headers, Json.toJson(reply));
            return;
        }
        if (SYSTEM_TOPIC_DISCONNECT.equals(normalized)) {
            log.info("钉钉机器人收到 disconnect 指令, 断开后换取新 ticket 重连");
            closeCurrentSocket("disconnect");
        }
    }

    /**
     * 处理回调与事件帧，回 200 应答避免平台重推
     *
     * @param type    帧类型
     * @param headers 帧头
     * @param topic   帧主题
     * @param data    帧体
     */
    private void onBusinessFrame(String type, JsonObject headers, String topic, Object data) {
        sendAck(type, headers, ACK_DATA_NULL);
        if (!normalizeTopic(BOT_MESSAGE_TOPIC).equals(normalizeTopic(topic))) {
            log.debug("钉钉机器人收到非机器人消息帧 topic={}", topic);
            return;
        }
        JsonObject payload = objectValue(data);
        if (payload == null) {
            log.warn("钉钉机器人消息帧 data 解析失败: {}", data);
            return;
        }
        ExecutorService executor = inboundExecutor;
        if (executor == null) {
            dispatchInbound(payload);
            return;
        }
        executor.execute(() -> {
            try {
                dispatchInbound(payload);
            } catch (Exception e) {
                notifyError(e);
                log.error("钉钉机器人派发入站消息失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 回写一帧应答
     *
     * @param type    帧类型，与请求帧一致
     * @param headers 请求帧头，messageId 须原样回带
     * @param data    应答体 JSON 字符串
     */
    private void sendAck(String type, JsonObject headers, String data) {
        JsonObject echo = new JsonObject();
        if (headers != null) {
            echo.putAll(headers);
        }
        echo.putIfAbsent("contentType", CONTENT_TYPE_JSON);
        JsonObject frame = new JsonObject()
                .fluent("code", 200)
                .fluent("message", "OK")
                .fluent("data", data)
                .fluent("type", type)
                .fluent("specVersion", SPEC_VERSION);
        frame.put("headers", echo);
        writeFrame(frame);
    }

    /**
     * 串行写出帧，WebSocket 并发写会抛异常
     *
     * @param frame 帧内容
     */
    private void writeFrame(JsonObject frame) {
        WebSocket ws = webSocket;
        if (ws == null) {
            log.debug("钉钉机器人长连接未就绪, 丢弃应答帧");
            return;
        }
        String text = Json.toJson(frame);
        synchronized (sendLock) {
            try {
                ws.sendText(text, true);
            } catch (Exception e) {
                log.warn("钉钉机器人写出应答帧失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 关闭当前连接并唤醒主循环重连
     *
     * @param reason 关闭原因
     */
    private void closeCurrentSocket(String reason) {
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(CLOSE_CODE_NORMAL, reason);
            } catch (Exception ignored) {
                // 连接已断开，无需处理
            }
        }
        stopIdleWatchdog();
        endSession();
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
     * 启动空闲检测，超时未收到任何下行帧即重连
     */
    private void startIdleWatchdog() {
        stopIdleWatchdog();
        ScheduledExecutorService executor = scheduler;
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dingtalk-bot-idle");
                thread.setDaemon(true);
                return thread;
            });
            scheduler = executor;
        }
        idleTask = executor.scheduleWithFixedDelay(this::checkIdle, IDLE_CHECK_INTERVAL_MS,
                IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止空闲检测
     */
    private void stopIdleWatchdog() {
        ScheduledFuture<?> task = idleTask;
        idleTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 空闲检测单次执行
     */
    private void checkIdle() {
        if (!running.get() || webSocket == null) {
            return;
        }
        if (System.currentTimeMillis() - lastFrameAt.get() < idleTimeoutMillis) {
            return;
        }
        log.warn("钉钉机器人长连接 {}ms 无下行帧, 主动重连", idleTimeoutMillis);
        closeCurrentSocket("idle");
    }

    /**
     * 退避等待
     *
     * @param backoff 当前退避毫秒数
     * @return 下一次退避毫秒数
     */
    private long sleepBackoff(long backoff) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.min(backoff * 2, BACKOFF_MAX_MS);
    }

    // ==================== 入站映射 ====================

    /**
     * 处理 HTTP 回调推送的机器人消息，Webhook 接收模式下由业务侧调用
     *
     * @param jsonBody 请求体 JSON 字符串
     * @return true 表示已解析并派发
     */
    public boolean handleCallback(String jsonBody) {
        JsonObject payload = objectValue(jsonBody);
        if (payload == null) {
            return false;
        }
        try {
            return dispatchInbound(payload);
        } catch (Exception e) {
            notifyError(e);
            log.error("钉钉机器人处理回调失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 记录会话上下文并派发给监听器
     *
     * @param payload 机器人消息体
     * @return true 表示已派发
     */
    private boolean dispatchInbound(JsonObject payload) {
        String conversationId = stringValue(payload.get("conversationId"));
        String senderId = firstNonBlank(stringValue(payload.get("senderStaffId")),
                stringValue(payload.get("senderId")));
        boolean fromGroup = isGroupConversation(payload);
        rememberSession(conversationId, senderId, stringValue(payload.get("sessionWebhook")),
                longValue(payload.get("sessionWebhookExpiredTime")), fromGroup);
        rememberConversation(senderId, stringValue(payload.get("senderNick")), conversationId,
                fromGroup);
        BotInboundMessage inbound = parseInbound(payload);
        if (inbound == null) {
            return false;
        }
        for (BotMessageListener listener : messageListeners) {
            try {
                listener.onMessage(inbound);
            } catch (Exception e) {
                notifyError(e);
            }
        }
        return true;
    }

    /**
     * 将钉钉机器人消息体映射为统一入站消息
     *
     * @param data 消息体
     * @return 统一入站消息，无法识别时返回 null
     */
    private BotInboundMessage parseInbound(JsonObject data) {
        String msgType = stringValue(data.get("msgtype"));
        if (StringUtils.isBlank(msgType)) {
            return null;
        }
        JsonObject text = objectValue(data.get("text"));
        JsonObject content = objectValue(data.get("content"));
        String senderId = firstNonBlank(stringValue(data.get("senderStaffId")),
                stringValue(data.get("senderId")));
        String robot = stringValue(data.get("robotCode"));
        boolean fromGroup = isGroupConversation(data);
        String message = text == null ? null : trimToNull(stringValue(text.get("content")));
        String downloadCode = content == null ? null
                : trimToNull(stringValue(content.get("downloadCode")));
        List<String> mentioned = mentionedIds(data);
        Boolean inAtList = booleanValue(data.get("isInAtList"));
        BotInboundMessage.BotInboundMessageBuilder builder = BotInboundMessage.builder()
                .msgId(stringValue(data.get("msgId")))
                .type(mapMsgType(msgType))
                .content(message)
                .fromUser(senderId)
                .fromUserName(stringValue(data.get("senderNick")))
                .toUser(robot)
                .createTime(longValueOrDefault(data.get("createAt"), System.currentTimeMillis()))
                .chatId(stringValue(data.get("conversationId")))
                .fromGroup(fromGroup)
                .mentionedBot(inAtList != null ? inAtList : fromGroup)
                .rawFields(new LinkedHashMap<>(data));
        if (!mentioned.isEmpty()) {
            builder.mentionedList(mentioned);
        }
        if (downloadCode != null) {
            builder.mediaId(downloadCode).mediaUrl(resolveDownloadUrl(downloadCode, robot));
        }
        return builder.build();
    }

    /**
     * 取消息中被 @ 的用户标识列表
     *
     * @param data 消息体
     * @return 被 @ 的用户标识列表
     */
    private List<String> mentionedIds(JsonObject data) {
        Object atUsers = data.get("atUsers");
        if (!(atUsers instanceof List)) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : (List<?>) atUsers) {
            JsonObject at = objectValue(item);
            String dingtalkId = at == null ? null : stringValue(at.get("dingtalkId"));
            if (StringUtils.isNotBlank(dingtalkId)) {
                ids.add(dingtalkId);
            }
        }
        return ids;
    }

    /**
     * downloadCode 换取临时下载地址，失败不影响消息派发
     *
     * @param downloadCode 下载码
     * @param robotCode    机器人编码
     * @return 临时下载地址，失败返回 null
     */
    private String resolveDownloadUrl(String downloadCode, String robotCode) {
        if (!hasCredentials()) {
            return null;
        }
        try {
            JsonObject body = new JsonObject()
                    .fluent("robotCode", firstNonBlank(robotCode, clientId))
                    .fluent("downloadCode", downloadCode);
            Map<String, Object> result = api(MEDIA_DOWNLOAD_PATH, body, true);
            return stringValue(result.get("downloadUrl"));
        } catch (Exception e) {
            log.warn("钉钉机器人换取下载地址失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 映射钉钉消息类型为统一类型
     *
     * @param msgType 钉钉消息类型
     * @return 统一消息类型
     */
    private Type mapMsgType(String msgType) {
        switch (msgType.toLowerCase(Locale.ROOT)) {
            case "text":
            case "richtext":
                return Type.TEXT;
            case "picture":
                return Type.IMAGE;
            case "audio":
                return Type.VOICE;
            case "video":
                return Type.VIDEO;
            case "file":
                return Type.FILE;
            default:
                return Type.UNKNOWN;
        }
    }

    /**
     * 记录被动回复窗口
     *
     * @param conversationId 会话 ID
     * @param senderId       发送者 ID
     * @param sessionWebhook 被动回复地址
     * @param expireAt       被动回复过期时间戳
     * @param fromGroup      是否群聊
     */
    private void rememberSession(String conversationId, String senderId, String sessionWebhook,
                                 long expireAt, boolean fromGroup) {
        if (StringUtils.isBlank(sessionWebhook)) {
            return;
        }
        SessionContext context = new SessionContext(sessionWebhook, expireAt, fromGroup,
                conversationId);
        if (StringUtils.isNotBlank(conversationId)) {
            sessions.put(conversationId, context);
        }
        if (StringUtils.isNotBlank(senderId)) {
            sessions.put(senderId, context);
        }
    }

    /**
     * 记录长连接期间观测到的会话与成员，钉钉未提供群列表接口
     *
     * @param userId    发送者 ID
     * @param userName  发送者昵称
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

    // ==================== 出站发送 ====================

    /**
     * 发送文本，优先使用入站消息自带的被动回复地址
     *
     * @param toUser  接收者 ID 或会话 ID
     * @param content 文本内容
     * @return 发送结果
     */
    @Override
    public BotSendResult sendText(String toUser, String content) {
        JsonObject text = new JsonObject().fluent("content", content);
        return deliver(toUser, false, new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", text), MSG_KEY_TEXT, text);
    }

    /**
     * 发送图片，参数可为 https 图片地址或本地文件路径
     *
     * @param toUser    接收者 ID 或会话 ID
     * @param mediaPath 图片地址或本地路径
     * @return 发送结果
     */
    @Override
    public BotSendResult sendImage(String toUser, String mediaPath) {
        if (isUrl(mediaPath)) {
            JsonObject image = new JsonObject().fluent("picURL", mediaPath);
            return deliver(toUser, false, new JsonObject()
                    .fluent("msgtype", "image")
                    .fluent("image", image), MSG_KEY_IMAGE,
                    new JsonObject().fluent("photoURL", mediaPath));
        }
        if (!hasCredentials()) {
            return BotSendResult.fail(-1, "自定义机器人 Webhook 不支持本地图片，"
                    + "请配置 clientId/clientSecret 走开放平台，或改用 https 图片地址");
        }
        try {
            String mediaId = uploadMedia(mediaPath, "image");
            return deliver(toUser, false, null, MSG_KEY_IMAGE,
                    new JsonObject().fluent("photoURL", mediaId));
        } catch (Exception e) {
            return failure("图片", e);
        }
    }

    /**
     * 发送语音，钉钉语音模板要求音频时长，客户端无法从文件推导，暂不支持
     *
     * @param toUser    接收者 ID 或会话 ID
     * @param mediaPath 语音文件路径
     * @return 发送结果
     */
    @Override
    public BotSendResult sendVoice(String toUser, String mediaPath) {
        return BotSendResult.fail(-1, "钉钉机器人语音消息需音频时长, 客户端无法从文件推导, 暂不支持");
    }

    /**
     * 发送视频，钉钉视频模板要求封面素材，客户端不生成封面，暂不支持
     *
     * @param toUser    接收者 ID 或会话 ID
     * @param mediaPath 视频文件路径
     * @param title     标题
     * @param desc      描述
     * @return 发送结果
     */
    @Override
    public BotSendResult sendVideo(String toUser, String mediaPath, String title, String desc) {
        return BotSendResult.fail(-1, "钉钉机器人视频消息需封面素材, 客户端不生成封面, 暂不支持");
    }

    /**
     * 发送文件，先上传素材再以文件模板下发
     *
     * @param toUser    接收者 ID 或会话 ID
     * @param mediaPath 本地文件路径
     * @return 发送结果
     */
    @Override
    public BotSendResult sendFile(String toUser, String mediaPath) {
        if (!hasCredentials()) {
            return BotSendResult.fail(-1, "自定义机器人 Webhook 不支持文件消息, "
                    + "请配置 clientId/clientSecret 走开放平台");
        }
        try {
            String mediaId = uploadMedia(mediaPath, "file");
            JsonObject file = new JsonObject()
                    .fluent("mediaId", mediaId)
                    .fluent("fileName", Paths.get(mediaPath).getFileName().toString())
                    .fluent("fileSize", String.valueOf(Files.size(Paths.get(mediaPath))));
            return deliver(toUser, false, null, MSG_KEY_FILE, file);
        } catch (Exception e) {
            return failure("文件", e);
        }
    }

    /**
     * 按消息类型分发发送
     *
     * @param message 出站消息
     * @return 发送结果
     */
    @Override
    public BotSendResult send(BotOutboundMessage message) {
        Type type = message.getType();
        if (type == null) {
            return BotSendResult.fail(-1, "消息类型必填");
        }
        String toUser = message.getToUser();
        List<String> mentioned = message.getMentionedUsers();
        switch (type) {
            case TEXT:
                if (message.isToGroup()) {
                    return mentioned == null || mentioned.isEmpty()
                            ? sendToGroup(toUser, message.getContent())
                            : sendToGroupMention(toUser, message.getContent(), mentioned);
                }
                return sendText(toUser, message.getContent());
            case IMAGE:
                return sendImage(toUser, message.getMediaPath());
            case VOICE:
                return sendVoice(toUser, message.getMediaPath());
            case VIDEO:
                return sendVideo(toUser, message.getMediaPath(), message.getTitle(),
                        message.getDescription());
            case FILE:
                return sendFile(toUser, message.getMediaPath());
            default:
                return BotSendResult.fail(-1, "不支持的消息类型: " + type);
        }
    }

    /**
     * 发送群文本消息
     *
     * @param groupId 群会话 ID
     * @param content 文本内容
     * @return 发送结果
     */
    @Override
    public BotSendResult sendToGroup(String groupId, String content) {
        JsonObject text = new JsonObject().fluent("content", content);
        return deliver(groupId, true, new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", text), MSG_KEY_TEXT, text);
    }

    /**
     * 发送群文本消息并 @ 指定成员
     *
     * @param groupId          群会话 ID
     * @param content          文本内容
     * @param mentionedUserIds 被 @ 的成员 ID
     * @return 发送结果
     */
    @Override
    public BotSendResult sendToGroupMention(String groupId, String content,
                                            List<String> mentionedUserIds) {
        JsonObject text = new JsonObject()
                .fluent("content", withMention(content, mentionedUserIds));
        JsonObject at = new JsonObject()
                .fluent("atUserIds", mentionedUserIds == null ? Collections.emptyList()
                        : mentionedUserIds)
                .fluent("isAtAll", false);
        return deliver(groupId, true, new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", text)
                .fluent("at", at), MSG_KEY_TEXT, text);
    }

    /**
     * 文本发送（异步）
     *
     * @param toUser  接收者 ID 或会话 ID
     * @param content 文本内容
     * @return 异步发送结果
     */
    @Override
    public CompletableFuture<BotSendResult> sendTextAsync(String toUser, String content) {
        return CompletableFuture.supplyAsync(() -> sendText(toUser, content));
    }

    /**
     * 图片发送（异步）
     *
     * @param toUser    接收者 ID 或会话 ID
     * @param mediaPath 图片地址或本地路径
     * @return 异步发送结果
     */
    @Override
    public CompletableFuture<BotSendResult> sendImageAsync(String toUser, String mediaPath) {
        return CompletableFuture.supplyAsync(() -> sendImage(toUser, mediaPath));
    }

    /**
     * 消息发送（异步）
     *
     * @param message 出站消息
     * @return 异步发送结果
     */
    @Override
    public CompletableFuture<BotSendResult> sendAsync(BotOutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    /**
     * 群文本发送（异步）
     *
     * @param groupId 群会话 ID
     * @param content 文本内容
     * @return 异步发送结果
     */
    @Override
    public CompletableFuture<BotSendResult> sendToGroupAsync(String groupId, String content) {
        return CompletableFuture.supplyAsync(() -> sendToGroup(groupId, content));
    }

    /**
     * 群文本提及发送（异步）
     *
     * @param groupId          群会话 ID
     * @param content          文本内容
     * @param mentionedUserIds 被 @ 的成员 ID
     * @return 异步发送结果
     */
    @Override
    public CompletableFuture<BotSendResult> sendToGroupMentionAsync(String groupId, String content,
            List<String> mentionedUserIds) {
        return CompletableFuture.supplyAsync(
                () -> sendToGroupMention(groupId, content, mentionedUserIds));
    }

    /**
     * 统一出站：被动回复窗口可用则被动回复，否则开放平台主动消息，最后回落自定义机器人 Webhook
     *
     * @param target      接收者 ID 或会话 ID
     * @param toGroup     是否群聊
     * @param webhookBody Webhook 消息体，为 null 表示该类型不支持 Webhook
     * @param msgKey      主动消息模板键
     * @param msgParam    主动消息模板参数
     * @return 发送结果
     */
    private BotSendResult deliver(String target, boolean toGroup, JsonObject webhookBody,
                                  String msgKey, JsonObject msgParam) {
        SessionContext context = StringUtils.isBlank(target) ? null : sessions.get(target);
        boolean group = toGroup || context != null && context.fromGroup;
        if (context != null && context.usable()) {
            BotSendResult result = postWebhook(context.sessionWebhook, webhookBody, null, false);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("钉钉机器人被动回复失败, 回落主动消息: {}", result.getErrorMessage());
        }
        if (hasCredentials()) {
            try {
                String activeTarget = group && context != null
                        ? firstNonBlank(context.conversationId, target) : target;
                return activeSend(activeTarget, group, msgKey, msgParam);
            } catch (Exception e) {
                return failure("消息", e);
            }
        }
        if (StringUtils.isBlank(webhookUrl)) {
            return BotSendResult.fail(-1, "无可用的被动回复窗口, 且未配置 "
                    + "clientId/clientSecret 或 webhookUrl");
        }
        if (webhookBody == null) {
            return BotSendResult.fail(-1, "自定义机器人 Webhook 不支持该消息类型");
        }
        return postWebhook(webhookUrl, webhookBody, webhookSignSecret, true);
    }

    /**
     * 开放平台主动消息发送
     *
     * @param target   接收者 ID 或群会话 ID
     * @param toGroup  是否群聊
     * @param msgKey   模板键
     * @param msgParam 模板参数
     * @return 发送结果
     */
    private BotSendResult activeSend(String target, boolean toGroup, String msgKey,
                                    JsonObject msgParam) {
        if (StringUtils.isBlank(target)) {
            return BotSendResult.fail(-1, "接收者必填");
        }
        JsonObject body = new JsonObject()
                .fluent("robotCode", firstNonBlank(robotCode, clientId))
                .fluent("msgKey", msgKey)
                .fluent("msgParam", Json.toJson(msgParam));
        String path;
        if (toGroup) {
            body.put("openConversationId", target);
            path = GROUP_MESSAGE_PATH;
        } else {
            body.put("userIds", Collections.singletonList(target));
            path = OTO_MESSAGE_PATH;
        }
        Map<String, Object> result = api(path, body, true);
        String msgId = firstNonBlank(stringValue(result.get("messageId")),
                stringValue(result.get("processQueryKey")));
        return BotSendResult.ok(msgId);
    }

    /**
     * 拼接 @ 占位，钉钉需在正文出现占位才会高亮
     *
     * @param content 文本内容
     * @param userIds 被 @ 的成员 ID
     * @return 带 @ 占位的文本内容
     */
    private String withMention(String content, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder();
        for (String userId : userIds) {
            builder.append('@').append(userId).append(' ');
        }
        return builder.append(content).toString();
    }

    /**
     * 上传素材换取 media_id
     *
     * @param mediaPath 本地文件路径
     * @param type      素材类型，image / file
     * @return media_id
     * @throws Exception 读取或上传失败
     */
    private String uploadMedia(String mediaPath, String type) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(mediaPath));
        String fileName = Paths.get(mediaPath).getFileName().toString();
        String boundary = BOUNDARY_PREFIX + System.currentTimeMillis();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String url = legacyBaseUrl + MEDIA_UPLOAD_PATH + "?access_token="
                + URLEncoder.encode(accessTokenOrFetch(), StandardCharsets.UTF_8.name())
                + "&type=" + type;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> response = send(request, MEDIA_UPLOAD_PATH);
        Map<String, Object> result = parse(response);
        int errcode = (int) longValueOrDefault(result.get("errcode"), 0L);
        if (response.statusCode() >= 400 || errcode != 0) {
            throw new DingTalkApiException(errcode != 0 ? errcode : response.statusCode(),
                    firstNonBlank(stringValue(result.get("errmsg")),
                            stringValue(result.get("message")), "素材上传失败"));
        }
        String mediaId = stringValue(result.get("media_id"));
        if (StringUtils.isBlank(mediaId)) {
            throw new DingTalkApiException(-1,
                    "素材上传响应缺少 media_id: " + Json.toJson(result));
        }
        return mediaId;
    }

    /**
     * 以 Webhook 形态推送消息体，被动回复与自定义机器人共用
     *
     * @param url       推送地址
     * @param body      消息体
     * @param signKey   加签密钥，可为空
     * @param allowSign 是否允许加签
     * @return 发送结果
     */
    private BotSendResult postWebhook(String url, JsonObject body, String signKey,
                                      boolean allowSign) {
        if (body == null) {
            return BotSendResult.fail(-1, "Webhook 通道不支持该消息类型");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(allowSign ? signUrl(url, signKey) : url))
                    .timeout(Duration.ofMillis(readTimeoutMillis))
                    .header("Content-Type", "application/json;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> result = parse(response);
            int code = response.statusCode() >= 400 ? response.statusCode()
                    : (int) longValueOrDefault(result.get("errcode"), 0L);
            if (code != 0) {
                String errmsg = firstNonBlank(stringValue(result.get("errmsg")),
                        stringValue(result.get("message")));
                log.error("钉钉机器人 Webhook 返回错误: code={}, msg={}", code, errmsg);
                return BotSendResult.fail(code, firstNonBlank(errmsg, "unknown"));
            }
            return BotSendResult.ok(stringValue(result.get("msgId")));
        } catch (Exception e) {
            return failure("Webhook 消息", e);
        }
    }

    /**
     * 自定义机器人加签
     *
     * @param url    原始地址
     * @param secret 加签密钥
     * @return 带 timestamp 与 sign 的地址
     * @throws Exception 签名失败
     */
    private String signUrl(String url, String secret) throws Exception {
        if (StringUtils.isBlank(secret)) {
            return url;
        }
        long timestamp = System.currentTimeMillis();
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
        byte[] signData = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData),
                StandardCharsets.UTF_8.name());
        return url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
    }

    // ==================== 令牌与 REST ====================

    /**
     * @return 是否具备开放平台凭证
     */
    private boolean hasCredentials() {
        return StringUtils.isNotBlank(clientId) && StringUtils.isNotBlank(clientSecret);
    }

    /**
     * 取缓存或重新换取 access_token
     *
     * @return access_token
     */
    private synchronized String accessTokenOrFetch() {
        long now = System.currentTimeMillis();
        if (StringUtils.isBlank(accessToken) || now >= tokenExpireAt - TOKEN_REFRESH_AHEAD_MS) {
            fetchAccessToken();
        }
        return accessToken;
    }

    /**
     * 换取 access_token，有效期按响应 expireIn 折算
     */
    private void fetchAccessToken() {
        JsonObject body = new JsonObject()
                .fluent("appKey", clientId)
                .fluent("appSecret", clientSecret);
        Map<String, Object> result = apiQuiet(ACCESS_TOKEN_PATH, body);
        String token = stringValue(result.get("accessToken"));
        if (StringUtils.isBlank(token)) {
            throw new DingTalkApiException(-1, "换取 access_token 失败: " + Json.toJson(result));
        }
        long expireMs = longValueOrDefault(result.get("expireIn"), 0L) * 1000L;
        this.accessToken = token;
        this.tokenExpireAt = System.currentTimeMillis()
                + (expireMs > 0 ? expireMs : TOKEN_FALLBACK_EXPIRES_MS);
    }

    /**
     * 调用开放平台接口
     *
     * @param path   接口路径
     * @param body   请求体
     * @param authed 是否需要携带 access_token
     * @return 响应体
     * @throws DingTalkApiException 请求失败或平台返回错误
     */
    private Map<String, Object> api(String path, JsonObject body, boolean authed) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(absolute(path)))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "application/json;charset=utf-8");
        if (authed) {
            builder.header("x-acs-dingtalk-access-token", accessTokenOrFetch());
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, path);
        Map<String, Object> result = parse(response);
        if (response.statusCode() >= 400) {
            throw new DingTalkApiException(response.statusCode(),
                    describeApiError(result, response.body()));
        }
        return result;
    }

    /**
     * 组装开放平台错误描述。平台错误体为 {@code {requestid, code, message}}，机器码与请求 ID
     * 是排查依据，不能只留人话。
     *
     * @param result 响应体
     * @param raw    响应原文
     * @return 错误描述
     */
    private static String describeApiError(Map<String, Object> result, String raw) {
        String code = stringValue(result.get("code"));
        String message = stringValue(result.get("message"));
        String requestId = stringValue(result.get("requestid"));
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(code)) {
            text.append(code);
        }
        if (StringUtils.isNotBlank(message)) {
            text.append(text.length() == 0 ? message : ": " + message);
        }
        if (StringUtils.isNotBlank(requestId)) {
            text.append(" (requestid=").append(requestId).append(')');
        }
        return text.length() == 0 ? firstNonBlank(raw, "空响应体") : text.toString();
    }

    /**
     * 调用换取令牌接口，不递归取令牌
     *
     * @param path 接口路径
     * @param body 请求体
     * @return 响应体
     */
    private Map<String, Object> apiQuiet(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(absolute(path)))
                .timeout(Duration.ofMillis(readTimeoutMillis))
                .header("Content-Type", "application/json;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8))
                .build();
        return parse(send(request, path));
    }

    /**
     * 执行 HTTP 请求
     *
     * @param request 请求
     * @param path    接口路径，用于错误描述
     * @return HTTP 响应
     * @throws DingTalkApiException 请求失败
     */
    private HttpResponse<String> send(HttpRequest request, String path) {
        try {
            return httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DingTalkApiException(-1, "请求被中断: " + path);
        } catch (IOException e) {
            throw new DingTalkApiException(-1, "请求失败: " + path + " " + e.getMessage());
        }
    }

    /**
     * 解析响应体为映射
     *
     * @param response HTTP 响应
     * @return 响应体映射，空体返回空映射
     */
    private Map<String, Object> parse(HttpResponse<String> response) {
        JsonObject json = objectValue(response.body());
        return json == null ? new LinkedHashMap<>() : json;
    }

    /**
     * 拼接绝对地址
     *
     * @param path 接口路径或完整地址
     * @return 绝对地址
     */
    private String absolute(String path) {
        return path.startsWith("http") ? path : apiBaseUrl + path;
    }

    /**
     * 统一异常转失败结果并回调错误监听器
     *
     * @param what 操作描述
     * @param e    异常
     * @return 失败结果
     */
    private BotSendResult failure(String what, Exception e) {
        notifyError(e);
        log.error("钉钉机器人发送{}失败: {}", what, e.getMessage());
        if (e instanceof DingTalkApiException) {
            DingTalkApiException api = (DingTalkApiException) e;
            return BotSendResult.fail(api.getCode(), api.getMessage());
        }
        return BotSendResult.fail(-1, e.getMessage());
    }

    // ==================== 监听器与配置 ====================

    /**
     * 设置用户存储
     *
     * @param userStore 用户存储
     * @return this
     */
    @Override
    public BotClient userStore(BotUserStore userStore) {
        this.userStore = userStore == null ? new InMemoryBotUserStore() : userStore;
        return this;
    }

    /**
     * @return 已知的用户列表
     */
    @Override
    public List<BotUserInfo> listUsers() {
        return userStore.findAll();
    }

    /**
     * @return 长连接期间观测到的群会话，钉钉未提供群列表接口
     */
    @Override
    public List<BotGroupInfo> listGroups() {
        return new ArrayList<>(observedGroups.values());
    }

    /**
     * 添加消息监听器
     *
     * @param listener 监听器
     * @return this
     */
    @Override
    public BotClient addMessageListener(BotMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
        return this;
    }

    /**
     * 移除消息监听器
     *
     * @param listener 监听器
     * @return this
     */
    @Override
    public BotClient removeMessageListener(BotMessageListener listener) {
        messageListeners.remove(listener);
        return this;
    }

    /**
     * 添加错误监听器
     *
     * @param listener 监听器
     * @return this
     */
    @Override
    public BotClient addErrorListener(BotErrorListener listener) {
        if (listener != null) {
            errorListeners.add(listener);
        }
        return this;
    }

    /**
     * @return 当前运行配置快照，敏感字段已脱敏
     */
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", streamMode ? "stream" : (webhookMode ? "webhook" : "send-only"));
        config.put("clientId", mask(clientId));
        config.put("apiBaseUrl", apiBaseUrl);
        config.put("legacyBaseUrl", legacyBaseUrl);
        config.put("webhookUrl", maskUrl(webhookUrl));
        config.put("subscriptions", getTopics());
        config.put("running", running.get());
        config.put("streamConnected", isWebSocketConnected());
        config.put("gatewayEndpoint", gatewayEndpoint);
        config.put("observedGroups", observedGroups.size());
        config.put("observedSessions", sessions.size());
        config.put("tokenCached", StringUtils.isNotBlank(accessToken));
        return config;
    }

    /**
     * 构造订阅项
     *
     * @param type  订阅类型
     * @param topic 订阅主题
     * @return 订阅项
     */
    private static Map<String, Object> subscription(String type, String topic) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("topic", topic);
        return item;
    }

    /**
     * 判定入站消息是否来自群聊
     *
     * @param data 消息体
     * @return 是否群聊
     */
    private static boolean isGroupConversation(JsonObject data) {
        return CONVERSATION_GROUP.equals(stringValue(data.get("conversationType")));
    }

    /**
     * 归一化帧主题，容忍大小写与前导斜杠差异
     *
     * @param topic 原始主题
     * @return 归一化主题
     */
    private static String normalizeTopic(String topic) {
        if (topic == null) {
            return "";
        }
        String value = topic.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /**
     * @param value 待判定字符串
     * @return 是否为 http(s) 地址
     */
    private static boolean isUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 去除尾部斜杠
     *
     * @param value 原始值
     * @return 去除尾部斜杠后的值
     */
    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 掩码敏感字符串，避免日志与配置快照泄露凭据
     *
     * @param value 原始值
     * @return 掩码后的值
     */
    private static String mask(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (value.length() <= 8) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    /**
     * 掩码地址中的查询参数
     *
     * @param url 原始地址
     * @return 掩码后的地址
     */
    private static String maskUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        int index = url.indexOf('?');
        return index < 0 ? url : url.substring(0, index) + "?***";
    }

    /**
     * 取首个非空白字符串
     *
     * @param values 候选值
     * @return 首个非空白值，全为空时返回 null
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 去除首尾空白，空串归一为 null
     *
     * @param value 原始值
     * @return 裁剪后的值
     */
    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * 取字符串值
     *
     * @param value 原始值
     * @return 字符串值
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取长整型值
     *
     * @param value 原始值
     * @return 长整型值，无法转换返回 0
     */
    private static long longValue(Object value) {
        return longValueOrDefault(value, 0L);
    }

    /**
     * 取长整型值并带兜底
     *
     * @param value      原始值
     * @param defaultVal 兜底值
     * @return 长整型值
     */
    private static long longValueOrDefault(Object value, long defaultVal) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && StringUtils.isNotBlank((String) value)) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultVal;
            }
        }
        return defaultVal;
    }

    /**
     * 取布尔值
     *
     * @param value 原始值
     * @return 布尔值，非布尔返回 null
     */
    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.valueOf((String) value);
        }
        return null;
    }

    /**
     * 取嵌套对象，兼容映射与 JSON 字符串两种形态
     *
     * @param value 原始值
     * @return 对象映射，无法转换返回 null
     */
    private static JsonObject objectValue(Object value) {
        if (value instanceof JsonObject) {
            return (JsonObject) value;
        }
        if (value instanceof Map) {
            JsonObject json = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                json.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return json;
        }
        if (value instanceof String && Json.isJson(value)) {
            try {
                return Json.getJsonObject((String) value);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 通知错误监听器
     *
     * @param e 异常
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
     * 被动回复窗口
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static class SessionContext {

        /**
         * 被动回复地址
         */
        private final String sessionWebhook;

        /**
         * 被动回复过期时间戳
         */
        private final long expireAt;

        /**
         * 是否群聊
         */
        private final boolean fromGroup;

        /**
         * 会话 ID
         */
        private final String conversationId;

        /**
         * @param sessionWebhook 被动回复地址
         * @param expireAt       过期时间戳
         * @param fromGroup      是否群聊
         * @param conversationId 会话 ID
         */
        SessionContext(String sessionWebhook, long expireAt, boolean fromGroup,
                       String conversationId) {
            this.sessionWebhook = sessionWebhook;
            this.expireAt = expireAt;
            this.fromGroup = fromGroup;
            this.conversationId = conversationId;
        }

        /**
         * @return 被动回复地址是否仍在有效期内
         */
        boolean usable() {
            return StringUtils.isNotBlank(sessionWebhook)
                    && (expireAt <= 0 || expireAt > System.currentTimeMillis());
        }
    }

    /**
     * 钉钉接口异常
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static class DingTalkApiException extends RuntimeException {

        /**
         * 序列化标识
         */
        private static final long serialVersionUID = 1L;

        /**
         * 错误码
         */
        private final int code;

        /**
         * @param code    错误码
         * @param message 错误描述
         */
        public DingTalkApiException(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * @return 错误码
         */
        public int getCode() {
            return code;
        }
    }
}
