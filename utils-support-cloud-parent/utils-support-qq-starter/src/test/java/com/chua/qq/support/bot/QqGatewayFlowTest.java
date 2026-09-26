package com.chua.qq.support.bot;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.lang.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QQ 机器人网关链路测试：以本地假网关按官方契约对话，逐段验证取令牌、网关、鉴权、
 * 心跳、事件映射、收发与富媒体上传。
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("QQ 机器人 网关链路测试")
class QqGatewayFlowTest {

    /**
     * 测试用 AppID。
     */
    private static final String APP_ID = "1000018791";

    /**
     * 测试用 ClientSecret。
     */
    private static final String APP_SECRET = "unit_test_secret";

    /**
     * 假网关下发的 access_token。
     */
    private static final String ACCESS_TOKEN = "unit_test_access_token";

    /**
     * 单聊用户 openid。
     */
    private static final String USER_OPENID = "USER6b2f0c1e0a4d8e9f0a1b2c3d4e5f6a7b";

    /**
     * 群 openid。
     */
    private static final String GROUP_OPENID = "GROUP8c3d1e2f0a4b8c9d0e1f2a3b4c5d6e7f";

    /**
     * 群事件里的发送者 member openid。
     */
    private static final String MEMBER_OPENID = "MEMB1c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7";

    /**
     * 入站群消息 id，被动回复需回带。
     */
    private static final String INBOUND_MSG_ID = "ROBOT1.0_unit_test_inbound_msg_id";

    private FakeQqGateway gateway;

    private QqBotClient client;

    /**
     * 本用例内是否已完成 WS 握手。
     */
    private boolean identified;

    /**
     * 握手时捕获到的 identify 帧原文。
     */
    private String identifyFrame;

    private final List<BotInboundMessage> received = new CopyOnWriteArrayList<>();

    /**
     * 启动假网关并注册官方端点。
     *
     * @throws Exception 端口绑定失败
     */
    @BeforeEach
    void setUp() throws Exception {
        gateway = new FakeQqGateway();
        gateway.route("POST", "/app/getAppAccessToken", 200,
                call -> "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":\"7200\"}");
        gateway.route("GET", "/gateway", 200,
                call -> "{\"url\":\"" + gateway.websocketUrl() + "\"}");
        gateway.route("POST", "/v2/users/", 200, call -> {
            if (call.path.endsWith("/files")) {
                return "{\"file_uuid\":\"uuid_1\",\"file_info\":\"FILEINFO_1\",\"ttl\":300}";
            }
            if (call.path.endsWith("/upload_prepare")) {
                return uploadPrepareBody(call);
            }
            if (call.path.endsWith("/upload_part_finish")) {
                return "{\"ret\":0}";
            }
            return "{\"id\":\"SENT_MSG_1\",\"timestamp\":\"2026-09-20T10:00:00+08:00\"}";
        });
        gateway.route("POST", "/v2/groups/", 200, call -> {
            if (call.path.endsWith("/files")) {
                return "{\"file_uuid\":\"uuid_2\",\"file_info\":\"FILEINFO_2\",\"ttl\":300}";
            }
            return "{\"id\":\"SENT_GROUP_1\",\"timestamp\":\"2026-09-20T10:00:00+08:00\"}";
        });
        gateway.route("PUT", "/chunks/", 200, call -> "{\"ret\":0}");
        client = new QqBotClient();
        client.baseUrl(gateway.baseUrl());
        client.addMessageListener(received::add);
    }

    /**
     * 释放客户端与网关。
     */
    @AfterEach
    void tearDown() {
        client.stop();
        gateway.close();
    }

    // ==================== 凭证与网关 ====================

