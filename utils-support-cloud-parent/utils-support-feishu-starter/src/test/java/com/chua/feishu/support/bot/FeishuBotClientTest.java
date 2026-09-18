package com.chua.feishu.support.bot;

import com.chua.common.support.ai.bot.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞书 机器人 客户端 单元测试
 *
 * <p>覆盖配置、生命周期、消息发送、群组操作、监听器、配置获取等核心逻辑。
 * 依赖真实飞书 API 的测试标记为 {@code @Disabled}，待凭据就绪后启用。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@DisplayName("飞书 机器人 客户端测试")
class FeishuBotClientTest {

    private static final String TEST_APP_ID = "test_app_id";
    private static final String TEST_APP_SECRET = "test_app_secret";
    private static final String TEST_USER = "ou_test_user";
    private static final String TEST_GROUP = "oc_test_group";

    private final FeishuBotClient client = new FeishuBotClient();

    // ==================== 配置类测试 ====================

    @Test
    @DisplayName("configure-设置appId和appSecret")
    void shouldConfigureAppIdAndSecret() {
        BotClient result = client.configure(TEST_APP_ID, TEST_APP_SECRET, null);
        assertSame(client, result);
    }

    @Test
    @DisplayName("configure-appId为null时保留null,getConfig返回空串占位")
    void shouldHandleNullAppIdInConfigure() {
        FeishuBotClient fresh = new FeishuBotClient();
        fresh.configure(null, "secret", null);
        // appId 为 null 时 getConfig 返回空串占位
        assertEquals("", fresh.getConfig().get("appId"));
    }

    @Test
    @DisplayName("configure-appId为空格时赋值空格(getConfig原样返回)")
    void shouldPreserveWhitespaceAppIdInConfigure() {
        FeishuBotClient fresh = new FeishuBotClient();
        fresh.configure(" ", "secret", null);
        // 实现直接赋值,不做 trim,getConfig 原样返回
        assertEquals(" ", fresh.getConfig().get("appId"));
    }

    @Test
    @DisplayName("token-单独设置appId")
    void shouldSetAppIdViaToken() {
        client.token(TEST_APP_ID);
        assertEquals(TEST_APP_ID, client.getConfig().get("appId"));
    }

    @Test
    @DisplayName("secret-单独设置appSecret")
    void shouldSetAppSecretViaSecret() {
        BotClient result = client.secret(TEST_APP_SECRET);
        assertSame(client, result);
    }

    @Test
    @DisplayName("encodingAesKey-飞书不支持,直接返回this")
    void shouldIgnoreEncodingAesKey() {
        BotClient result = client.encodingAesKey("aes_key");
        assertSame(client, result);
    }

    @Test
    @DisplayName("baseUrl-默认值")
    void shouldHaveDefaultBaseUrl() {
        assertEquals("https://open.feishu.cn/open-apis", client.getConfig().get("baseUrl"));
    }

    @Test
    @DisplayName("baseUrl-自定义")
    void shouldSetCustomBaseUrl() {
        client.baseUrl("https://custom.example.com/open-apis");
        assertEquals("https://custom.example.com/open-apis", client.getConfig().get("baseUrl"));
    }

    @Test
    @DisplayName("baseUrl-空字符串不覆盖")
    void shouldNotOverrideBaseUrlWhenEmpty() {
        client.baseUrl("https://original.example.com");
        client.baseUrl("");
        assertEquals("https://original.example.com", client.getConfig().get("baseUrl"));
    }

    @Test
    @DisplayName("connectTimeoutMillis-设置")
    void shouldSetConnectTimeout() {
        BotClient result = client.connectTimeoutMillis(5000L);
        assertSame(client, result);
    }

    @Test
    @DisplayName("readTimeoutMillis-设置")
    void shouldSetReadTimeout() {
        BotClient result = client.readTimeoutMillis(15000L);
        assertSame(client, result);
    }

    @Test
    @DisplayName("configSaveOrLoader-直接返回this")
    void shouldReturnThisForConfigLoader() {
        BotClient result = client.configSaveOrLoader(null);
        assertSame(client, result);
    }

