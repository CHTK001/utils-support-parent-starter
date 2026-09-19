package com.chua.kimi.support;

import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Kimi 会话客户端：负责 令牌 管理、设备指纹请求头与基础 HTTP 调用。
 *
 * <p>基于项目统一 {@link HttpClient} 抽象（自动选择 OkHttp/HttpClient5/JDK 实现）。
 * app键 支持两种 令牌：</p>
 * <ul>
 *   <li>JWT access token（{@code token=eyJ...}）：直接使用，过期前无需刷新</li>
 *   <li>refresh token：通过 {@code /api/auth/token/refresh} 换取短期 access token，自动缓存</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class KimiSession implements AutoCloseable {

    /**
     * 令牌 刷新提前量（秒），在过期前 5 分钟提前刷新。
     */
    private static final long REFRESH_BUFFER_SECONDS = 300;

    /**
     * 读取超时时间（毫秒）。
     */
    private static final int READ_TIMEOUT_MS = 120000;

    /**
     * 连接超时时间（毫秒）。
     */
    private static final int CONNECT_TIMEOUT_MS = 30000;

    /**
     * 基础地址。
     */
    private final String baseUrl;

    /**
     * 原始 令牌（app键）。
     */
    private final String rawToken;

    /**
     * 当前 access 令牌。
     */
    private volatile String accessToken;

    /**
     * 当前 access 令牌 过期时间（轮次 秒，0 表示未知）。
     */
    private volatile long expiresAt;

    /**
     * 当前 令牌 类型：JWT / refresh。
     */
    private volatile String tokenType;

    /**
     * 设备 标识（客户端持久化身份）。
     */
    private final String deviceId;

    /**
     * 会话 标识。
     */
    private final String sessionId;

    /**
     * 令牌 刷新互斥锁。
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * 构造 Kimi 会话客户端。
     *
     * @param appKey  原始 令牌（JWT 或 refresh 令牌）
     * @param baseUrl 基础地址，空 时用默认值
     */
    public KimiSession(String appKey, String baseUrl) {
        this.rawToken = appKey == null ? "" : appKey.strip();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? KimiProtocol.BASE_URL : stripTrailingSlash(baseUrl);
        this.deviceId = KimiProtocol.generateDeviceId();
        this.sessionId = KimiProtocol.generateSessionId();

        boolean jwt = KimiProtocol.isJwt(this.rawToken);
        if (jwt) {
            JsonObject payload = KimiProtocol.parseJwt(this.rawToken);
            Object expObj = payload == null ? null : payload.getObject("exp");
            if (expObj instanceof Number) {
                this.expiresAt = ((Number) expObj).longValue();
            }
        }
        this.accessToken = this.rawToken;
        this.tokenType = jwt ? "jwt" : "refresh";
    }

    /**
     * 获取当前有效的 access 令牌，必要时自动刷新。
     *
     * @return access 令牌
     * @throws RuntimeException 令牌 刷新失败时抛出
     */
    public String getAccessToken() {
        if (needsRefresh()) {
            refreshLock.lock();
            try {
                if (needsRefresh()) {
                    doRefresh();
                }
            } finally {
                refreshLock.unlock();
            }
        }
        return accessToken;
    }

    /**
     * 判断当前 令牌 是否需要刷新。
     * JWT 令牌 直接使用，过期后由调用方重新提供。
     *
     * @return true 需要刷新
     */
    private boolean needsRefresh() {
        if (!"refresh".equals(tokenType)) {
            return false;
        }
        if (expiresAt <= 0) {
            return true;
        }
        return Instant.now().getEpochSecond() > expiresAt - REFRESH_BUFFER_SECONDS;
    }

    /**
     * 使用 refresh 令牌 换取新的 access 令牌。
     *
     * @throws RuntimeException 刷新失败时抛出
     */
    private void doRefresh() {
        if (rawToken.isEmpty()) {
            throw new RuntimeException("Kimi token 未配置");
        }
        try {
            Map<String, String> headers = buildAuthHeaders();
            headers.put("Authorization", "Bearer " + rawToken);
            ClientResponse response = HttpClientFactory.of(baseUrl)
                    .path(KimiProtocol.REFRESH_PATH)
                    .headers(headers)
                    .connectTimeout(CONNECT_TIMEOUT_MS)
                    .readTimeout(READ_TIMEOUT_MS)
                    .get();
            if (response.getStatusCode() != 200) {
                throw new RuntimeException("Kimi token 刷新失败，HTTP " + response.getStatusCode() + ": "
                        + truncate(response.getBodyString(), 200));
            }
            JsonObject data = JsonObject.parse(response.getBodyString());
            Object tokenObj = data.getObject("access_token");
            if (tokenObj == null) {
                tokenObj = data.getObject("token");
            }
            if (tokenObj == null) {
                throw new RuntimeException("Kimi token 刷新响应缺少 access_token");
            }
            String newToken = tokenObj.toString();
            synchronized (this) {
                accessToken = newToken;
                tokenType = "jwt";
                JsonObject payload = KimiProtocol.parseJwt(newToken);
                Object expObj = payload == null ? null : payload.getObject("exp");
                expiresAt = (expObj instanceof Number) ? ((Number) expObj).longValue() : 0L;
            }
            log.debug("Kimi token 刷新成功，expires_at={}", expiresAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Kimi token 刷新异常: " + e.getMessage(), e);
        }
    }

    /**
     * 发起 连接 帧对话请求。
     *
     * @param encodedBody 连接 编码后的请求字节
     * @return HTTP 响应
     */
    public ClientResponse postChat(byte[] encodedBody) {
        Map<String, String> extra = buildHeaders();
        extra.put("Content-Type", "application/connect+json");
        return HttpClientFactory.of(baseUrl)
                .path(KimiProtocol.CHAT_PATH)
                .headers(extra)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .body(encodedBody)
                .post();
    }

    /**
     * 完整请求头（含认证）。
     *
     * @return 请求头映射
     */
    public Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Cache-Control", "no-cache");
        headers.put("Origin", baseUrl);
        headers.put("Referer", baseUrl + "/");
        headers.put("Pragma", "no-cache");
        headers.put("R-Timezone", "Asia/Shanghai");
        headers.put("User-Agent", buildUserAgent());
        headers.put("X-Msh-Device-Id", deviceId);
        headers.put("X-Msh-Session-Id", sessionId);
        headers.put("X-Msh-Platform", "web");
        String token = getAccessToken();
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    /**
     * 认证请求头（不含 device 指纹，用于 令牌 刷新）。
     *
     * @return 请求头映射
     */
    private Map<String, String> buildAuthHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Origin", baseUrl);
        headers.put("X-Msh-Device-Id", deviceId);
        headers.put("X-Msh-Session-Id", sessionId);
        headers.put("X-Msh-Platform", "web");
        headers.put("User-Agent", buildUserAgent());
        return headers;
    }

    /**
     * 构建浏览器指纹 用户-Agent。
     *
     * @return User-Agent 字符串
     */
    private static String buildUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    }

    /**
     * 去掉字符串末尾的斜杠。
     *
     * @param url 原始地址
     * @return 去末尾斜杠后的地址
     */
    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 截断文本便于日志输出。
     *
     * @param text 文本
     * @param max  最大长度
     * @return 截断后的文本
     */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 服务器地址（用户可配置）。
     * @return 获取baseurl的结果
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    /** 关闭 */
    public void close() {
        // HttpClientFactory.getClient() 为全局单例，不可由单会话关闭，仅释放会话内资源
    }
}
