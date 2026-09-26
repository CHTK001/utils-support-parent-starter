package com.chua.feishu.support.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.chua.common.support.ai.bot.BotInboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书 Webhook 回调接收测试
 *
 * <p>用飞书真实回调报文驱动 {@link FeishuBotClient#handleCallback(String, Map)}，
 * 覆盖 URL 验证握手、验证令牌比对、Encrypt Key 解密与验签，以及入站消息映射。
 * 全程不触网。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("飞书 Webhook 回调接收测试")
class FeishuWebhookCallbackTest {

    /**
     * 测试用验证令牌
     */
    private static final String VERIFY_TOKEN = "verify-token";

    /**
     * 测试用加密密钥
     */
    private static final String ENCRYPT_KEY = "encrypt-key-0123456789";

    /**
     * 飞书事件创建时间戳（毫秒）
     */
    private static final long EVENT_CREATE_TIME = 1700000123456L;

    /**
     * 已收听的入站消息
     */
    private final List<BotInboundMessage> received = new CopyOnWriteArrayList<>();

    /**
     * 测试：Url验证握手回显Challenge。
     */
    @Test
    @DisplayName("url_verification-握手回显challenge")
    void shouldEchoChallengeOnUrlVerification() {
        FeishuCallbackResult result = client(VERIFY_TOKEN, null)
                .handleCallback("{\"challenge\":\"chk-12345\","
                        + "\"token\":\"" + VERIFY_TOKEN + "\","
                        + "\"type\":\"url_verification\"}", Map.of());
        assertTrue(result.isSuccess(), "握手续返 200");
        assertEquals(200, result.getStatusCode());
        assertEquals("{\"challenge\":\"chk-12345\"}", result.getBody());
        assertTrue(received.isEmpty(), "握手不是消息事件");
    }

    /**
     * 测试：Url验证令牌不匹配时拒绝。
     */
    @Test
    @DisplayName("url_verification-令牌不匹配不得回显challenge")
    void shouldRejectUrlVerificationWithBadToken() {
        FeishuCallbackResult result = client(VERIFY_TOKEN, null)
                .handleCallback("{\"challenge\":\"chk-12345\","
                        + "\"token\":\"other\",\"type\":\"url_verification\"}",
                        Map.of());
        assertEquals(401, result.getStatusCode());
        assertFalse(result.getBody().contains("chk-12345"),
                "令牌不匹配时不得把 challenge 交出去");
    }

    /**
     * 测试：V2事件令牌取自header。
     */
    @Test
    @DisplayName("回调体-事件令牌按v2的header.token比对")
    void shouldMatchV2EventTokenFromHeader() {
        FeishuCallbackResult result = client(VERIFY_TOKEN, null)
                .handleCallback(messageEvent("oc_group", "group", ""),
                        Map.of());
        assertTrue(result.isSuccess(), "v2 header.token 应通过比对: "
                + result.getStatusCode() + result.getBody());
        assertEquals(1, received.size());
    }

    /**
     * 测试：未配令牌时不比对直接受理。
     */
    @Test
    @DisplayName("回调体-令牌匹配与否决定受理")
    void shouldGateCallbackOnRequestBodyToken() {
        assertTrue(client(null, null)
                .handleCallback(messageEvent("oc_group", "group", ""),
                        Map.of()).isSuccess(),
                "未配置验证令牌时不做比对");
        FeishuCallbackResult mismatched = client(VERIFY_TOKEN, null)
                .handleCallback(messageEvent("oc_group", "group", "")
                        .replace(VERIFY_TOKEN, "nope"), Map.of());
        assertEquals(401, mismatched.getStatusCode(),
                "令牌不匹配的回调必须被拒绝");
        assertEquals(1, received.size(), "被拒绝的回调不得再派发");
    }

    /**
     * 测试：群聊消息映射保真。
     */
    @Test
    @DisplayName("入站映射-群消息保留事件时间戳与提及列表")
    void shouldMapGroupMessageFaithfully() {
        client(null, null).handleCallback(
                messageEvent("oc_group", "group",
                        ",\"mentions\":[{\"key\":\"@_user_1\",\"name\":\"bot\","
                                + "\"id\":{\"open_id\":\"ou_bot\"}}]"),
                Map.of());
        assertEquals(1, received.size(), "回调须派发一条入站消息");
        BotInboundMessage inbound = received.get(0);
        assertEquals("om_message", inbound.getMsgId());
        assertEquals("hello bot", inbound.getContent());
        assertEquals("ou_sender", inbound.getFromUser());
        assertTrue(inbound.isFromGroup());
        assertEquals("oc_group", inbound.getChatId());
        assertEquals("im.message.receive_v1", inbound.getEventType());
        assertEquals(List.of("ou_bot"), inbound.getMentionedList());
        assertEquals(EVENT_CREATE_TIME, inbound.getCreateTime(),
                "createTime 须取事件自带时间戳, 非派发时刻的本机时钟");
        assertNull(inbound.getMentionedBot(),
                "机器人 open_id 未解析时群消息无法判定是否被 @");
    }

    /**
     * 测试：单聊消息判定为未被提及。
     */
    @Test
    @DisplayName("入站映射-单聊mentionedBot为false而非null")
    void shouldReportSingleChatAsNotMentioningBot() {
        client(null, null).handleCallback(
                messageEvent("oc_p2p", "p2p", ""), Map.of());
        assertEquals(1, received.size());
        BotInboundMessage inbound = received.get(0);
        assertFalse(inbound.isFromGroup());
        assertEquals(Boolean.FALSE, inbound.getMentionedBot(),
                "单聊必然不是 @ 机器人, 留 null 会让下游按未知处理");
    }

    /**
     * 测试：EncryptKey回调解密并验签。
     */
    @Test
    @DisplayName("EncryptKey-密文回调解密验签后派发")
    void shouldDecryptAndVerifySignedCallback() throws Exception {
        String plain = messageEvent("oc_group", "group", "");
        String body = encrypt(plain, ENCRYPT_KEY);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-1";
        FeishuCallbackResult result = client(VERIFY_TOKEN, ENCRYPT_KEY)
                .handleCallback(body, signedHeaders(timestamp, nonce,
                        signature(timestamp, nonce, ENCRYPT_KEY, body)));
        assertTrue(result.isSuccess(), "签名正确的密文回调应被受理: "
                + result.getStatusCode() + result.getBody());
        assertEquals(1, received.size(), "密文解开后须派发入站消息");
        assertEquals("om_message", received.get(0).getMsgId());
    }

    /**
     * 测试：签名被篡改时拒绝。
     */
    @Test
    @DisplayName("EncryptKey-签名不符时拒绝且不派发")
    void shouldRejectCallbackWithTamperedSignature() throws Exception {
        String body = encrypt(messageEvent("oc_group", "group", ""),
                ENCRYPT_KEY);
        String timestamp = String.valueOf(System.currentTimeMillis());
        FeishuCallbackResult result = client(VERIFY_TOKEN, ENCRYPT_KEY)
                .handleCallback(body, signedHeaders(timestamp, "nonce-2",
                        signature(timestamp, "nonce-2", "wrong-key",
                                body)));
        assertFalse(result.isSuccess(), "签名不符的回调必须被拒绝");
        assertTrue(received.isEmpty(), "签名不符时不得派发");
    }

    /**
     * 测试：密文回调缺密钥时报错。
     */
    @Test
    @DisplayName("EncryptKey-未配置密钥时无法解密密文回调")
    void shouldFailOnEncryptedCallbackWithoutKey() throws Exception {
        String body = encrypt(messageEvent("oc_group", "group", ""),
                ENCRYPT_KEY);
        FeishuCallbackResult result = client(null, null)
                .handleCallback(body, Map.of());
        assertFalse(result.isSuccess(), "缺密钥不能假装解开了密文");
        assertTrue(received.isEmpty());
    }

    /**
     * 测试：后台关闭加密后明文体仍受理。
     */
    @Test
    @DisplayName("EncryptKey-持有密钥但回调为明文时仍受理")
    void shouldAcceptPlainCallbackWhenEncryptKeyConfigured() {
        FeishuCallbackResult result = client(null, ENCRYPT_KEY)
                .handleCallback(messageEvent("oc_group", "group", ""),
                        Map.of());
        assertTrue(result.isSuccess(),
                "后台可能已关掉加密, 持有密钥不该让明文体解不开: "
                        + result.getStatusCode() + result.getBody());
        assertEquals(1, received.size());
    }

    /**
     * 测试：密文握手无令牌时仍回显。
     */
    @Test
    @DisplayName("EncryptKey-密文握手未配验证令牌也要回显challenge")
    void shouldEchoChallengeForEncryptedHandshakeWithoutToken()
            throws Exception {
        String body = encrypt("{\"challenge\":\"chk-9\",\"token\":\"\","
                + "\"type\":\"url_verification\"}", ENCRYPT_KEY);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-4";
        FeishuCallbackResult result = client(null, ENCRYPT_KEY)
                .handleCallback(body, signedHeaders(timestamp, nonce,
                        signature(timestamp, nonce, ENCRYPT_KEY, body)));
        assertTrue(result.isSuccess(), "只配密钥不配令牌不得炸握手: "
                + result.getStatusCode() + result.getBody());
        assertTrue(result.getBody().contains("chk-9"),
                "密文握手同样要回显 challenge");
    }

    /**
     * 测试：非法回调体被拒绝。
     */
    @Test
    @DisplayName("回调体-空体与非对象JSON返400")
    void shouldRejectMalformedCallbackBody() {
        FeishuBotClient client = client(null, null);
        assertEquals(400, client.handleCallback(null, Map.of())
                .getStatusCode());
        assertEquals(400, client.handleCallback("  ", Map.of())
                .getStatusCode());
        assertEquals(400, client.handleCallback("[1,2]", Map.of())
                .getStatusCode(), "数组不是合法的回调体");
        assertEquals(400, client.handleCallback("{ not json", Map.of())
                .getStatusCode());
        assertTrue(received.isEmpty());
    }

    /**
     * 测试：头键大小写不影响验签。
     */
    @Test
    @DisplayName("请求头-小写键同样能取到签名")
    void shouldReadHeadersRegardlessOfCase() throws Exception {
        String body = encrypt(messageEvent("oc_group", "group", ""),
                ENCRYPT_KEY);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-3";
        String sign = signature(timestamp, nonce, ENCRYPT_KEY, body);
        FeishuCallbackResult upper = client(VERIFY_TOKEN, ENCRYPT_KEY)
                .handleCallback(body, Map.of(
                        "X-Lark-Request-Timestamp", timestamp,
                        "X-Lark-Request-Nonce", nonce,
                        "X-Lark-Signature", sign));
        assertTrue(upper.isSuccess(),
                "容器给出的是原始大小写, SDK 按小写取头: "
                        + upper.getStatusCode() + upper.getBody());
    }

    /**
     * 构造客户端并挂上入站监听
     *
     * @param verifyToken 验证令牌，可为 null
     * @param encryptKey  加密密钥，可为 null
     * @return 客户端
     */
    private FeishuBotClient client(String verifyToken, String encryptKey) {
        FeishuBotClient client = new FeishuBotClient();
        client.token("cli_test").secret("secret_test");
        client.webhookVerifyToken(verifyToken);
        client.webhookEncryptKey(encryptKey);
        client.addMessageListener(received::add);
        return client;
    }

    /**
     * 拼出 im.message.receive_v1 的 v2 回调体
     *
     * @param chatId    会话 ID
     * @param chatType  会话类型
     * @param mentions  提及片段，形如 {@code ,"mentions":[...]}
     * @return 回调体
     */
    private static String messageEvent(String chatId, String chatType,
            String mentions) {
        String content = "{\"text\":\"hello bot\"}"
                .replace("\"", "\\\"");
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"evt_1\","
                + "\"event_type\":\"im.message.receive_v1\","
                + "\"create_time\":\"1700000000000\",\"token\":\""
                + VERIFY_TOKEN + "\","
                + "\"app_id\":\"cli_test\",\"tenant_key\":\"tk\"},"
                + "\"event\":{\"sender\":{\"sender_id\":{\"open_id\":"
                + "\"ou_sender\",\"union_id\":\"on_sender\"},"
                + "\"sender_type\":\"user\"},"
                + "\"message\":{\"message_id\":\"om_message\","
                + "\"create_time\":\"" + EVENT_CREATE_TIME + "\","
                + "\"update_time\":\"" + EVENT_CREATE_TIME + "\","
                + "\"chat_id\":\"" + chatId + "\","
                + "\"chat_type\":\"" + chatType + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content
                + "\"" + mentions + "}}}";
    }

    /**
     * 组出带签名的请求头
     *
     * @param timestamp 时间戳头
     * @param nonce     随机串头
     * @param signature 签名头
     * @return 请求头
     */
    private static Map<String, String> signedHeaders(String timestamp,
            String nonce, String signature) {
        return Map.of("X-Lark-Request-Timestamp", timestamp,
                "X-Lark-Request-Nonce", nonce,
                "X-Lark-Signature", signature);
    }

    /**
     * 按飞书口径计算签名
     * <p>参与摘要的四个片段均为 ASCII，故与 SDK 使用平台默认字符集结果一致。</p>
     *
     * @param timestamp  时间戳
     * @param nonce      随机串
     * @param encryptKey 加密密钥
     * @param body       回调体
     * @return 十六进制签名
     * @throws Exception 摘要算法不可用
     */
    private static String signature(String timestamp, String nonce,
            String encryptKey, String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(
                (timestamp + nonce + encryptKey + body)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 按 SDK 的 Decryptor 口径加密回调体
     *
     * @param plain      明文回调体
     * @param encryptKey 加密密钥
     * @return {@code {"encrypt":"..."}} 形态的回调体
     * @throws Exception 加密失败
     */
    private static String encrypt(String plain, String encryptKey)
            throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        byte[] sealed = cipher.doFinal(
                plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
        return "{\"encrypt\":\""
                + Base64.getEncoder().encodeToString(combined) + "\"}";
    }
}