    /**
     * 测试：SPI 扩展文件能把平台名解析到 QQ 实现。
     */
    @Test
    @DisplayName("SPI-BotClient.auto/builder 解析到 QqBotClient 且默认域名为 api.bot.qq.com")
    void shouldResolveQqClientThroughSpi() {
        BotClient auto = BotClient.auto("qq");
        assertTrue(auto instanceof QqBotClient, "auto(\"qq\") 未解析到 QqBotClient");
        assertEquals("https://api.bot.qq.com", auto.getConfig().get("baseUrl"),
                "生产默认 baseUrl 应为 api.bot.qq.com");

        BotClient built = BotClient.builder("qq")
                .token(APP_ID)
                .secret(APP_SECRET)
                .baseUrl(gateway.baseUrl())
                .readTimeoutMillis(5_000L)
                .build();
        assertTrue(built instanceof QqBotClient, "builder(\"qq\") 未解析到 QqBotClient");
        assertEquals(gateway.baseUrl(), built.getConfig().get("baseUrl"),
                "builder 的 baseUrl 未透传");
    }

    /**
     * 测试：取令牌请求指向可配置域名并携带 appId/clientSecret。
     */
    @Test
    @DisplayName("start-取 access_token 走 baseUrl 下的 /app/getAppAccessToken")
    void shouldFetchAccessTokenFromConfiguredBaseUrl() {
        client.configure(APP_ID, APP_SECRET, null);
        client.start();

        FakeQqGateway.HttpCall call = gateway.awaitCall("POST", "/app/getAppAccessToken", 5000L);
        assertNotNull(call, "未向 baseUrl 下的 /app/getAppAccessToken 发起请求。" + dump());
        Map<?, ?> body = call.json();
        assertEquals(APP_ID, body.get("appId"));
        assertEquals(APP_SECRET, body.get("clientSecret"));
    }

    /**
     * 测试：网关地址来自 /gateway 的 url 字段。
     */
    @Test
    @DisplayName("start-GET /gateway 带 QQBot 令牌并读取 url 字段建立 WS")
    void shouldResolveGatewayFromUrlField() {
        connectedClient();

        FakeQqGateway.HttpCall call = gateway.awaitCall("GET", "/gateway", 5000L);
        assertNotNull(call, "未请求 /gateway。" + dump());
        assertEquals("QQBot " + ACCESS_TOKEN, call.headers.get("authorization"));
        assertTrue(gateway.websocketConnected(), "WS 未连上假网关。" + dump());
    }

    /**
     * 测试：identify 帧结构符合官方 payload。
     */
    @Test
    @DisplayName("identify-op2 携带 QQBot 令牌、群/单聊 intents 与 shard")
    void shouldIdentifyWithTokenIntentsAndShard() {
        connectedClient();

        String identify = identifyFrame;
        assertNotNull(identify, "未收到 identify 帧。" + dump());
        Map<?, ?> frame = Json.fromJson(identify, Map.class);
        Map<?, ?> d = (Map<?, ?>) frame.get("d");
        assertEquals("QQBot " + ACCESS_TOKEN, d.get("token"));
        assertEquals(List.of(0, 1), toIntList(d.get("shard")), "shard 应为 [0,1]");
        int intents = ((Number) d.get("intents")).intValue();
        assertTrue((intents & (1 << 25)) != 0,
                "未订阅 GROUP_AND_C2C_EVENT(1<<25)，实际 intents=" + intents);
    }

