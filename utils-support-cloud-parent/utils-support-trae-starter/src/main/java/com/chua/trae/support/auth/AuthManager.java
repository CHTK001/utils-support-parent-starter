package com.chua.trae.support.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/**
   * Trae 认证管理器，从 Trae IDE 的 storage.json 或手动 令牌 中读取 JWT 凭证。
   * 支持 CN/SG 双版本、令牌 过期检测与缓存失效。
 *
 * <p>认证优先级：
 * <ol>
 *   <li>手动 Token（JWT 格式，解析 payload 获取过期时间）</li>
 *   <li>storage.json 中 iCubeAuthInfo 节点（明文或加密）</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class); // 日志
    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /** storage.json 中认证信息对应的 JSON 键 */
    private static final String AUTH_KEY = "iCubeAuthInfo://icube.cloudide";
    /** JWT 前缀，用于识别手动 令牌 */
    private static final String JWT_PREFIX = "eyJ";

    /** Trae 版本：cn 或 sg，不可为 空 */
    private final String edition;
    /** Trae 数据目录（storage.json 所在 用户/全局storage 路径），可为 空 */
    private final String dataDir;
    /** 手动 JWT 令牌，可为 空（优先于 数据dir 读取） */
    private final String manualToken;
    /** Trae API 主机地址，用于手动 令牌 场景 */
    private final String apiHost;

    /** 认证快照缓存，volatile 保证多线程可见性 */
    private volatile AuthSnapshot cached;

    /**
      * 认证快照，承载从 Trae 存储或手动 令牌 中解析出的全部认证信息。
     *
     * @param token JWT 访问令牌，不可为 空
     * @param refreshToken 刷新令牌，手动 令牌 场景为 空
     * @param expiredAt 过期时间（ISO-8601），未知时为 空
     * @param refreshTokenExpiredAt 刷新令牌过期时间，未知时为 空
     * @param userId 用户 标识，从 JWT payload 解析
     * @param host API 主机地址
     * @param userRegion 用户区域（CN/SG/US）
     * @param account 账号名
     * @param edition 版本来源：cn / sg / manual
     */
    public record AuthSnapshot(
        String token,
        String refreshToken,
        String expiredAt,
        String refreshTokenExpiredAt,
        String userId,
        String host,
        String userRegion,
        String account,
        String edition
    ) {}

    /**
     * 创建认证管理器。
     *
     * @param edition Trae 版本（cn/sg），空 时默认 cn
     * @param dataDir Trae 数据目录，可为 空（使用 manual令牌 时）
     * @param manualToken 手动 JWT 令牌，可为 空
     * @param apiHost API 主机地址，手动 令牌 场景必填
     */
    public AuthManager(String edition, String dataDir, String manualToken, String apiHost) {
        this.edition = (edition != null ? edition : "cn").toLowerCase();
        this.dataDir = dataDir;
        this.manualToken = manualToken;
        this.apiHost = apiHost;
    }

    /**
     * 基于 Trae 数据目录创建认证管理器。
     *
     * @param traeDataDir Trae 安装数据目录（如 C:\用户\xxx\app数据\Roaming\Trae CN），不可为 空
     * @return 新的 认证管理器 实例
     */
    public AuthManager fromTraeDataDir(Path traeDataDir) {
        Objects.requireNonNull(traeDataDir, "traeDataDir must not be null");
        return new AuthManager(this.edition,
            traeDataDir.resolve("User").resolve("globalStorage").toString(),
            this.manualToken, this.apiHost);
    }

    /**
     * 获取认证快照，优先使用缓存，过期或无缓存时重新加载。
     *
     * @return 认证快照，不可为 空
     * @throws AuthException 当无法读取认证信息或 令牌 格式无效时
     */
    public synchronized AuthSnapshot getAuth() throws AuthException {
        if (cached != null && !isExpired(cached)) {
            return cached;
        }

        if (manualToken != null && manualToken.startsWith(JWT_PREFIX)) {
            log.info("[auth] Using manual token");
            return parseManualToken(manualToken);
        }

        if (dataDir == null) {
            throw new AuthException("dataDir is null and no manual token provided");
        }

        try {
            Path storagePath = Path.of(dataDir, "User", "globalStorage", "storage.json");
            if (!Files.exists(storagePath)) {
                throw new AuthException("storage.json not found at: " + storagePath);
            }
            byte[] raw = Files.readAllBytes(storagePath);
            JsonNode root = MAPPER.readTree(raw);
            JsonNode authNode = root.get(AUTH_KEY);
            if (authNode == null) {
                throw new AuthException("No auth info found in storage.json, key=" + AUTH_KEY);
            }

            String authJson = authNode.isTextual() ? authNode.asText() : authNode.toString();
            // CN 版为 tc 加密串，自动检测并解密
            if (TraeTcDecryptor.isTcEncrypted(authJson)) {
                log.info("[auth] CN edition tc-encrypted auth data detected, decrypting");
                authJson = TraeTcDecryptor.decrypt(authJson);
            }
            JsonNode auth = MAPPER.readTree(authJson);

            cached = new AuthSnapshot(
                auth.path("token").asText(null),
                auth.path("refreshToken").asText(null),
                auth.path("expiredAt").asText(null),
                auth.path("refreshExpiredAt").asText(null),
                auth.path("userId").asText(null),
                auth.path("host").asText(null),
                auth.path("userRegion").asText(null),
                auth.path("account").asText(null),
                edition
            );
            log.info("[auth] Loaded auth from {}, expiredAt={}", storagePath, cached.expiredAt());
            return cached;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Failed to read auth: " + e.getMessage(), e);
        }
    }

    /**
      * 解析手动 JWT 令牌，从 payload 提取过期时间与用户 标识。
     *
     * @param token JWT 格式 令牌，必须以 eyj 开头
     * @return 认证快照
     * @throws AuthException 当 令牌 格式无效时
     */
    private AuthSnapshot parseManualToken(String token) throws AuthException {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new AuthException("Invalid JWT token format: " + token.substring(0, Math.min(20, token.length())) + "...");
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode payload = MAPPER.readTree(decoded);
            long exp = payload.path("exp").asLong(0);
            String expiredAt = exp > 0
                ? java.time.Instant.ofEpochSecond(exp).toString()
                : null;
            String userId = payload.path("data").path("id").asText(null);

            cached = new AuthSnapshot(token, null, expiredAt, null, userId,
                apiHost, null, null, "manual");
            return cached;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            cached = new AuthSnapshot(token, null, null, null, null, apiHost, null, null, "manual");
            return cached;
        }
    }

    /**
     * 判断认证快照是否已过期。
     *
     * @param auth 认证快照，可为 空
     * @return true 表示已过期或无法判断
     */
    public static boolean isExpired(AuthSnapshot auth) {
        if (auth == null || auth.expiredAt() == null) {
            return true;
        }
        try {
            java.time.Instant expiry = java.time.Instant.parse(auth.expiredAt());
            return expiry.isBefore(java.time.Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    /**
      * 清除认证缓存，下次 获取认证 时重新加载。
     * 线程安全。
     */
    public synchronized void invalidate() {
        cached = null;
    }

    /**
      * 补齐 基础64 填充位。
     *
     * @param s 基础64 字符串，不可为 空
     * @return 补齐后的字符串
     */
    private static String padBase64(String s) {
        int mod = s.length() % 4;
        if (mod == 0) {
            return s;
        }
        return s + "=".repeat(4 - mod);
    }

    /**
     * 认证异常，读取/解析认证信息失败时抛出。
     * @author CH
     * @since 4.0.0
     */
    public static class AuthException extends Exception {
        /**
         * 创建认证异常。
         *
         * @param message 异常消息
         */
        public AuthException(String message) { super(message); }

        /**
         * 创建带原因的认证异常。
         *
         * @param message 异常消息
         * @param cause 原因
         */
        public AuthException(String message, Throwable cause) { super(message, cause); }
    }
}