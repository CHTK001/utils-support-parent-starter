package com.chua.wechat.support.bot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.lang.json.Json;
import com.chua.wechat.support.bot.WechatReplyPolicy.DenyReason;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人微信自动回复全链路测试。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 顶替本机 Hook 服务，走真实 HTTP：
 * 假服务推送消息 → 回调地址 → {@code deliver()} → 风控 → 假模型 → 切条 → 发送接口。
 * 除进程注入外，链路上每一环都由真实请求验证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("个人微信自动回复全链路测试")
class WechatAutoReplyFlowTest {

    /** 当前登录账号 wxid */
    private static final String SELF_WXID = "wxid_self";

    /** 好友 wxid */
    private static final String FRIEND_WXID = "wxid_friend";

    /** 群 wxid */
    private static final String ROOM_WXID = "123@chatroom";

    /** 回调地址路径 */
    private static final String CALLBACK_PATH = "/wechat/callback";

    /** 单条回复长度上限，故意设小以触发切条 */
    private static final int REPLY_MAX_LENGTH = 12;

    /** 每会话保留的历史消息条数 */
    private static final int HISTORY_SIZE = 12;

    /** 等待异步回复的超时毫秒数 */
    private static final long AWAIT_TIMEOUT_MILLIS = 5_000L;

    /** 轮询间隔毫秒数 */
    private static final long POLL_INTERVAL_MILLIS = 50L;

    /** 判定发送已停止增长所需的连续轮询次数 */
    private static final int STABLE_POLLS = 2;

    /** 观察静默期的等待毫秒数，期间不应有任何发送 */
    private static final long SILENCE_WINDOW_MILLIS = 400L;

    /** 假回复的固定前缀 */
    private static final String ANSWER_PREFIX = "收到：";

    /** 假回复的固定尾巴，保证长度超过上限以触发切条 */
    private static final String ANSWER_SUFFIX = "。这是补充说明。";

    /** 假 Hook 服务 */
    private HttpServer server;

    /** 假 Hook 服务基础地址 */
    private String baseUrl;

    /** 被测客户端 */
    private WechatPersonalBotClient client;

    /** 被测处理器 */
    private WechatAutoReplyHandler handler;

    /** 假模型调用次数 */
    private final AtomicInteger modelCalls = new AtomicInteger();

    /** 最近一次喂给模型的历史条数 */
    private final AtomicInteger lastHistorySize = new AtomicInteger(-1);

    /** Hook 服务收到的发送请求体 */
    private final List<String> sentBodies = new CopyOnWriteArrayList<>();

    /** Hook 服务收到的回调注册请求体 */
    private final AtomicReference<String> registerBody = new AtomicReference<>();

