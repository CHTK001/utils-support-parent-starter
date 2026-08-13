package com.chua.kimi.support;

import com.chua.common.support.lang.json.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Kimi 网页版协议常量与编解码工具。
 *
 * <p>核心调用基于 connect-rpc over HTTP：请求体为 <b>5 字节帧头 + JSON</b>（首字节标志位，
 * 后 4 字节大端长度），响应体为连续的 gRPC 帧流（同样 5 字节帧头，首字节最高位为 1 表示压缩/跳过）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class KimiProtocol {

    /**
     * Kimi 网页版基础地址。
     */
    public static final String BASE_URL = "https://www.kimi.com";

    /**
     * 对话接口路径（connect-rpc）。
     */
    public static final String CHAT_PATH = "/apiv2/kimi.gateway.chat.v1.ChatService/Chat";

    /**
     * refresh token 换取 access token 接口路径。
     */
    public static final String REFRESH_PATH = "/api/auth/token/refresh";

    /**
     * subscribers 接口路径。
     */
    public static final String SUBSCRIPTION_PATH = "/apiv2/kimi.gateway.order.v1.SubscriptionService/GetSubscription";

    /**
     * 默认场景标识。
     */
    public static final String SCENARIO = "SCENARIO_K2D5";

    /**
     * 帧头长度：1 字节标志 + 4 字节长度。
     */
    public static final int FRAME_HEADER_LENGTH = 5;

    /**
     * 保持类型标志（非压缩帧）。
     */
    public static final int FLAG_TYPE_KEEP = 0x00;

    private KimiProtocol() {
    }

    /**
     * 生成 device id（16 位纯数字，客户端持久化身份）。
     *
     * @return 随机 16 位数字字符串
     */
    public static String generateDeviceId() {
        long base = 7000000000000000000L + (long) (Math.random() * 999999999999999999L);
        return Long.toString(base);
    }

    /**
     * 生成 session id（16 位纯数字）。
     *
     * @return 随机 16 位数字字符串
     */
    public static String generateSessionId() {
        long base = 1700000000000000000L + (long) (Math.random() * 99999999999999999L);
        return Long.toString(base);
    }

    /**
     * 解析 JWT 的 payload（Base64URL 解码）。
     *
     * @param token JWT 字符串
     * @return payload JSON 对象，解析失败返回 null
     */
    public static JsonObject parseJwt(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String payload = parts[1];
            int pad = (4 - payload.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(payload);
            for (int i = 0; i < pad; i++) {
                sb.append('=');
            }
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(sb.toString()), StandardCharsets.UTF_8);
            return JsonObject.parse(decoded);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检测 token 类型：JWT access token 或 refresh token。
     *
     * @param token 原始 token
     * @return true 表示为 JWT access token
     */
    public static boolean isJwt(String token) {
        if (token == null || !token.startsWith("eyJ")) {
            return false;
        }
        JsonObject payload = parseJwt(token);
        if (payload == null) {
            return false;
        }
        Object appId = payload.get("app_id");
        Object typ = payload.get("typ");
        return "kimi".equals(appId) && "access".equals(typ);
    }

    /**
     * 对请求体做 connect 帧编码：5 字节帧头 + JSON 字节。
     *
     * <p>帧首字节：保留类型（0x00）；后 4 字节：大端无符号长度。</p>
     *
     * @param payload 请求参数
     * @return 编码后的完整请求字节
     */
    public static byte[] encodeConnectRequest(JsonObject payload) {
        byte[] body = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[FRAME_HEADER_LENGTH + body.length];
        frame[0] = FLAG_TYPE_KEEP;
        frame[1] = (byte) ((body.length >>> 24) & 0xFF);
        frame[2] = (byte) ((body.length >>> 16) & 0xFF);
        frame[3] = (byte) ((body.length >>> 8) & 0xFF);
        frame[4] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, frame, FRAME_HEADER_LENGTH, body.length);
        return frame;
    }
}