    /**
     * 测试：READY 事件里的 session_id 被记录下来。
     */
    @Test
    @DisplayName("READY-记录 session_id 供断线 resume 使用")
    void shouldRecordSessionIdFromReady() {
        pushReady("sess_unit_1");
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if ("sess_unit_1".equals(String.valueOf(client.getConfig().get("sessionId")))) {
                return;
            }
            sleepQuietly(50L);
        }
        assertEquals("sess_unit_1", client.getConfig().get("sessionId"),
                "READY 的 d.session_id 未被记录。" + dump());
    }

    /**
     * 测试：心跳使用 op1 并携带最新 seq。
     */
    @Test
    @DisplayName("心跳-按 hello 下发间隔以 op1 上报（非 op11）")
    void shouldHeartbeatWithOpOne() {
        connectedClient();
        gateway.pushDispatch(7, "GROUP_AT_MESSAGE_CREATE", groupEventBody("心跳前的消息"));

        String heartbeat = awaitFrame("\"op\":1,", 5000L);
        assertNotNull(heartbeat, "未收到 op1 心跳帧。" + dump());
        Map<?, ?> frame = Json.fromJson(heartbeat, Map.class);
        assertEquals(7, ((Number) frame.get("d")).intValue(), "心跳应回带最新 seq");
    }

    // ==================== 事件映射 ====================

    /**
     * 测试：群 @机器人事件映射正确。
     */
    @Test
    @DisplayName("事件-GROUP_AT_MESSAGE_CREATE 映射群聊入站消息")
    void shouldMapGroupAtMessage() throws Exception {
        feedFrame("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"sess_x\"}}");
        feedFrame("{\"op\":0,\"s\":2,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":"
                + groupEventBody("帮我看看这个") + "}");

        BotInboundMessage msg = awaitInbound();
        assertEquals("帮我看看这个", msg.getContent());
        assertEquals(MEMBER_OPENID, msg.getFromUser(), "群聊应取 author.member_openid");
        assertEquals("小明", msg.getFromUserName());
        assertTrue(msg.isFromGroup());
        assertEquals(GROUP_OPENID, msg.getChatId());
        assertEquals(INBOUND_MSG_ID, msg.getMsgId());
        assertTrue(Boolean.TRUE.equals(msg.getMentionedBot()), "@机器人事件应标记 mentionedBot");
    }

    /**
     * 测试：单聊事件映射正确。
     */
    @Test
    @DisplayName("事件-C2C_MESSAGE_CREATE 映射单聊入站消息")
    void shouldMapC2cMessage() throws Exception {
        feedFrame("{\"op\":0,\"s\":3,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{"
                + "\"id\":\"C2C_MSG_1\","
                + "\"author\":{\"id\":\"" + USER_OPENID + "\",\"user_openid\":\"" + USER_OPENID
                + "\",\"username\":\"小红\",\"bot\":false},"
                + "\"content\":\"私聊你好\","
                + "\"timestamp\":\"2026-09-20T10:05:00+08:00\"}}");

        BotInboundMessage msg = awaitInbound();
        assertEquals("私聊你好", msg.getContent());
        assertEquals(USER_OPENID, msg.getFromUser(), "单聊应取 author.user_openid");
        assertEquals("小红", msg.getFromUserName());
        assertTrue(!msg.isFromGroup());
        assertEquals(USER_OPENID, msg.getChatId(), "单聊会话标识应为该用户 openid");
        assertTrue(msg.getCreateTime() > 0L, "RFC3339 时间戳应被解析");
        assertEquals(Boolean.FALSE, msg.getMentionedBot(),
                "单聊事件无 @ 动作，mentionedBot 应为 false（真机实测曾误判为 true）");
        assertEquals(USER_OPENID, client.listUsers().get(0).getUserId(), "用户应落进 userStore");
    }

    /**
     * 测试：群 @ 消息内容清掉平台残留的 @ 前导空格。
     */
    @Test
    @DisplayName("事件-群 @ 消息 content 前导空格被清理")
    void shouldTrimGroupMentionContent() throws Exception {
        feedFrame("{\"op\":0,\"s\":12,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":"
                + groupEventBody(" 1") + "}");

        BotInboundMessage msg = awaitInbound();
        assertEquals("1", msg.getContent(),
                "真机 content 实测为 \" 1\"（@机器人 被平台替换成空格），不能把前导空格交给上层");
    }

    /**
     * 测试：非消息类事件转发时携带事件名。
     */
    @Test
    @DisplayName("事件-非消息事件转发可见事件名")
    void shouldCarryEventNameForSystemEvents() throws Exception {
        feedFrame("{\"op\":0,\"s\":13,\"t\":\"GROUP_ADD_ROBOT\",\"d\":"
                + "{\"op_type\":1,\"group_openid\":\"" + GROUP_OPENID + "\","
                + "\"member_openid\":\"" + MEMBER_OPENID + "\"}}");

        BotInboundMessage msg = awaitInbound();
        assertEquals(BotInboundMessage.Type.EVENT, msg.getType());
        assertEquals("GROUP_ADD_ROBOT", msg.getEventType());
        assertEquals("GROUP_ADD_ROBOT", msg.getRawFields().get("event"),
                "真机入群通知曾取到 event=null，监听器无法区分事件种类");
        assertEquals(GROUP_OPENID, msg.getChatId());
    }

    // ==================== 发送 ====================

    /**
     * 测试：群回复走 /v2/groups 路径并带被动 msg_id。
     */
    @Test
    @DisplayName("sendToGroup-POST /v2/groups/{openid}/messages 且回带 msg_id")
    void shouldReplyGroupPassively() throws Exception {
        startedClient();
        feedFrame("{\"op\":0,\"s\":4,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":"
                + groupEventBody("群内提问") + "}");
        awaitInbound();

        BotSendResult result = client.sendToGroup(GROUP_OPENID, "收到");

        FakeQqGateway.HttpCall call = gateway.awaitCall("POST", "/v2/groups/", 5000L);
        assertNotNull(call, "群消息未打到 /v2/groups 前缀。" + dump());
        assertEquals("/v2/groups/" + GROUP_OPENID + "/messages", call.path);
        Map<?, ?> body = call.json();
        assertEquals("收到", body.get("content"));
        assertEquals(0, ((Number) body.get("msg_type")).intValue());
        assertEquals(INBOUND_MSG_ID, body.get("msg_id"), "回复用户消息应带被动 msg_id");
        assertTrue(result.isSuccess(), "发送应成功: " + result.getErrorMessage());
        assertEquals("SENT_GROUP_1", result.getMsgId());
    }

    /**
     * 测试：单聊回复走 /v2/users 路径。
     */
    @Test
    @DisplayName("sendText-POST /v2/users/{openid}/messages")
    void shouldSendUserText() {
        startedClient();
        BotSendResult result = client.sendText(USER_OPENID, "单聊回复");

        FakeQqGateway.HttpCall call = gateway.awaitCall("POST", "/v2/users/", 5000L);
        assertNotNull(call, "单聊消息未打到 /v2/users 前缀。" + dump());
        assertEquals("/v2/users/" + USER_OPENID + "/messages", call.path);
        assertEquals("单聊回复", call.json().get("content"));
        assertTrue(result.isSuccess(), "发送应成功: " + result.getErrorMessage());
    }

    /**
     * 测试：平台错误码透传为失败结果。
     */
    @Test
    @DisplayName("send-平台返回业务错误码时透传 code/message")
    void shouldSurfacePlatformError() {
        startedClient();
        gateway.route("POST", "/v2/users/" + USER_OPENID + "/messages", 401,
                call -> "{\"message\":\"鉴权失败\",\"code\":11201,\"err_code\":40012001}");

        BotSendResult result = client.sendText(USER_OPENID, "会失败");

        assertTrue(!result.isSuccess(), "鉴权失败应返回失败结果");
        assertEquals(11201, result.getErrorCode(), "应透传平台业务错误码");
    }

    /**
     * 测试：URL 图片走整文件上传再发富媒体。
     */
    @Test
    @DisplayName("sendImage(URL)-先 /files 上传再 msg_type=7 携带 media.file_info")
    void shouldSendImageByUrl() {
        startedClient();
        client.sendImage(USER_OPENID, "https://example.com/a.png");

        FakeQqGateway.HttpCall upload = gateway.awaitCall("POST",
                "/v2/users/" + USER_OPENID + "/files", 5000L);
        assertNotNull(upload, "未调用单聊文件上传接口。" + dump());
        Map<?, ?> uploadBody = upload.json();
        assertEquals(1, ((Number) uploadBody.get("file_type")).intValue(), "图片 file_type=1");
        assertEquals("https://example.com/a.png", uploadBody.get("url"));
        assertEquals(Boolean.FALSE, uploadBody.get("srv_send_msg"), "仅上传不应顺带发送");

        FakeQqGateway.HttpCall send = gateway.awaitCall("POST",
                "/v2/users/" + USER_OPENID + "/messages", 5000L);
        assertNotNull(send, "上传后未发送富媒体消息。" + dump());
        Map<?, ?> sendBody = send.json();
        assertEquals(7, ((Number) sendBody.get("msg_type")).intValue(), "富媒体 msg_type=7");
        assertEquals("FILEINFO_1", ((Map<?, ?>) sendBody.get("media")).get("file_info"));
    }

    /**
     * 测试：本地文件走分片上传四步流程。
     */
    @Test
    @DisplayName("sendImage(本地)-upload_prepare→PUT 分片→part_finish→/files 合并")
    void shouldSendLocalImageByChunks() throws Exception {
        startedClient();
        Path file = Files.createTempFile("qq-bot-unit", ".png");
        Files.write(file, new byte[4096]);
        try {
            client.sendImage(USER_OPENID, file.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(file);
        }

        FakeQqGateway.HttpCall prepare = gateway.awaitCall("POST",
                "/v2/users/" + USER_OPENID + "/upload_prepare", 5000L);
        assertNotNull(prepare, "本地文件未走 upload_prepare。" + dump());
        Map<?, ?> prepareBody = prepare.json();
        assertEquals(1, ((Number) prepareBody.get("file_type")).intValue());
        assertEquals("4096", String.valueOf(prepareBody.get("file_size")));
        assertNotNull(prepareBody.get("md5"), "upload_prepare 需要 md5");
        assertNotNull(prepareBody.get("sha1"), "upload_prepare 需要 sha1");
        assertNotNull(prepareBody.get("md5_10m"), "upload_prepare 需要 md5_10m");

        FakeQqGateway.HttpCall put = gateway.awaitCall("PUT", "/chunks/", 5000L);
        assertNotNull(put, "未 PUT 分片到预签名地址。" + dump());
        FakeQqGateway.HttpCall finish = gateway.awaitCall("POST",
                "/v2/users/" + USER_OPENID + "/upload_part_finish", 5000L);
        assertNotNull(finish, "未通知分片上传完成。" + dump());
        assertEquals(0, ((Number) finish.json().get("part_index")).intValue());

        FakeQqGateway.HttpCall merge = null;
        for (FakeQqGateway.HttpCall call : gateway.httpCalls()) {
            if ("POST".equals(call.method) && call.path.endsWith("/files")) {
                merge = call;
            }
        }
        assertNotNull(merge, "未携带 upload_id 调 /files 合并。" + dump());
        assertEquals("upload_unit_1", merge.json().get("upload_id"));
    }

    /**
     * 测试：语音/文件映射到正确 file_type。
     */
    @Test
    @DisplayName("sendVoice/sendFile-file_type 分别为 3 与 4")
    void shouldMapMediaFileTypes() {
        startedClient();
        client.sendVoice(USER_OPENID, "https://example.com/a.silk");
        client.sendFile(USER_OPENID, "https://example.com/a.pdf");

        List<Integer> types = new ArrayList<>();
        for (FakeQqGateway.HttpCall call : gateway.httpCalls()) {
            if (call.path.endsWith("/files") && "POST".equals(call.method)) {
                types.add(((Number) call.json().get("file_type")).intValue());
            }
        }
        assertEquals(List.of(3, 4), types, "语音 file_type=3、文件 file_type=4");
    }

    /**
     * 测试：群会话富媒体上传走群路径。
     */
    @Test
    @DisplayName("send(群图片)-上传与发送均落 /v2/groups 前缀")
    void shouldSendGroupMediaByGroupRoute() {
        startedClient();
        client.send(BotOutboundMessage.builder()
                .type(BotInboundMessage.Type.IMAGE)
                .toUser(GROUP_OPENID)
                .toGroup(true)
                .mediaPath("https://example.com/a.png")
                .build());

        FakeQqGateway.HttpCall upload = gateway.awaitCall("POST",
                "/v2/groups/" + GROUP_OPENID + "/files", 5000L);
        assertNotNull(upload, "群图片未走 /v2/groups/{openid}/files。" + dump());
        assertEquals(1, ((Number) upload.json().get("file_type")).intValue());

        FakeQqGateway.HttpCall send = gateway.awaitCall("POST",
                "/v2/groups/" + GROUP_OPENID + "/messages", 5000L);
        assertNotNull(send, "群图片未发送富媒体消息。" + dump());
        assertEquals(7, ((Number) send.json().get("msg_type")).intValue());
        assertEquals("FILEINFO_2", ((Map<?, ?>) send.json().get("media")).get("file_info"));
    }

    /**
     * 测试：普通断线后按 op6 resume。
     */
    @Test
    @DisplayName("断线-4000 关闭后重连并 op6 resume 携带 session_id")
    void shouldResumeAfterDisconnect() {
        pushReady("sess_resume_1");
        gateway.closeWebsocket(4000);

        String resume = awaitFrame("\"op\":6", 15000L);
        assertNotNull(resume, "重连后未发 op6 resume。" + dump());
        Map<?, ?> d = (Map<?, ?>) Json.fromJson(resume, Map.class).get("d");
        assertEquals("sess_resume_1", d.get("session_id"));
        assertEquals("QQBot " + ACCESS_TOKEN, d.get("token"));
    }

    /**
     * 测试：会话超时关闭码后必须重新 identify 而非 resume。
     */
    @Test
    @DisplayName("断线-4009 会话超时后重连重新 op2 identify")
    void shouldReidentifyAfterSessionTimeout() {
        pushReady("sess_expired_1");
        gateway.closeWebsocket(4009);

        String identify = awaitFrame("\"op\":2", 15000L);
        assertNotNull(identify, "会话超时后未重新 identify。" + dump());
        Map<?, ?> d = (Map<?, ?>) Json.fromJson(identify, Map.class).get("d");
        assertEquals("QQBot " + ACCESS_TOKEN, d.get("token"));
        assertNull(d.get("session_id"), "identify 帧不应携带旧 session_id");
    }

    // ==================== 端到端 ====================

    /**
     * 测试：WS 全链路群聊收发闭环。
     */
    @Test
    @DisplayName("端到端-WS 收群消息并回复，全链路打通")
    void shouldCompleteGroupRoundTripOverWebsocket() {
        connectedClient();
        pushReady("sess_e2e");
        gateway.pushDispatch(21, "GROUP_AT_MESSAGE_CREATE", groupEventBody("端到端提问"));

        BotInboundMessage msg = awaitInbound();
        assertEquals("端到端提问", msg.getContent());
        assertEquals(MEMBER_OPENID, msg.getFromUser());

        BotSendResult result = client.sendToGroup(msg.getChatId(), "端到端回复");
        assertTrue(result.isSuccess(), "回复失败: " + result.getErrorMessage());
        FakeQqGateway.HttpCall call = gateway.awaitCall("POST", "/v2/groups/", 5000L);
        assertNotNull(call, "回复未到达假网关。" + dump());
        assertEquals("端到端回复", call.json().get("content"));
    }

    /**
     * 测试：未 start 时不发送。
     */
    @Test
    @DisplayName("send-未 start 返回失败且不打网络请求")
    void shouldFailBeforeStart() {
        BotSendResult result = client.sendText(USER_OPENID, "hi");
        assertTrue(!result.isSuccess());
        assertTrue(gateway.httpCalls().isEmpty(), "未 start 不应有请求: " + dump());
    }

    /**
     * 测试：webhook 模式不会假装收消息。
     */
    @Test
    @DisplayName("start-配置 verifyToken 进入 webhook 模式")
    void shouldEnterWebhookMode() {
        client.configure(APP_ID, APP_SECRET, ACCESS_TOKEN);
        client.webhookVerifyToken("verify_token_1");
        client.start();
        assertTrue(client.isUseWebhookMode());
        assertNull(client.verifyChallenge("other_token"));
        assertEquals("verify_token_1", client.verifyChallenge("verify_token_1"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 仅启动客户端（进入 running 状态，不要求 WS 完成握手）。
     */
    private void startedClient() {
        client.configure(APP_ID, APP_SECRET, ACCESS_TOKEN);
        client.start();
    }

    /**
     * 以 botToken 方式启动并等待 WS 与 identify 完成。
     */
    private void connectedClient() {
        if (identified) {
            return;
        }
        client.configure(APP_ID, APP_SECRET, ACCESS_TOKEN);
        client.start();
        identifyFrame = awaitFrame("\"op\":2", 8000L);
        assertNotNull(identifyFrame, "未完成 identify 握手。" + dump());
        identified = true;
    }

    /**
     * 下行 READY 事件。
     *
     * @param sessionId 会话 id
     */
    private void pushReady(String sessionId) {
        connectedClient();
        gateway.pushDispatch(1, "READY", "{\"version\":1,\"session_id\":\"" + sessionId
                + "\",\"user\":{\"id\":\"bot_1\",\"username\":\"测试机器人\",\"bot\":true},"
                + "\"shard\":[0,0]}");
    }

    /**
     * 构造官方结构的群事件体。
     *
     * @param content 消息文本
     * @return 事件体 JSON
     */
    private static String groupEventBody(String content) {
        return "{\"id\":\"" + INBOUND_MSG_ID + "\","
                + "\"author\":{\"id\":\"" + MEMBER_OPENID + "\",\"member_openid\":\""
                + MEMBER_OPENID + "\",\"member_role\":\"member\",\"username\":\"小明\",\"bot\":false},"
                + "\"content\":\"" + content + "\","
                + "\"group_openid\":\"" + GROUP_OPENID + "\","
                + "\"message_type\":0,"
                + "\"timestamp\":\"2026-09-20T10:00:00+08:00\","
                + "\"mentions\":[{\"id\":\"" + USER_OPENID + "\",\"username\":\"路人\"}]}";
    }

    /**
     * 分片上传预响应：预签名地址指向本工装。
     *
     * @param call 预上传请求
     * @return 响应体
     */
    private String uploadPrepareBody(FakeQqGateway.HttpCall call) {
        return "{\"upload_id\":\"upload_unit_1\",\"block_size\":\"1048576\",\"parts\":[{"
                + "\"index\":0,\"presigned_url\":\"" + gateway.baseUrl()
                + "/chunks/part0\",\"block_size\":\"1048576\"}]}";
    }

    /**
     * 反射注入一条 WS 帧，隔离验证消息映射。
     *
     * @param rawFrame 帧原文
     * @throws Exception 反射失败
     */
    private void feedFrame(String rawFrame) throws Exception {
        Method method = QqBotClient.class.getDeclaredMethod("handleWsMessage", String.class);
        method.setAccessible(true);
        method.invoke(client, rawFrame);
    }

    /**
     * 等待入站消息。
     *
     * @return 入站消息
     */
    private BotInboundMessage awaitInbound() {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (!received.isEmpty()) {
                return received.get(0);
            }
            sleepQuietly(50L);
        }
        throw new AssertionError("未收到入站消息。" + dump());
    }

    /**
     * 等待上行帧。
     *
     * @param contains 片段
     * @param timeoutMs 超时
     * @return 帧原文
     */
    private String awaitFrame(String contains, long timeoutMs) {
        return gateway.awaitFrame(frame -> frame.contains(contains), timeoutMs);
    }

    /**
     * @return 现场证据
     */
    private String dump() {
        StringBuilder builder = new StringBuilder("\n  REST 调用: ");
        if (gateway.httpCalls().isEmpty()) {
            builder.append("(无)");
        }
        for (FakeQqGateway.HttpCall call : gateway.httpCalls()) {
            builder.append("\n    ").append(call.method).append(' ').append(call.path)
                    .append(" body=").append(trim(call.body));
        }
        builder.append("\n  监听器收到: ").append(received.size()).append(" 条");
        return builder.toString();
    }

    /**
     * JSON 数组转整数列表。
     *
     * @param value 数组对象
     * @return 整数列表，非数组返回空列表
     */
    private static List<Integer> toIntList(Object value) {
        List<Integer> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                values.add(item instanceof Number number ? number.intValue() : -1);
            }
        }
        return values;
    }

    /**
     * 截断长文本。
     *
     * @param value 原文
     * @return 截断结果
     */
    private static String trim(String value) {
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    /**
     * 静默 sleep。
     *
     * @param millis 毫秒
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
