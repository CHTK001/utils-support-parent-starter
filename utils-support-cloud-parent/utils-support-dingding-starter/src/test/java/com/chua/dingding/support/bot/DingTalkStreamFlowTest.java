package com.chua.dingding.support.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

/**
 * 钉钉 Stream 模式收发链路单元测试，以 {@link FakeDingTalkGateway} 模拟开放平台网关。
 * <p>
 * 覆盖建连订阅、ping 应答、回调应答、入站映射、被动回复优先、主动消息回落、
 * 素材下载、断线重连与自定义机器人加签，全部断言取自律点原始帧与真实请求体。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class DingTalkStreamFlowTest {

    /**
     * 假的 AppKey
     */
    private static final String CLIENT_ID = "dingfakeclientid";

    /**
     * 假的 AppSecret
     */
    private static final String CLIENT_SECRET = "fake-client-secret";

    /**
     * 假的群会话 ID
     */
    private static final String GROUP_ID = "cidFakeGroup==";

    /**
     * 假的员工 ID
     */
    private static final String STAFF_ID = "manager0001";

    /**
     * 被动回复地址路径
     */
    private static final String SESSION_PATH = "/session/webhook";

    /**
     * 工装等待上限毫秒
     */
    private static final long WAIT_MS = 5000L;

    private FakeDingTalkGateway gateway;

    private DingTalkBotClient client;

    /**
     * 起网关并注册全量路由
     *
     * @throws Exception 端口绑定失败
     */
    @BeforeEach
    void setUp() throws Exception {
        gateway = new FakeDingTalkGateway();
        gateway.route("POST", "/v1.0/gateway/connections/open", 200,
                call -> Json.toJson(new JsonObject()
                        .fluent("endpoint", gateway.websocketUrl())
                        .fluent("ticket", "tk-1")));
        gateway.route("POST", "/v1.0/oauth2/accessToken", 200,
                call -> "{\"accessToken\":\"fake-access-token\",\"expireIn\":7200}");
        gateway.route("POST", SESSION_PATH, 200,
                call -> "{\"errcode\":0,\"errmsg\":\"ok\"}");
        gateway.route("POST", "/robot/send", 200,
                call -> "{\"errcode\":0,\"errmsg\":\"ok\"}");
        gateway.route("POST", "/v1.0/robot/oToMessages/batchSend", 200,
                call -> "{\"processQueryKey\":\"pqk-oto\"}");
        gateway.route("POST", "/v1.0/robot/groupMessages/send", 200,
                call -> "{\"processQueryKey\":\"pqk-group\"}");
        gateway.route("POST", "/v1.0/robot/messageFiles/download", 200,
                call -> "{\"downloadUrl\":\"https://fake.oss/download\"}");
        gateway.route("POST", "/media/upload", 200,
                call -> "{\"errcode\":0,\"errmsg\":\"ok\",\"media_id\":\"@lADmedia\"}");
        client = newClient();
    }

    /**
     * 关闭客户端与网关
     */
    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
        gateway.close();
    }

    // ==================== 建连与帧应答 ====================

    /**
     * 建连须以应用凭证换取 ticket，并订阅机器人收消息主题
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldOpenStreamWithBotMessageTopic() throws Exception {
        client.start();
        FakeDingTalkGateway.HttpCall open = awaitOpen();
        Map<?, ?> body = open.json();
        assertEquals(CLIENT_ID, body.get("clientId"));
        assertEquals(CLIENT_SECRET, body.get("clientSecret"));
        assertTrue(String.valueOf(body.get("subscriptions")).contains(
                        FakeDingTalkGateway.BOT_MESSAGE_TOPIC),
                "未订阅机器人收消息主题则平台不会回推消息: " + body.get("subscriptions"));
        assertTrue(client.isStreamMode(), "具备应用凭证应以 Stream 模式启动");
        assertTrue(awaitUntil(client::isWebSocketConnected, WAIT_MS), "长连接未建立");
        assertEquals(gateway.websocketUrl(), client.getGatewayEndpoint());
    }

    /**
     * 一次性 ticket 须原样带到接入点查询参数，且配置快照不得泄露凭据明文
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldCarryTicketAndMaskCredentials() throws Exception {
        client.start();
        awaitOpen();
        assertTrue(awaitUntil(gateway::websocketConnected, WAIT_MS));
        FakeDingTalkGateway.HttpCall upgrade = gateway.awaitCall("GET", "/websocket", WAIT_MS);
        assertNotNull(upgrade, "未见携带 ticket 的 WS 握手请求");
        assertTrue(upgrade.path.contains("ticket=tk-1"), "ticket 未回带: " + upgrade.path);
        assertFalse(String.valueOf(upgrade.path).contains(CLIENT_SECRET), "密钥不得出现在地址上");
        Map<String, Object> config = client.getConfig();
        assertFalse(config.toString().contains(CLIENT_SECRET), "配置快照泄露密钥: " + config);
        assertFalse(config.toString().contains("fake-access-token"), "配置快照泄露令牌: " + config);
        assertEquals("stream", config.get("mode"));
    }

    /**
     * SYSTEM ping 帧须原样回带 opaque，否则平台判定客户端失活
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldAnswerPingWithOpaqueEcho() throws Exception {
        client.start();
        awaitOpen();
        assertTrue(awaitUntil(gateway::websocketConnected, WAIT_MS));
        gateway.pushSystemEvent("ping", "sys-1", "{\"opaque\":\"op-77\"}");
        String ack = gateway.awaitFrame(frame -> frame.contains("op-77"), WAIT_MS);
        assertNotNull(ack, "未回应 ping 帧");
        Map<?, ?> frame = Json.fromJson(ack, Map.class);
        assertEquals("SYSTEM", frame.get("type"));
        assertEquals(200, ((Number) frame.get("code")).intValue());
        assertEquals("sys-1", header(frame).get("messageId"), "应答须回带请求 messageId");
        assertEquals("{\"opaque\":\"op-77\"}", frame.get("data"),
                "data 必须是 JSON 字符串而非嵌套对象");
    }

    /**
     * disconnect 后须换取新 ticket 重连，旧 ticket 不可复用
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldReopenSessionAfterDisconnect() throws Exception {
        client.start();
        awaitOpen();
        assertTrue(awaitUntil(gateway::websocketConnected, WAIT_MS));
        gateway.pushSystemEvent("disconnect", "sys-2", "{\"reason\":\"rebalance\"}");
        assertTrue(awaitUntil(() -> countCalls("/v1.0/gateway/connections/open") >= 2, WAIT_MS),
                "disconnect 后须换取新 ticket 重新建连");
    }

    /**
     * 平台可能回 https 形式的接入点，而 JDK WebSocket 只认 ws/wss，拨号前必须规范化
     */
    @Test
    void shouldNormalizeEndpointSchemeForWebsocket() {
        assertEquals("wss://gw.dingtalk.com:443/connect",
                DingTalkBotClient.normalizeEndpoint("https://gw.dingtalk.com:443/connect"));
        assertEquals("ws://127.0.0.1:8080/websocket",
                DingTalkBotClient.normalizeEndpoint("http://127.0.0.1:8080/websocket"));
        assertEquals("wss://gw.dingtalk.com/connect",
                DingTalkBotClient.normalizeEndpoint(" wss://gw.dingtalk.com/connect "));
        assertEquals("wss://gw.dingtalk.com/connect",
                DingTalkBotClient.normalizeEndpoint("gw.dingtalk.com/connect"), "缺协议须按 wss 补齐");
    }

    /**
     * 建连失败须把平台机器码与 requestid 透传给错误监听器，不能只剩人话
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldSurfacePlatformErrorCodeOnOpenFailure() throws Exception {
        client.stop();
        gateway.route("POST", "/v1.0/gateway/connections/open", 401,
                call -> "{\"requestid\":\"req-9\",\"code\":\"authFailed\",\"message\":\"鉴权失败\"}");
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        DingTalkBotClient broken = newClient();
        broken.addErrorListener(errors::add);
        broken.start();
        assertTrue(awaitUntil(() -> !errors.isEmpty(), WAIT_MS), "建连失败未上报错误");
        String message = String.valueOf(errors.get(0).getMessage());
        assertTrue(message.contains("authFailed"), "错误须含平台机器码: " + message);
        assertTrue(message.contains("requestid=req-9"), "错误须含 requestid: " + message);
        assertEquals(0, countCalls("/v1.0/robot/oToMessages/batchSend"), "建连失败不该继续发消息");
    }

    /**
     * 监听器耗时不得占用 WebSocket 读线程，否则平台的 ping 得不到应答
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldKeepAnsweringPingWhileListenerBlocks() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.addMessageListener(message -> {
            blocked.countDown();
            try {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        startStream();
        gateway.pushBotMessage("msg-block", groupText("卡住", gateway.baseUrl() + SESSION_PATH,
                System.currentTimeMillis() + 60_000L));
        assertTrue(blocked.await(WAIT_MS, TimeUnit.MILLISECONDS), "监听器未被调用");
        gateway.pushSystemEvent("ping", "sys-block", "{\"opaque\":\"op-block\"}");
        assertNotNull(gateway.awaitFrame(frame -> frame.contains("op-block"), WAIT_MS),
                "监听器阻塞期间读线程必须仍能应答 ping");
        release.countDown();
    }

    // ==================== 入站映射 ====================

    /**
     * 群消息须回 200 应答并映射为统一入站消息
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldAckAndMapGroupMessage() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        long expireAt = System.currentTimeMillis() + 60_000L;
        gateway.pushBotMessage("msg-1", groupText(" 1 ", gateway.baseUrl() + SESSION_PATH,
                expireAt));
        String ack = gateway.awaitFrame(frame -> frame.contains("msg-1"), WAIT_MS);
        assertNotNull(ack, "回调帧未应答，平台会持续重推");
        Map<?, ?> ackFrame = Json.fromJson(ack, Map.class);
        assertEquals("CALLBACK", ackFrame.get("type"));
        assertEquals("{\"response\":null}", ackFrame.get("data"));
        assertEquals("msg-1", header(ackFrame).get("messageId"), "应答须回显平台 messageId");
        BotInboundMessage message = awaitOne(received);
        assertEquals("msg", message.getMsgId(), "业务 msgId 取消息体 msgId, 非传输层 messageId");
        assertEquals("1", message.getContent(), "去除 @ 后残留的空白应被裁剪");
        assertEquals(BotInboundMessage.Type.TEXT, message.getType());
        assertEquals(STAFF_ID, message.getFromUser(), "企业内部应用应取 senderStaffId");
        assertEquals("绿野", message.getFromUserName());
        assertEquals(GROUP_ID, message.getChatId());
        assertTrue(message.isFromGroup());
        assertTrue(message.getMentionedBot(), "群机器人只接收 @ 消息，默认视为提及");
        assertEquals(1700000000000L, message.getCreateTime(), "createAt 须原样透传");
        assertEquals(CLIENT_ID, message.getToUser());
        assertEquals(gateway.baseUrl() + SESSION_PATH,
                message.getRawFields().get("sessionWebhook"));
    }

    /**
     * 单聊事件不存在 @ 动作，mentionedBot 必须为 false
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldNotMarkMentionedBotForSingleChat() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-2", singleText("在吗",
                gateway.baseUrl() + SESSION_PATH, System.currentTimeMillis() + 60_000L));
        BotInboundMessage message = awaitOne(received);
        assertEquals(Boolean.FALSE, message.getMentionedBot(),
                "单聊无 @ 动作，mentionedBot 应为 false");
        assertFalse(message.isFromGroup());
    }

    /**
     * isInAtList 存在时以平台判定为准，覆盖群聊默认值
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldTrustIsInAtListOverGroupHeuristic() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        String payload = new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", new JsonObject().fluent("content", "顺手发的"))
                .fluent("conversationType", "2")
                .fluent("conversationId", GROUP_ID)
                .fluent("senderStaffId", STAFF_ID)
                .fluent("isInAtList", false)
                .fluent("msgId", "msg-3")
                .toJSONString();
        gateway.pushBotMessage("msg-3", payload);
        BotInboundMessage message = awaitOne(received);
        assertEquals(Boolean.FALSE, message.getMentionedBot(),
                "平台已判定未 @ 机器人，不得用群聊启发式覆盖");
    }

    /**
     * 图片消息须解析 downloadCode 并换取临时下载地址
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldResolveDownloadUrlForPicture() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        String payload = new JsonObject()
                .fluent("msgtype", "picture")
                .fluent("content", new JsonObject().fluent("downloadCode", "dc-9527"))
                .fluent("conversationType", "1")
                .fluent("senderStaffId", STAFF_ID)
                .fluent("robotCode", CLIENT_ID)
                .fluent("msgId", "msg-4")
                .toJSONString();
        gateway.pushBotMessage("msg-4", payload);
        BotInboundMessage message = awaitOne(received);
        assertEquals(BotInboundMessage.Type.IMAGE, message.getType());
        assertEquals("dc-9527", message.getMediaId());
        assertEquals("https://fake.oss/download", message.getMediaUrl());
        FakeDingTalkGateway.HttpCall download = gateway.awaitCall("POST",
                "/v1.0/robot/messageFiles/download", WAIT_MS);
        assertNotNull(download, "未换取下载地址");
        assertEquals("dc-9527", download.json().get("downloadCode"));
        assertEquals(CLIENT_ID, download.json().get("robotCode"));
    }

    /**
     * 未知消息类型不得丢弃帧，映射为 UNKNOWN 交由业务判定
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldMapUnknownMsgTypeWithoutDropping() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-5", new JsonObject()
                .fluent("msgtype", "actionCard")
                .fluent("conversationType", "2")
                .fluent("conversationId", GROUP_ID)
                .fluent("msgId", "msg-5")
                .toJSONString());
        BotInboundMessage message = awaitOne(received);
        assertEquals(BotInboundMessage.Type.UNKNOWN, message.getType());
        assertNull(message.getContent());
    }

    /**
     * 缺 msgtype 的系统类帧不派发，但仍须应答
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldAckFrameWithoutMsgType() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-6", "{\"conversationId\":\"" + GROUP_ID + "\"}");
        String ack = gateway.awaitFrame(frame -> frame.contains("msg-6"), WAIT_MS);
        assertNotNull(ack, "无法解析的帧也必须应答，否则平台重推");
        assertTrue(received.isEmpty(), "无 msgtype 的帧不应派发为消息");
    }

    // ==================== 出站发送 ====================

    /**
     * 入站窗口内应走 sessionWebhook 被动回复，不消耗主动消息配额
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldReplyThroughSessionWebhook() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-7", groupText("你好", gateway.baseUrl() + SESSION_PATH,
                System.currentTimeMillis() + 60_000L));
        awaitOne(received);
        BotSendResult result = client.sendToGroup(GROUP_ID, "已收到");
        assertTrue(result.isSuccess(), "被动回复失败: " + result.getErrorMessage());
        FakeDingTalkGateway.HttpCall call = gateway.awaitCall("POST", SESSION_PATH, WAIT_MS);
        assertNotNull(call, "未走 sessionWebhook");
        Map<?, ?> body = call.json();
        assertEquals("text", body.get("msgtype"));
        assertEquals("已收到", String.valueOf(((Map<?, ?>) body.get("text")).get("content")));
        assertEquals(0, countCalls("/v1.0/robot/groupMessages/send"),
                "被动窗口内不应消耗主动消息配额");
    }

    /**
     * 会话 ID 与发送者 ID 都要能命中同一个被动回复窗口
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldKeySessionByConversationAndSender() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-8", groupText("你好", gateway.baseUrl() + SESSION_PATH,
                System.currentTimeMillis() + 60_000L));
        awaitOne(received);
        assertTrue(client.sendText(STAFF_ID, "按人回").isSuccess());
        assertTrue(client.sendToGroup(GROUP_ID, "按群回").isSuccess());
        assertTrue(awaitUntil(() -> countCalls(SESSION_PATH) >= 2, WAIT_MS),
                "两种目标都应命中同一个 sessionWebhook");
    }

    /**
     * 被动窗口过期后回落群主动消息，须带 access_token 与 openConversationId
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldFallbackToActiveGroupMessageWhenWindowExpired() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-9", groupText("你好", gateway.baseUrl() + SESSION_PATH,
                System.currentTimeMillis() - 1000L));
        awaitOne(received);
        BotSendResult result = client.sendToGroup(GROUP_ID, "补发");
        assertTrue(result.isSuccess(), "主动群消息失败: " + result.getErrorMessage());
        assertEquals("pqk-group", result.getMsgId());
        FakeDingTalkGateway.HttpCall send = gateway.awaitCall("POST",
                "/v1.0/robot/groupMessages/send", WAIT_MS);
        assertNotNull(send, "未回落主动群消息");
        assertEquals("fake-access-token", send.headers.get("x-acs-dingtalk-access-token"),
                "主动消息须带新版鉴权头");
        Map<?, ?> body = send.json();
        assertEquals(GROUP_ID, body.get("openConversationId"));
        assertEquals(CLIENT_ID, body.get("robotCode"));
        assertEquals("sampleText", body.get("msgKey"));
        assertEquals("补发", ((Map<?, ?>) Json.fromJson(String.valueOf(body.get("msgParam")),
                Map.class)).get("content"), "msgParam 必须是 JSON 字符串");
        assertEquals(0, countCalls(SESSION_PATH), "窗口过期后不得再打被动地址");
    }

    /**
     * 无入站会话时按单聊主动消息发送，userIds 为数组
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldSendActiveSingleChatWithoutInbound() throws Exception {
        client.start();
        awaitOpen();
        BotSendResult result = client.sendText(STAFF_ID, "主动单聊");
        assertTrue(result.isSuccess(), "主动单聊失败: " + result.getErrorMessage());
        FakeDingTalkGateway.HttpCall send = gateway.awaitCall("POST",
                "/v1.0/robot/oToMessages/batchSend", WAIT_MS);
        assertNotNull(send, "未走单聊主动消息");
        Map<?, ?> body = send.json();
        assertEquals(CLIENT_ID, body.get("robotCode"));
        assertTrue(String.valueOf(body.get("userIds")).contains(STAFF_ID),
                "userIds 应为数组: " + body.get("userIds"));
        assertNull(gateway.awaitCall("POST", "/v1.0/robot/groupMessages/send", 200L),
                "单聊不应打群消息接口");
    }

    /**
     * 群提及须回带 atUserIds 且在正文留下 @ 占位
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldMentionUsersInGroupPassiveReply() throws Exception {
        CopyOnWriteArrayList<BotInboundMessage> received = capture();
        startStream();
        gateway.pushBotMessage("msg-10", groupText("点名", gateway.baseUrl() + SESSION_PATH,
                System.currentTimeMillis() + 60_000L));
        awaitOne(received);
        BotSendResult result = client.sendToGroupMention(GROUP_ID, "开会了",
                Collections.singletonList("staff-2"));
        assertTrue(result.isSuccess(), "提及发送失败: " + result.getErrorMessage());
        FakeDingTalkGateway.HttpCall call = gateway.awaitCall("POST", SESSION_PATH, WAIT_MS);
        Map<?, ?> body = call.json();
        assertTrue(String.valueOf(((Map<?, ?>) body.get("at")).get("atUserIds"))
                .contains("staff-2"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) body.get("at")).get("isAtAll"));
        assertTrue(String.valueOf(((Map<?, ?>) body.get("text")).get("content"))
                .startsWith("@staff-2 "), "@ 占位须出现在正文");
    }

    /**
     * 出站消息对象按类型分派，群聊文本走群通道
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldDispatchOutboundByType() throws Exception {
        client.start();
        awaitOpen();
        BotSendResult result = client.send(BotOutboundMessage.builder()
                .type(BotInboundMessage.Type.TEXT)
                .toUser(GROUP_ID)
                .toGroup(true)
                .content("按类型发送")
                .build());
        assertTrue(result.isSuccess(), "按类型发送失败: " + result.getErrorMessage());
        assertNotNull(gateway.awaitCall("POST", "/v1.0/robot/groupMessages/send", WAIT_MS));
    }

    /**
     * 本地文件先上传素材再以文件模板下发
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldUploadMediaBeforeSendingFile() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("ding-file", ".txt");
        java.nio.file.Files.write(file, "hello dingtalk".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            client.start();
            awaitOpen();
            BotSendResult result = client.sendFile(STAFF_ID, file.toString());
            assertTrue(result.isSuccess(), "文件发送失败: " + result.getErrorMessage());
            FakeDingTalkGateway.HttpCall upload = gateway.awaitCall("POST", "/media/upload",
                    WAIT_MS);
            assertNotNull(upload, "未上传素材");
            assertTrue(upload.path.contains("access_token=fake-access-token"),
                    "素材上传须带令牌: " + upload.path);
            assertTrue(upload.headers.get("content-type").startsWith("multipart/form-data"),
                    "素材上传须用 multipart");
            Map<?, ?> body = gateway.awaitCall("POST",
                    "/v1.0/robot/oToMessages/batchSend", WAIT_MS).json();
            assertEquals("sampleFile", body.get("msgKey"));
            Map<?, ?> param = Json.fromJson(String.valueOf(body.get("msgParam")), Map.class);
            assertEquals("@lADmedia", param.get("mediaId"));
            assertEquals(String.valueOf("hello dingtalk".length()), String.valueOf(param.get("fileSize")));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /**
     * 语音与视频缺字段来源，须明确拒绝而不是伪造发送
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldRejectVoiceAndVideoExplicitly() throws Exception {
        client.start();
        awaitOpen();
        assertFalse(client.sendVoice(STAFF_ID, "/tmp/a.mp3").isSuccess());
        assertFalse(client.sendVideo(STAFF_ID, "/tmp/a.mp4", "t", "d").isSuccess());
        assertTrue(client.sendVoice(STAFF_ID, "/tmp/a.mp3").getErrorMessage().contains("时长"));
    }

    /**
     * 自定义机器人通道须加签，且不带开放平台令牌
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldSignLegacyWebhookWithoutCredentials() throws Exception {
        client.stop();
        DingTalkBotClient legacy = new DingTalkBotClient();
        legacy.baseUrl(gateway.baseUrl());
        legacy.legacyBaseUrl(gateway.baseUrl());
        legacy.webhookAccessToken("legacy-token");
        legacy.webhookSignSecret("SEC-fake-sign");
        legacy.start();
        assertEquals("send-only", legacy.getConfig().get("mode"));
        BotSendResult result = legacy.sendToGroup(GROUP_ID, "自定义机器人");
        assertTrue(result.isSuccess(), "自定义机器人发送失败: " + result.getErrorMessage());
        FakeDingTalkGateway.HttpCall call = gateway.awaitCall("POST", "/robot/send", WAIT_MS);
        assertNotNull(call, "未打自定义机器人 Webhook");
        assertTrue(call.path.contains("access_token=legacy-token"), call.path);
        assertTrue(call.path.contains("timestamp="), "加签缺 timestamp: " + call.path);
        assertTrue(call.path.contains("sign="), "加签缺 sign: " + call.path);
        assertNull(call.headers.get("x-acs-dingtalk-access-token"), "旧通道不该带新令牌");
    }

    /**
     * 既无凭证又无 Webhook 时启动应快速失败
     */
    @Test
    void shouldRejectStartWithoutAnyCredential() {
        DingTalkBotClient bare = new DingTalkBotClient();
        bare.baseUrl(gateway.baseUrl());
        assertThrows(IllegalStateException.class, bare::start, "无任何凭证不应静默启动");
    }

    /**
     * Webhook 通道返回业务错误码时不得报成功
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldSurfaceWebhookBusinessError() throws Exception {
        client.stop();
        gateway.route("POST", "/robot/send-fail", 200,
                call -> "{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        DingTalkBotClient legacy = new DingTalkBotClient();
        legacy.baseUrl(gateway.baseUrl());
        legacy.webhookUrl(gateway.baseUrl() + "/robot/send-fail?access_token=legacy-token");
        legacy.start();
        BotSendResult result = legacy.sendText(STAFF_ID, "带错误码");
        assertFalse(result.isSuccess(), "errcode 非 0 必须失败");
        assertEquals(310000, result.getErrorCode());
        assertEquals("sign not match", result.getErrorMessage());
    }

    /**
     * Webhook 接收模式须校验 challenge 并派发 HTTP 推送的消息体
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldDispatchHttpCallbackInWebhookMode() throws Exception {
        client.stop();
        CopyOnWriteArrayList<BotInboundMessage> received = new CopyOnWriteArrayList<>();
        DingTalkBotClient webhook = new DingTalkBotClient();
        webhook.baseUrl(gateway.baseUrl());
        webhook.legacyBaseUrl(gateway.baseUrl());
        webhook.webhookVerifyToken("verify-token");
        webhook.addMessageListener(received::add);
        webhook.start();

        assertEquals("verify-token", webhook.verifyChallenge("verify-token"));
        assertNull(webhook.verifyChallenge("other-token"), "challenge 不匹配不得回显");

        long expireAt = System.currentTimeMillis() + 60_000L;
        assertTrue(webhook.handleCallback(groupText("回调入站",
                gateway.baseUrl() + SESSION_PATH, expireAt)), "回调体未派发");
        BotInboundMessage message = awaitOne(received);
        assertEquals("回调入站", message.getContent());
        assertFalse(webhook.handleCallback("{ not json"), "非法回调体必须被拒绝");

        BotSendResult result = webhook.sendToGroup(GROUP_ID, "被动回复");
        assertTrue(result.isSuccess(), "被动回复失败: " + result.getErrorMessage());
        assertNotNull(gateway.awaitCall("POST", SESSION_PATH, WAIT_MS), "未走被动回复地址");
        assertEquals(0, countCalls("/v1.0/robot/groupMessages/send"), "窗口内不该发主动消息");
    }

    /**
     * SPI 应解析到钉钉实现
     */
    @Test
    void shouldResolveClientViaSpi() {
        BotClient auto = BotClient.auto("dingtalk");
        assertTrue(auto instanceof DingTalkBotClient, "auto(\"dingtalk\") 未解析到 DingTalkBotClient");
    }

    // ==================== 工装辅助 ====================

    /**
     * 构造带应用凭证的客户端，域名全部指向本机工装
     *
     * @return 被测客户端
     */
    private DingTalkBotClient newClient() {
        DingTalkBotClient created = new DingTalkBotClient();
        created.baseUrl(gateway.baseUrl());
        created.legacyBaseUrl(gateway.baseUrl());
        created.configure(CLIENT_ID, CLIENT_SECRET, null);
        created.connectTimeoutMillis(3000L);
        created.readTimeoutMillis(3000L);
        return created;
    }

    /**
     * 启动并等待长连接就绪
     *
     * @throws Exception 等待超时
     */
    private void startStream() throws Exception {
        client.start();
        awaitOpen();
        assertTrue(awaitUntil(gateway::websocketConnected, WAIT_MS), "工装未完成 WS 握手");
    }

    /**
     * 等待一次 Stream 建连请求
     *
     * @return 建连调用
     */
    private FakeDingTalkGateway.HttpCall awaitOpen() {
        FakeDingTalkGateway.HttpCall open = gateway.awaitCall("POST",
                "/v1.0/gateway/connections/open", WAIT_MS);
        assertNotNull(open, "未见 Stream 建连请求");
        return open;
    }

    /**
     * 注册入站捕获监听器
     *
     * @return 捕获列表
     */
    private CopyOnWriteArrayList<BotInboundMessage> capture() {
        CopyOnWriteArrayList<BotInboundMessage> received = new CopyOnWriteArrayList<>();
        client.addMessageListener(received::add);
        return received;
    }

    /**
     * 等待一条入站消息
     *
     * @param received 捕获列表
     * @return 入站消息
     */
    private static BotInboundMessage awaitOne(
            CopyOnWriteArrayList<BotInboundMessage> received) {
        assertTrue(awaitUntil(() -> !received.isEmpty(), WAIT_MS), "未收到入站消息");
        return received.get(0);
    }

    /**
     * 群文本消息体
     *
     * @param content 文本
     * @param sessionWebhook 被动回复地址
     * @param expireAt 窗口过期时间
     * @return 消息体 JSON
     */
    private static String groupText(String content, String sessionWebhook, long expireAt) {
        return Json.toJson(new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", new JsonObject().fluent("content", content))
                .fluent("senderStaffId", STAFF_ID)
                .fluent("senderId", "$:LWCP_v1:$fake")
                .fluent("senderNick", "绿野")
                .fluent("conversationId", GROUP_ID)
                .fluent("conversationType", "2")
                .fluent("robotCode", CLIENT_ID)
                .fluent("msgId", "msg")
                .fluent("createAt", 1700000000000L)
                .fluent("sessionWebhook", sessionWebhook)
                .fluent("sessionWebhookExpiredTime", expireAt));
    }

    /**
     * 单聊文本消息体
     *
     * @param content 文本
     * @param sessionWebhook 被动回复地址
     * @param expireAt 窗口过期时间
     * @return 消息体 JSON
     */
    private static String singleText(String content, String sessionWebhook, long expireAt) {
        return Json.toJson(new JsonObject()
                .fluent("msgtype", "text")
                .fluent("text", new JsonObject().fluent("content", content))
                .fluent("senderStaffId", STAFF_ID)
                .fluent("conversationId", "cidFakeSingle==")
                .fluent("conversationType", "1")
                .fluent("robotCode", CLIENT_ID)
                .fluent("msgId", "msg")
                .fluent("sessionWebhook", sessionWebhook)
                .fluent("sessionWebhookExpiredTime", expireAt));
    }

    /**
     * @param frame 上行帧
     * @return 帧头
     */
    private static Map<?, ?> header(Map<?, ?> frame) {
        return (Map<?, ?>) frame.get("headers");
    }

    /**
     * 统计命中指定路径的调用数
     *
     * @param pathPrefix 路径前缀
     * @return 调用数
     */
    private int countCalls(String pathPrefix) {
        int count = 0;
        for (FakeDingTalkGateway.HttpCall call : gateway.httpCalls()) {
            if (stripQuery(call.path).startsWith(pathPrefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @param path 含查询串的路径
     * @return 去掉查询串的路径
     */
    private static String stripQuery(String path) {
        int index = path.indexOf('?');
        return index < 0 ? path : path.substring(0, index);
    }

    /**
     * 轮询等待条件成立
     *
     * @param condition 条件
     * @param timeoutMs 超时毫秒
     * @return 是否成立
     */
    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