    @Test
    @DisplayName("webhookVerifyToken-设置")
    void shouldSetWebhookVerifyToken() {
        client.webhookVerifyToken("challenge_token");
        assertEquals(false, client.isUseWebhookMode(), "未start前仍为轮询模式");
    }

    @Test
    @DisplayName("userStore-设置")
    void shouldSetUserStore() {
        BotClient result = client.userStore(new InMemoryBotUserStore());
        assertSame(client, result);
    }

    @Test
    @DisplayName("userStore-null不覆盖")
    void shouldNotOverrideUserStoreWithNull() {
        InMemoryBotUserStore store = new InMemoryBotUserStore();
        client.userStore(store);
        client.userStore(null);
        assertNotNull(client.listUsers());
    }

    // ==================== 生命周期测试 ====================

    @Test
    @DisplayName("isRunning-默认false")
    void shouldNotBeRunningInitially() {
        assertFalse(client.isRunning());
    }

    @Test
    @DisplayName("start-appId为空时抛出IllegalStateException")
    void shouldThrowWhenAppIdMissing() {
        FeishuBotClient fresh = new FeishuBotClient();
        fresh.configure(null, TEST_APP_SECRET, null);
        assertThrows(IllegalStateException.class, fresh::start);
    }

    @Test
    @DisplayName("start-appSecret为空时抛出IllegalStateException")
    void shouldThrowWhenAppSecretMissing() {
        FeishuBotClient fresh = new FeishuBotClient();
        fresh.configure(TEST_APP_ID, null, null);
        assertThrows(IllegalStateException.class, fresh::start);
    }

    @Test
    @DisplayName("start-缺少凭证时抛出")
    void shouldThrowWhenBothMissing() {
        FeishuBotClient fresh = new FeishuBotClient();
        assertThrows(IllegalStateException.class, fresh::start);
    }

    @Test
    @DisplayName("stop-未start时调用不抛异常")
    void shouldStopSafelyWhenNotStarted() {
        assertDoesNotThrow(() -> client.stop());
    }

    @Test
    @DisplayName("stop-stop后isRunning为false")
    void shouldNotBeRunningAfterStop() {
        client.stop();
        assertFalse(client.isRunning());
    }

    @Test
    @Disabled("需要真实 appId/appSecret 及网络访问飞书开放平台")
    @DisplayName("start+stop-真实凭据完整生命周期")
    void shouldStartAndStopWithRealCredentials() {
        FeishuBotClient real = new FeishuBotClient();
        real.configure(
                System.getenv().getOrDefault("FEISHU_APP_ID", "real_app_id"),
                System.getenv().getOrDefault("FEISHU_APP_SECRET", "real_app_secret"),
                null);
        real.start();
        assertTrue(real.isRunning());
        sleepQuietly(2000);
        real.stop();
        assertFalse(real.isRunning());
    }

    // ==================== 发送消息测试 ====================