    /**
     * 起假 Hook 服务，接好查询登录号、注册回调、发送文本、推送消息四个端点。
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(WechatPersonalBotClient.USER_INFO_PATH, exchange ->
                reply(exchange, "{\"status\":0,\"data\":{\"wxid\":\"" + SELF_WXID + "\"}}"));
        server.createContext(WechatPersonalBotClient.CALLBACK_REGISTER_PATH, exchange -> {
            registerBody.set(read(exchange));
            reply(exchange, "{\"status\":0}");
        });
        server.createContext(WechatPersonalBotClient.SEND_TEXT_PATH, exchange -> {
            sentBodies.add(read(exchange));
            reply(exchange, "{\"status\":0,\"msgId\":\"srv-" + sentBodies.size() + "\"}");
        });
        server.createContext(CALLBACK_PATH, exchange -> {
            client.deliver(read(exchange));
            reply(exchange, "{\"status\":0}");
        });
        server.start();
        client = new WechatPersonalBotClient();
        client.baseUrl(baseUrl);
        client.callbackUrl(baseUrl + CALLBACK_PATH);
    }

    /**
     * 关闭回复线程池与假 Hook 服务。
     */
    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.close();
        }
        client.stop();
        server.stop(0);
    }

    @Test
    @DisplayName("start 解析登录号并向 Hook 服务注册回调地址")
    void startResolvesSelfAndRegistersCallback() {
        client.start();

        assertEquals(SELF_WXID, client.getSelfWxid());
        assertTrue(registerBody.get().contains(CALLBACK_PATH), "register=" + registerBody.get());
        assertTrue(registerBody.get().contains("\"enable\":1"), "register=" + registerBody.get());
    }

    @Test
    @DisplayName("好友消息经回调驱动模型并以真实 HTTP 分条发出")
    void privateMessageRepliesThroughHttp() throws Exception {
        startHandler(noDelayPolicy());
        String answer = answerOf("你好呀朋友");

        push(FRIEND_WXID, "你好呀朋友", "7001");

        List<String> bodies = awaitSettled();
        assertTrue(bodies.size() > 1, "应被切成多条: " + bodies);
        assertEquals(FRIEND_WXID, jsonValue(bodies.get(0), "wxid"));
        assertEquals(answer, concatMessages(bodies));
        for (String body : bodies) {
            assertTrue(jsonValue(body, "msg").length() <= REPLY_MAX_LENGTH, "part=" + body);
        }
    }

    @Test
    @DisplayName("同 msgId 重推不再触发第二次回复")
    void duplicatedCallbackIsDeduped() throws Exception {
        startHandler(noDelayPolicy());

        push(FRIEND_WXID, "第一", "7002");
        int sent = awaitSettled().size();

        push(FRIEND_WXID, "第一", "7002");
        Thread.sleep(SILENCE_WINDOW_MILLIS);

        assertEquals(sent, sentBodies.size());
        assertEquals(1, modelCalls.get());
    }

    @Test
    @DisplayName("自己发出的消息不触发回复")
    void selfMessageIsIgnored() throws Exception {
        startHandler(noDelayPolicy());

        push(SELF_WXID, "我自己说的", "7003");
        Thread.sleep(SILENCE_WINDOW_MILLIS);

        assertTrue(sentBodies.isEmpty(), "sent=" + sentBodies);
        assertEquals(0, modelCalls.get());
        assertEquals(0, client.deliver("{\"type\":1,\"msg\":\"自言自语\",\"talker\":\""
                + SELF_WXID + "\",\"msgId\":\"7003b\"}").size());
    }

    @Test
    @DisplayName("每分钟上限生效后不再触达模型")
    void rateLimitBlocksSecondTurn() throws Exception {
        WechatReplyPolicy policy = noDelayPolicy().maxPerUserPerMinute(1);
        startHandler(policy);

        push(FRIEND_WXID, "第一", "7004");
        int sent = awaitSettled().size();

        push(FRIEND_WXID, "第二", "7005");
        Thread.sleep(SILENCE_WINDOW_MILLIS);

        assertEquals(1, modelCalls.get());
        assertEquals(sent, sentBodies.size());

        List<BotInboundMessage> accepted = client.deliver("{\"type\":1,\"msg\":\"第三\","
                + "\"talker\":\"" + FRIEND_WXID + "\",\"msgId\":\"7005b\"}");
        assertEquals(1, accepted.size());
        assertEquals(DenyReason.USER_RATE_LIMIT, policy.denyReason(accepted.get(0)));
    }

    @Test
    @DisplayName("第二轮对话带上一轮问答作为历史")
    void historyCarriesPreviousTurn() throws Exception {
        startHandler(noDelayPolicy().maxPerUserPerMinute(5));

        push(FRIEND_WXID, "第一", "7006");
        awaitSettled();
        assertEquals(0, lastHistorySize.get());

        push(FRIEND_WXID, "第二", "7007");
        awaitSettled();
        assertEquals(2, lastHistorySize.get());
        assertEquals(2, modelCalls.get());
    }

    @Test
    @DisplayName("群聊默认被风控挡下，模型与发送都不触达")
    void groupMessageIsDenied() throws Exception {
        WechatReplyPolicy policy = noDelayPolicy();
        startHandler(policy);

        pushGroup(ROOM_WXID, FRIEND_WXID, "群里说话", "7008");
        Thread.sleep(SILENCE_WINDOW_MILLIS);

        assertTrue(sentBodies.isEmpty(), "sent=" + sentBodies);
        assertEquals(0, modelCalls.get());

        List<BotInboundMessage> accepted = client.deliver(
                "{\"type\":1,\"msg\":\"群里再说\",\"talker\":\"" + ROOM_WXID
                        + "\",\"roomWxid\":\"" + ROOM_WXID + "\",\"sender\":\"" + FRIEND_WXID
                        + "\",\"msgId\":\"7008b\"}");
        assertEquals(1, accepted.size());
        assertEquals(ROOM_WXID, accepted.get(0).getChatId());
        assertEquals(FRIEND_WXID, accepted.get(0).getFromUser());
        assertEquals(DenyReason.GROUP_DISABLED, policy.denyReason(accepted.get(0)));
    }

    /**
     * 造一个不等待的风控：默认最小间隔与抖动会把每个用例拖到秒级。
     *
     * @return 关闭间隔与抖动的风控守卫
     */
    private static WechatReplyPolicy noDelayPolicy() {
        return new WechatReplyPolicy().minIntervalMillis(0).jitterMillis(0);
    }

    /**
     * 装上处理器并启动客户端。
     *
     * @param policy 风控守卫
     */
    private void startHandler(WechatReplyPolicy policy) {
        handler = new WechatAutoReplyHandler(client, new StubChatClient(), policy,
                HISTORY_SIZE, REPLY_MAX_LENGTH);
        client.addMessageListener(handler);
        client.start();
    }

    /**
     * 假模型对某个提问给出的完整回复。
     *
     * @param prompt 提问
     * @return 回复全文
     */
    private static String answerOf(String prompt) {
        return ANSWER_PREFIX + prompt + ANSWER_SUFFIX;
    }

    /**
     * 以 Hook 服务的身份把一条单聊消息 POST 到已注册的回调地址。
     *
     * @param talker 会话对端 wxid
     * @param content 消息正文
     * @param msgId 消息 id
     */
    private void push(String talker, String content, String msgId) throws Exception {
        postToCallback("{\"type\":1,\"msg\":\"" + content + "\",\"talker\":\"" + talker
                + "\",\"msgId\":\"" + msgId + "\",\"timestamp\":1700000000}");
    }

    /**
     * 推送一条群消息，发言人与群分开。
     *
     * @param room 群 wxid
     * @param sender 群内发言人 wxid
     * @param content 消息正文
     * @param msgId 消息 id
     */
    private void pushGroup(String room, String sender, String content, String msgId)
            throws Exception {
        postToCallback("{\"type\":1,\"msg\":\"" + content + "\",\"talker\":\"" + room
                + "\",\"roomWxid\":\"" + room + "\",\"sender\":\"" + sender
                + "\",\"msgId\":\"" + msgId + "\"}");
    }

    /**
     * 向回调地址发一次 POST，模拟 Hook 服务的推送。
     *
     * @param body 原始回调体
     */
    private void postToCallback(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + CALLBACK_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "body=" + body);
    }

    /**
     * 等待 Hook 服务收到的发送条数连续若干轮不再增长。
     *
     * @return 稳定后的发送请求体列表
     */
    private List<String> awaitSettled() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        int previous = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int size = sentBodies.size();
            stable = size == previous ? stable + 1 : 0;
            previous = size;
            if (size > 0 && stable >= STABLE_POLLS) {
                return List.copyOf(sentBodies);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("等待回复超时, sent=" + sentBodies);
    }

    /**
     * 取发送体里的字段值。
     *
     * @param body 发送请求体
     * @param field 字段名
     * @return 字段字符串值
     */
    private static String jsonValue(String body, String field) {
        return String.valueOf(Json.getJsonObject(body).get(field));
    }

    /**
     * 把多条发送体的 msg 拼回完整回复。
     *
     * @param bodies 发送请求体列表
     * @return 拼接后的文本
     */
    private static String concatMessages(List<String> bodies) {
        StringBuilder builder = new StringBuilder();
        for (String body : bodies) {
            builder.append(jsonValue(body, "msg"));
        }
        return builder.toString();
    }

    /**
     * 读取请求体原文。
     *
     * @param exchange HTTP 交换
     * @return UTF-8 请求体
     */
    private static String read(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 回复固定 JSON。
     *
     * @param exchange HTTP 交换
     * @param body 响应体
     */
    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * 假模型：记录喂进来的历史条数，返回固定格式的回复。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private class StubChatClient implements ChatClient {

        @Override
        public ChatClient history(List<ChatMessage> messages) {
            lastHistorySize.set(messages == null ? 0 : messages.size());
            return this;
        }

        @Override
        public String chatSync(String prompt) {
            modelCalls.incrementAndGet();
            return answerOf(prompt);
        }
    }
}
