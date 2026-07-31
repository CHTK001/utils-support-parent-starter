package com.chua.remote.support.gateway.core.auth;

import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.remote.support.spi.GatewayTokenVerifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于配置文件的默认令牌验证实现
 *
 * <p>令牌数据持久化到 ~/.remote-gateway/tokens.json，格式：
 * <pre>{@code
 * {
 *   "tokens": [
 *     {
 *       "token": "abc123...",
 *       "userId": "admin",
 *       "displayName": "管理员",
 *       "accessibleAgentIds": null,
 *       "accessibleTargetIds": null
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>null/空 accessibleAgentIds = 可访问全部

 * @author CH
 */@Slf4j
@SpiDefault
public class FileBasedTokenVerifier implements GatewayTokenVerifier {

    /** 令牌存储，token 字符串 → TokenAuthImpl 映射 */
    private final Map<String, TokenAuthImpl> tokens = new ConcurrentHashMap<>();
    /** 令牌数据持久化文件路径 */
    private final Path dataPath;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 创建默认文件令牌验证器，持久化路径为 ~/.remote-gateway/tokens.json
     */
    public FileBasedTokenVerifier() {
        this(Path.of(System.getProperty("user.home"), ".remote-gateway", "tokens.json"));
    }

    /**
     * 创建指定持久化路径的文件令牌验证器，自动从磁盘加载已有令牌
     *
     * @param dataPath 令牌数据文件路径
     */
    public FileBasedTokenVerifier(Path dataPath) {
        this.dataPath = dataPath;
        loadFromDisk();
    }

    /**
     * authenticate
     * @param token 参数
     * @return authenticate结果
     */
    @Override
    public GatewayTokenVerifier.TokenAuth authenticate(String token) {
        if (token == null || token.isEmpty()) { return null; }
        TokenAuthImpl auth = tokens.get(token);
        if (auth == null) { return null; }
        // 检查过期
        if (auth.expiresAt != null && Instant.now().isAfter(auth.expiresAt)) {
            tokens.remove(token);
            log.info("[TokenVerifier] 令牌已过期，自动删除: userId={} token={}...", auth.getUserId(), token.substring(0, 8));
            saveToDisk();
            return null;
        }
        return auth;
    }

    /**
     * listTokens
     * @return listTokens结果
     */
    @Override
    public Map<String, GatewayTokenVerifier.TokenAuth> listTokens() {
        return new LinkedHashMap<>(tokens);
    }

    /**
     * createToken
     * @param auth 参数
     * @return createToken结果
     */
    @Override
    public String createToken(GatewayTokenVerifier.TokenAuth auth) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = null;
        if (auth.getExpiresAt() != null && !auth.getExpiresAt().isEmpty()) {
            try { expiresAt = Instant.parse(auth.getExpiresAt()); }
 catch (Exception e) { log.debug("解析过期时间失败: {}", auth.getExpiresAt(), e); }
        }
        TokenAuthImpl impl = new TokenAuthImpl(
                token,
                auth.getUserId(),
                auth.getDisplayName(),
                auth.getAccessibleAgentIds() != null ? new ArrayList<>(auth.getAccessibleAgentIds()) : null,
                auth.getAccessibleTargetIds() != null ? new ArrayList<>(auth.getAccessibleTargetIds()) : null,
                expiresAt
        );
        tokens.put(token, impl);
        saveToDisk();
        log.info("[TokenVerifier] 新建令牌: userId={} token={}...", impl.getUserId(), token.substring(0, 8));
        return token;
    }

    /**
     * 注册一个已有令牌到令牌存储（不生成新令牌）
     * <p>
     * 用于将管理端 Token 同步到 SPI 令牌体系，使其也可以通过 Token 验证器进行认证。
     *
     * @param token 令牌字符串
     * @param auth  令牌认证信息
     */
    public void registerToken(String token, TokenAuthImpl auth) {
        tokens.put(token, auth);
        saveToDisk();
        log.info("[TokenVerifier] 注册令牌: userId={} token={}...", auth.getUserId(), token.substring(0, Math.min(8, token.length())));
    }

    /**
     * editToken
     * @param token 参数
     * @param java.util.Map<String 参数
     * @param updates 参数
     * @return editToken结果
     */
    @Override
    public boolean editToken(String token, java.util.Map<String, Object> updates) {
        TokenAuthImpl auth = tokens.get(token);
        if (auth == null) { return false; }
        if (updates.containsKey("displayName")) {
            auth.setDisplayName((String) updates.get("displayName"));
        }
        if (updates.containsKey("accessibleAgentIds")) {
            @SuppressWarnings("unchecked")
            java.util.List<String> ids = (java.util.List<String>) updates.get("accessibleAgentIds");
            auth.setAccessibleAgentIds(ids);
        }
        if (updates.containsKey("expiresAt")) {
            String expiresAtStr = (String) updates.get("expiresAt");
            if (expiresAtStr == null || expiresAtStr.isEmpty()) {
                auth.setExpiresAt(null);
            }
 else {
                try { auth.setExpiresAt(Instant.parse(expiresAtStr)); }
 catch (Exception e) { log.debug("解析过期时间失败: {}", expiresAtStr, e); }
            }
        }
        saveToDisk();
        log.info("[TokenVerifier] 编辑令牌: userId={} token={}...", auth.getUserId(), token.substring(0, 8));
        return true;
    }

    /**
     * deleteToken
     * @param token 参数
     * @return deleteToken结果
     */
    @Override
    public boolean deleteToken(String token) {
        TokenAuthImpl removed = tokens.remove(token);
        if (removed != null) {
            saveToDisk();
            log.info("[TokenVerifier] 删除令牌: userId={} token={}...", removed.getUserId(), token.substring(0, 8));
            return true;
        }
        return false;
    }

    // ===== 持久化 =====

    /**
     * 从磁盘文件加载令牌数据
     * <p>
     * 如果文件不存在则静默忽略。
     * 加载后会自动清理已过期的令牌。
     */
    private void loadFromDisk() {
        if (!Files.exists(dataPath)) { return; }
        try {
            String json = Files.readString(dataPath);
            List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<>() {});
            for (Map<String, Object> entry : list) {
                String token = (String) entry.get("token");
                String userId = (String) entry.get("userId");
                String displayName = (String) entry.get("displayName");
                @SuppressWarnings("unchecked")
                List<String> agentIds = (List<String>) entry.get("accessibleAgentIds");
                @SuppressWarnings("unchecked")
                List<String> targetIds = (List<String>) entry.get("accessibleTargetIds");
                String expiresAtStr = (String) entry.get("expiresAt");
                Instant expiresAt = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;
                if (token != null) {
                    tokens.put(token, new TokenAuthImpl(token, userId, displayName, agentIds, targetIds, expiresAt));
                }
            }
            log.info("[TokenVerifier] 已加载 {} 个令牌: {}", tokens.size(), dataPath);
        }
 catch (IOException e) {
            log.warn("[TokenVerifier] 读取令牌文件失败: {}", e.getMessage());
        }
    }

    /**
     * 将当前令牌数据持久化到磁盘文件
     * <p>
     * 如果父目录不存在则自动创建。
     */
    private void saveToDisk() {
        try {
            Files.createDirectories(dataPath.getParent());
            List<Map<String, Object>> list = new ArrayList<>();
            for (TokenAuthImpl auth : tokens.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("token", auth.getToken());
                m.put("userId", auth.getUserId());
                m.put("displayName", auth.getDisplayName());
                m.put("accessibleAgentIds", auth.getAccessibleAgentIds());
                m.put("accessibleTargetIds", auth.getAccessibleTargetIds());
                if (auth.getExpiresAt() != null) { m.put("expiresAt", auth.getExpiresAt()); }
                list.add(m);
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            Files.writeString(dataPath, json);
        }
 catch (IOException e) {
            log.warn("[TokenVerifier] 保存令牌文件失败: {}", e.getMessage());
        }
    }

    /**
     * 令牌认证信息的可变实现
     * <p>
     * 相对于 {@link GatewayTokenVerifier.TokenAuth} 接口，增加了 Setter 方法和过期时间字段，
     * 支持在运行时编辑令牌属性。
     */
    public static class TokenAuthImpl implements GatewayTokenVerifier.TokenAuth {
        /** 令牌字符串 */
        /**
         * 令牌
         */
        private final String token;
        /** 用户 ID */
        /**
         * 用户 ID
         */
        private String userId;
        /** 显示名称 */
        private String displayName;
        /** 可访问的 Agent ID 列表（null 表示可访问全部） */
        private List<String> accessibleAgentIds;
        /** 可访问的 Target ID 列表（null 表示可访问全部） */
        private List<String> accessibleTargetIds;
        /** 令牌过期时间（null 表示永不过期） */
        private Instant expiresAt;

        public TokenAuthImpl(String token, String userId, String displayName,
                             List<String> accessibleAgentIds, List<String> accessibleTargetIds) {
            this(token, userId, displayName, accessibleAgentIds, accessibleTargetIds, null);
        }

        public TokenAuthImpl(String token, String userId, String displayName,
                             List<String> accessibleAgentIds, List<String> accessibleTargetIds,
                             Instant expiresAt) {
            this.token = token;
            this.userId = userId;
            this.displayName = displayName;
            this.accessibleAgentIds = accessibleAgentIds;
            this.accessibleTargetIds = accessibleTargetIds;
            this.expiresAt = expiresAt;
        }

        @Override public String getToken() { return token; }
        @Override public String getUserId() { return userId; }
        @Override public String getDisplayName() { return displayName; }
        /**
         * getExpiresAtInstant
         * @return getExpiresAtInstant结果
         */
        @Override public List<String> getAccessibleAgentIds() { return accessibleAgentIds; }
        @Override public List<String> getAccessibleTargetIds() { return accessibleTargetIds; }
        @Override public String getExpiresAt() { return expiresAt != null ? expiresAt.toString() : null; }
        public Instant getExpiresAtInstant() { return expiresAt; }

        /**
         * setUserId
         * @param userId 参数
         */
        public void setUserId(String userId) { this.userId = userId; }
        /**
         * setDisplayName
         * @param displayName 参数
         */
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        /**
         * setAccessibleAgentIds
         * @param ids 参数
         */
        public void setAccessibleAgentIds(List<String> ids) { this.accessibleAgentIds = ids; }
        /**
         * setAccessibleTargetIds
         * @param ids 参数
         */
        public void setAccessibleTargetIds(List<String> ids) { this.accessibleTargetIds = ids; }
        /**
         * setExpiresAt
         * @param expiresAt 参数
         */
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }
}