    @Test
    @DisplayName("sendText-未start时返回fail")
    void shouldFailWhenNotStarted() {
        BotSendResult result = client.sendText(TEST_USER, "hello");
        assertFalse(result.isSuccess());
        assertEquals(-1, result.getErrorCode());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("sendTextAsync-未start时返回fail")
    void shouldFailAsyncWhenNotStarted() {
        CompletableFuture<BotSendResult> future = client.sendTextAsync(TEST_USER, "hello");
        try {
            BotSendResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
        } catch (Exception e) {
            fail("不应抛出异常: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("sendImage-未start时返回fail")
    void shouldFailImageWhenNotStarted() {
        BotSendResult result = client.sendImage(TEST_USER, "/tmp/test.png");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("sendVoice-飞书不支持,返回fail")
    void shouldFailVoice() {
        BotSendResult result = client.sendVoice(TEST_USER, "/tmp/test.mp3");
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().toLowerCase().contains("voice")
                || result.getErrorMessage().toLowerCase().contains("support"),
                "错误信息应提及 voice/support, 实际: " + result.getErrorMessage());
    }

    @Test
    @DisplayName("sendVideo-飞书不支持,返回fail")
    void shouldFailVideo() {
        BotSendResult result = client.sendVideo(TEST_USER, "/tmp/test.mp4", "title", "desc");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("sendFile-飞书不支持,返回fail")
    void shouldFailFile() {
        BotSendResult result = client.sendFile(TEST_USER, "/tmp/test.pdf");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("send-消息类型为null且未start时返回Client not started")
    void shouldFailWhenMessageTypeNull() {
        BotOutboundMessage msg = BotOutboundMessage.builder()
                .toUser(TEST_USER)
                .content("hello")
                .build();
        BotSendResult result = client.send(msg);
        assertFalse(result.isSuccess());
        // 未 start 时 send 先检查 client==null, 返回 "Client not started"
        assertEquals("Client not started", result.getErrorMessage());
    }

    @Test
    @DisplayName("send-未start时任意类型消息返回fail")
    void shouldFailSendWhenNotStarted() {
        BotOutboundMessage msg = BotOutboundMessage.text(TEST_USER, "hello");
        BotSendResult result = client.send(msg);
        assertFalse(result.isSuccess());
    }

    @Test
    @Disabled("需要真实 appId/appSecret 及网络访问飞书开放平台")
    @DisplayName("sendText-真实发送文本到用户")
    void shouldSendRealText() {
        FeishuBotClient real = new FeishuBotClient();
        real.configure(
                System.getenv().getOrDefault("FEISHU_APP_ID", "real_app_id"),
                System.getenv().getOrDefault("FEISHU_APP_SECRET", "real_app_secret"),
                null);
        real.start();
        BotSendResult result = real.sendText(
                System.getenv().getOrDefault("FEISHU_TEST_USER", TEST_USER), "test message");
        assertTrue(result.isSuccess(), "发送应成功, 实际: " + result.getErrorMessage());
        real.stop();
    }

    @Test
    @Disabled("需要真实 appId/appSecret 及网络访问飞书开放平台")
    @DisplayName("sendImage-真实发送图片到用户")
    void shouldSendRealImage() {
        FeishuBotClient real = new FeishuBotClient();
        real.configure(
                System.getenv().getOrDefault("FEISHU_APP_ID", "real_app_id"),
                System.getenv().getOrDefault("FEISHU_APP_SECRET", "real_app_secret"),
                null);
        real.start();
        String imagePath = System.getenv().getOrDefault("FEISHU_TEST_IMAGE", "");
        BotSendResult result = real.sendImage(TEST_USER, imagePath);
        if (imagePath.isEmpty()) {
            assertFalse(result.isSuccess());
        } else {
            assertTrue(result.isSuccess(), "图片发送应成功, 实际: " + result.getErrorMessage());
        }
        real.stop();
    }

    // ==================== 群组操作测试 ====================

    @Test
    @DisplayName("listGroups-未start时返回空列表")
    void shouldReturnEmptyWhenNotStarted() {
        List<BotGroupInfo> groups = client.listGroups();
        assertNotNull(groups);
        assertTrue(groups.isEmpty());
    }

    @Test
    @DisplayName("sendToGroup-未start时返回fail")
    void shouldFailSendToGroupWhenNotStarted() {
        BotSendResult result = client.sendToGroup(TEST_GROUP, "hello");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("sendToGroupAsync-未start时返回fail")
    void shouldFailSendToGroupAsyncWhenNotStarted() {
        CompletableFuture<BotSendResult> future = client.sendToGroupAsync(TEST_GROUP, "hello");
        try {
            BotSendResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
        } catch (Exception e) {
            fail("不应抛出异常: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("sendToGroupMention-未start时返回fail")
    void shouldFailMentionWhenNotStarted() {
        BotSendResult result = client.sendToGroupMention(TEST_GROUP, "hello", List.of(TEST_USER));
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("sendToGroupMentionAsync-未start时返回fail")
    void shouldFailMentionAsyncWhenNotStarted() {
        CompletableFuture<BotSendResult> future =
                client.sendToGroupMentionAsync(TEST_GROUP, "hello", List.of(TEST_USER));
        try {
            BotSendResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
        } catch (Exception e) {
            fail("不应抛出异常: " + e.getMessage());
        }
    }

    @Test
    @Disabled("需要真实 appId/appSecret 及网络访问飞书开放平台")
    @DisplayName("listGroups-真实获取群组列表")
    void shouldListRealGroups() {
        FeishuBotClient real = new FeishuBotClient();
        real.configure(
                System.getenv().getOrDefault("FEISHU_APP_ID", "real_app_id"),
                System.getenv().getOrDefault("FEISHU_APP_SECRET", "real_app_secret"),
                null);
        real.start();
        List<BotGroupInfo> groups = real.listGroups();
        assertNotNull(groups, "应返回群组列表(可为空)");
        real.stop();
    }

    // ==================== 监听器测试 ====================

    @Test
    @DisplayName("addMessageListener-注册")
    void shouldAddMessageListener() {
        BotClient result = client.addMessageListener(msg -> {});
        assertSame(client, result);
    }

    @Test
    @DisplayName("addMessageListener-null不注册")
    void shouldNotAddNullListener() {
        BotClient result = client.addMessageListener(null);
        assertSame(client, result);
    }

    @Test
    @DisplayName("removeMessageListener-移除已注册监听器")
    void shouldRemoveMessageListener() {
        BotMessageListener listener = msg -> {};
        client.addMessageListener(listener);
        BotClient result = client.removeMessageListener(listener);
        assertSame(client, result);
    }

    @Test
    @DisplayName("removeMessageListener-未注册监听器不抛异常")
    void shouldNotThrowWhenRemovingUnregistered() {
        assertDoesNotThrow(() -> client.removeMessageListener(msg -> {}));
    }

    @Test
    @DisplayName("addErrorListener-注册")
    void shouldAddErrorListener() {
        BotClient result = client.addErrorListener(err -> {});
        assertSame(client, result);
    }

    @Test
    @DisplayName("addErrorListener-null不注册")
    void shouldNotAddNullErrorListener() {
        BotClient result = client.addErrorListener(null);
        assertSame(client, result);
    }

    // ==================== 配置获取测试 ====================

    @Test
    @DisplayName("getConfig-包含appId")
    void shouldContainAppId() {
        client.configure(TEST_APP_ID, TEST_APP_SECRET, null);
        Map<String, Object> config = client.getConfig();
        assertEquals(TEST_APP_ID, config.get("appId"));
    }

    @Test
    @DisplayName("getConfig-包含baseUrl")
    void shouldContainBaseUrl() {
        Map<String, Object> config = client.getConfig();
        assertNotNull(config.get("baseUrl"));
    }

    @Test
    @DisplayName("getConfig-包含running状态")
    void shouldContainRunningStatus() {
        Map<String, Object> config = client.getConfig();
        assertNotNull(config.get("running"));
    }

    @Test
    @DisplayName("getConfig-包含useWebhookMode状态")
    void shouldContainWebhookModeStatus() {
        Map<String, Object> config = client.getConfig();
        assertNotNull(config.get("useWebhookMode"));
    }

    @Test
    @DisplayName("verifyChallenge-未设置verifyToken时原样返回")
    void shouldReturnChallengeWhenNoVerifyToken() {
        assertEquals("test_challenge", client.verifyChallenge("test_challenge"));
    }

    @Test
    @DisplayName("verifyChallenge-匹配时返回challenge")
    void shouldReturnChallengeWhenMatch() {
        client.webhookVerifyToken("secret_challenge");
        assertEquals("secret_challenge", client.verifyChallenge("secret_challenge"));
    }

    @Test
    @DisplayName("verifyChallenge-不匹配时返回null")
    void shouldReturnNullWhenNotMatch() {
        client.webhookVerifyToken("secret_challenge");
        assertNull(client.verifyChallenge("wrong_challenge"));
    }

    // ==================== 出站消息工厂测试 ====================

    @Test
    @DisplayName("BotOutboundMessage.text-创建文本消息")
    void shouldCreateTextMessage() {
        BotOutboundMessage msg = BotOutboundMessage.text(TEST_USER, "hello");
        assertEquals(BotInboundMessage.Type.TEXT, msg.getType());
        assertEquals(TEST_USER, msg.getToUser());
        assertEquals("hello", msg.getContent());
        assertFalse(msg.isToGroup());
    }

    @Test
    @DisplayName("BotOutboundMessage.groupText-创建群组文本消息")
    void shouldCreateGroupTextMessage() {
        BotOutboundMessage msg = BotOutboundMessage.groupText(TEST_GROUP, "hello");
        assertTrue(msg.isToGroup());
        assertEquals(TEST_GROUP, msg.getToUser());
    }

    @Test
    @DisplayName("BotOutboundMessage.groupTextMention-创建群组@提及消息")
    void shouldCreateGroupMentionMessage() {
        BotOutboundMessage msg = BotOutboundMessage.groupTextMention(TEST_GROUP, "hello", List.of(TEST_USER));
        assertTrue(msg.isToGroup());
        assertEquals(List.of(TEST_USER), msg.getMentionedUsers());
    }

    @Test
    @DisplayName("BotOutboundMessage.image-创建图片消息")
    void shouldCreateImageMessage() {
        BotOutboundMessage msg = BotOutboundMessage.image(TEST_USER, "/tmp/img.png");
        assertEquals(BotInboundMessage.Type.IMAGE, msg.getType());
        assertEquals("/tmp/img.png", msg.getMediaPath());
    }

    @Test
    @DisplayName("BotOutboundMessage.voice-创建语音消息")
    void shouldCreateVoiceMessage() {
        BotOutboundMessage msg = BotOutboundMessage.voice(TEST_USER, "/tmp/voice.mp3");
        assertEquals(BotInboundMessage.Type.VOICE, msg.getType());
    }

    // ==================== 发送结果测试 ====================

    @Test
    @DisplayName("BotSendResult.ok-创建成功结果")
    void shouldCreateOkResult() {
        BotSendResult result = BotSendResult.ok("msg_id_123");
        assertTrue(result.isSuccess());
        assertEquals("msg_id_123", result.getMsgId());
    }

    @Test
    @DisplayName("BotSendResult.fail-创建失败结果")
    void shouldCreateFailResult() {
        BotSendResult result = BotSendResult.fail(400, "bad request");
        assertFalse(result.isSuccess());
        assertEquals(400, result.getErrorCode());
        assertEquals("bad request", result.getErrorMessage());
    }

    // ==================== SPI工厂测试 ====================

    @Test
    @DisplayName("FeishuBotClientFactory-create创建FeishuBotClient")
    void shouldCreateFeishuBotClient() {
        FeishuBotClientFactory factory = new FeishuBotClientFactory();
        BotClient result = factory.create();
        assertNotNull(result);
        assertTrue(result instanceof FeishuBotClient);
    }

    @Test
    @DisplayName("FeishuBotClientFactory-builder返回Builder")
    void shouldReturnBuilder() {
        FeishuBotClientFactory factory = new FeishuBotClientFactory();
        BotClient.Builder builder = factory.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("BotClient.auto-feishu-通过SPI获取FeishuBotClient")
    void shouldAutoLoadFeishuClient() {
        BotClient result = BotClient.auto("feishu");
        assertNotNull(result);
        assertTrue(result instanceof FeishuBotClient);
    }

    @Test
    @DisplayName("BotClient.builder-feishu-通过SPI获取Builder")
    void shouldAutoLoadFeishuBuilder() {
        BotClient.Builder builder = BotClient.builder("feishu");
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Builder-token-设置appId")
    void shouldSetTokenViaBuilder() {
        BotClient.Builder builder = new FeishuBotClientFactory().builder();
        builder.token(TEST_APP_ID);
        BotClient result = builder.build();
        assertTrue(result instanceof FeishuBotClient);
        assertEquals(TEST_APP_ID, result.getConfig().get("appId"));
    }

    @Test
    @DisplayName("Builder-secret-设置appSecret")
    void shouldSetSecretViaBuilder() {
        BotClient.Builder builder = new FeishuBotClientFactory().builder();
        builder.secret(TEST_APP_SECRET);
        BotClient result = builder.build();
        assertTrue(result instanceof FeishuBotClient);
    }

    // ==================== 工具方法 ====================

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
