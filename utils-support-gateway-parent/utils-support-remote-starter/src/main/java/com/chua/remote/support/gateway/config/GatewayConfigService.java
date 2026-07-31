package com.chua.remote.support.gateway.config;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.remote.support.spi.GatewayConfigStore;
import com.chua.remote.support.spi.GatewayTokenVerifier;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.session.SessionManager;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.UUID;

/**
 * 网关配置服务
 * 支持运行时热更新 + JSON 磁盘持久化

 * @author CH
 */@Slf4j
public class GatewayConfigService {

    /** 需要重启才能生效的配置字段集合 */
    private static final Set<String> RESTART_REQUIRED_FIELDS = Set.of("tcpControlPort", "tcpAgentPort", "socks5GatewayPort",
            "httpManagementPort", "httpApiPort", "wsApiGatewayPort", "dwsRemoteControlPort",
            "bossThreads", "workerThreads", "agentRegisterKey", "sniffBytes", "adminPath", "apiPath",
            "remoteApiHost", "remoteApiPort",
            "sshReverseTunnelEnabled", "sshRemoteHost", "sshRemotePort", "sshReverseLocalPort",
            "sshReverseRemotePort", "sshUsername", "sshKeyFile");

    /** 支持热加载（无需重启即可生效）的配置字段集合 */
    private static final Set<String> HOT_RELOADABLE_FIELDS = Set.of("maxSessions", "rateLimitTokensPerSecond", "rateLimitBurstCapacity",
            "agentHeartbeatInterval", "maxHeartbeatMisses", "sessionIdleTimeout",
            "apiTokenEnabled");

    /** 网关配置属性 */
    private final GatewayProperties properties;
    /** 会话管理器 */
    private final SessionManager sessionManager;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** SPI 配置存储 */
    private final GatewayConfigStore configStore;
    /** SPI 令牌验证器 */
    private final GatewayTokenVerifier tokenVerifier;

    /**
     * 构造网关配置服务
     * <p>
     * 通过 SPI 加载 GatewayConfigStore 和 GatewayTokenVerifier 的 datasource 实现。
     *
     * @param properties     网关配置属性
     * @param sessionManager 会话管理器
     * @param rateLimiter    网关限流器
     */
    public GatewayConfigService(GatewayProperties properties, SessionManager sessionManager, GatewayRateLimiter rateLimiter) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.configStore = ServiceProvider.of(GatewayConfigStore.class).getExtension("datasource");
        this.tokenVerifier = ServiceProvider.of(GatewayTokenVerifier.class).getExtension("datasource");
    }

    /** 启动时加载磁盘配置 */
    public void init() {
        Map<String, Object> diskConfig = configStore.load();
        if (diskConfig.isEmpty()) {
            log.info("[ConfigService] 无已保存配置，使用默认值");
            ensureManagementToken();
            ensureAdminToken();
            saveToDisk();
            return;
        }
        applyHotReloadableFields(diskConfig);
        // 从磁盘恢复 managementToken
        if (diskConfig.containsKey("managementToken")) {
            String token = String.valueOf(diskConfig.get("managementToken"));
            if (!token.isEmpty()) {
                properties.setManagementToken(token);
            }
        }
        ensureManagementToken();
        ensureAdminToken();
        log.info("[ConfigService] 已加载磁盘配置 ({} 项)", diskConfig.size());
    }

    /** 确保 managementToken 存在，不存在则生成并持久化 */
    private void ensureManagementToken() {
        if (properties.getManagementToken() == null || properties.getManagementToken().isEmpty()) {
            String token = UUID.randomUUID().toString().replace("-", "");
            properties.setManagementToken(token);
            log.info("[ConfigService] 已生成管理 Token: {}", token);
            saveToDisk();
        }
    }

    /** 确保 SPI 令牌列表中至少有一个 admin 令牌 */
    private void ensureAdminToken() {
        if (tokenVerifier.listTokens().isEmpty()) {
            String mgmtToken = properties.getManagementToken();
            if (mgmtToken != null && !mgmtToken.isEmpty()) {
                // 尝试用 managementToken 创建 admin 令牌（由实现决定是否接受指定 token）
                GatewayTokenVerifier.TokenAuth auth = new SimpleTokenAuth(mgmtToken, "admin", "管理员", null, null);
                tokenVerifier.createToken(auth);
                log.info("[ConfigService] 已将管理 Token 同步为 SPI admin 令牌: {}...", mgmtToken.length() > 8 ? mgmtToken.substring(0, 8) : mgmtToken);
            }
 else {
                String spiToken = tokenVerifier.createToken(
                        new SimpleTokenAuth(null, "admin", "管理员", null, null));
                log.info("[ConfigService] 已创建默认 SPI admin 令牌: {}...", spiToken.substring(0, 8));
            }
        }
    }

    /**
     * {@link GatewayTokenVerifier.TokenAuth} 的简易实现，用于 {@link #ensureAdminToken()}
     */
    private record SimpleTokenAuth(String token, String userId, String displayName,
                                   List<String> accessibleAgentIds,
                                   List<String> accessibleTargetIds) implements GatewayTokenVerifier.TokenAuth {
        /**
         * getToken
         * @return getToken结果
         */
        @Override
        public String getToken() { return token; }
        /**
         * getUserId
         * @return getUserId结果
         */
        @Override
        public String getUserId() { return userId; }
        /**
         * 获取显示名称
         * @return 获取显示名称结果
         */
        @Override
        public String getDisplayName() { return displayName; }
        /**
         * getAccessibleAgentIds
         * @return getAccessibleAgentIds结果
         */
        @Override
        public List<String> getAccessibleAgentIds() { return accessibleAgentIds; }
        /**
         * getAccessibleTargetIds
         * @return getAccessibleTargetIds结果
         */
        @Override
        public List<String> getAccessibleTargetIds() { return accessibleTargetIds; }
        /**
         * getExpiresAt
         * @return getExpiresAt结果
         */
        @Override
        public String getExpiresAt() { return null; }
    }

    /** 验证管理 Token（兼容旧的 managementToken + SPI 令牌） */
    public boolean verifyToken(String token) {
        // 1. 旧的 managementToken 兼容
        String expected = properties.getManagementToken();
        log.info("[ConfigService] verifyToken: incomingPresent={} apiTokenEnabled={}", token != null && !token.isEmpty(), properties.isApiTokenEnabled());
        if (expected != null && !expected.isEmpty() && expected.equals(token)) {
            log.info("[ConfigService] verifyToken: managementToken MATCH");
            return true;
        }
        // 2. SPI 令牌验证
        boolean spiResult = tokenVerifier.authenticate(token) != null;
        log.info("[ConfigService] verifyToken: SPI result={}", spiResult);
        return spiResult;
    }

    /** 通过 SPI 令牌验证获取用户身份 */
    @Nullable
    public GatewayTokenVerifier.TokenAuth authenticateToken(String token) {
        return tokenVerifier.authenticate(token);
    }

    /**
     * 判断是否为旧版管理令牌。
     * <p>旧令牌没有细粒度 {@link GatewayTokenVerifier.TokenAuth}，但拥有管理端全量权限。</p>
     */
    public boolean isManagementToken(String token) {
        String expected = properties.getManagementToken();
        return token != null && !token.isEmpty() && expected != null && !expected.isEmpty() && expected.equals(token);
    }

    /**
     * 检查令牌是否可以访问指定 Agent。
     * <p>开启令牌校验时先验证令牌有效性，再按 SPI 授权范围过滤；旧版管理令牌保留全量权限。</p>
     */
    public boolean canAccessAgent(String token, String agentId) {
        if (!properties.isApiTokenEnabled()) {
            return true;
        }
        if (!verifyToken(token)) {
            return false;
        }
        GatewayTokenVerifier.TokenAuth auth = authenticateToken(token);
        if (auth != null) {
            return auth.canAccessAgent(agentId);
        }
        return isManagementToken(token);
    }

    /** 获取 SPI 令牌验证器 */
    public GatewayTokenVerifier getTokenVerifier() {
        return tokenVerifier;
    }

    /** API 令牌验证是否开启 */
    public boolean isApiTokenEnabled() {
        return properties.isApiTokenEnabled();
    }

    /** 保存当前配置到磁盘 */
    public void saveToDisk() {
        try {
            configStore.save(toMap());
            log.info("[ConfigService] 配置已保存");
        }
 catch (Exception e) {
            log.warn("[ConfigService] 保存配置失败: {}", e.getMessage());
        }
    }

    /** 运行时更新配置 */
    public Map<String, Object> updateConfig(Map<String, Object> updates) {
        List<String> updated = new ArrayList<>();
        List<String> restartRequired = new ArrayList<>();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            if (HOT_RELOADABLE_FIELDS.contains(field)) {
                applyField(field, value);
                updated.add(field);
            } else if (RESTART_REQUIRED_FIELDS.contains(field)) {
                // 写入 properties 但标记需重启
                applyField(field, value);
                restartRequired.add(field);
            }
        }

        saveToDisk();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("updated", updated);
        result.put("restartRequired", restartRequired);
        result.put("config", toMap());
        return result;
    }

    /** 序列化所有配置字段 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        // TCP
        map.put("tcpControlPort", properties.getTcpControlPort());
        map.put("tcpAgentPort", properties.getTcpAgentPort());
        map.put("socks5GatewayPort", properties.getSocks5GatewayPort());
        // HTTP/WS
        map.put("httpManagementPort", properties.getHttpManagementPort());
        map.put("httpApiPort", properties.getHttpApiPort());
        map.put("wsApiGatewayPort", properties.getWsApiGatewayPort());
        map.put("dwsRemoteControlPort", properties.getDwsRemoteControlPort());
        // Thread
        map.put("bossThreads", properties.getBossThreads());
        map.put("workerThreads", properties.getWorkerThreads());
        // Agent
        map.put("agentHeartbeatInterval", properties.getAgentHeartbeatInterval());
        map.put("maxHeartbeatMisses", properties.getMaxHeartbeatMisses());
        map.put("agentRegisterKey", properties.getAgentRegisterKey());
        // Session
        map.put("sessionIdleTimeout", properties.getSessionIdleTimeout());
        map.put("maxSessions", properties.getMaxSessions());
        // Rate Limit
        map.put("rateLimitTokensPerSecond", properties.getRateLimitTokensPerSecond());
        map.put("rateLimitBurstCapacity", properties.getRateLimitBurstCapacity());
        // Other
        map.put("sniffBytes", properties.getSniffBytes());
        map.put("adminPath", properties.getAdminPath());
        map.put("apiPath", properties.getApiPath());
        // Management
        map.put("managementToken", properties.getManagementToken());
        // Security
        map.put("apiTokenEnabled", properties.isApiTokenEnabled());
        // Remote API
        map.put("remoteApiHost", properties.getRemoteApiHost());
        map.put("remoteApiPort", properties.getRemoteApiPort());
        // SSH Reverse Tunnel
        map.put("sshReverseTunnelEnabled", properties.isSshReverseTunnelEnabled());
        map.put("sshRemoteHost", properties.getSshRemoteHost());
        map.put("sshRemotePort", properties.getSshRemotePort());
        map.put("sshReverseLocalPort", properties.getSshReverseLocalPort());
        map.put("sshReverseRemotePort", properties.getSshReverseRemotePort());
        map.put("sshUsername", properties.getSshUsername());
        map.put("sshPassword", properties.getSshPassword());
        map.put("sshKeyFile", properties.getSshKeyFile());
        // Meta
        map.put("restartRequiredFields", new ArrayList<>(RESTART_REQUIRED_FIELDS));
        return map;
    }

    /**
     * 判断指定字段是否需要重启才能生效
     *
     * @param field 配置字段名
     * @return 如果需要重启返回 true
     */
    public static boolean isRestartRequired(String field) {
        return RESTART_REQUIRED_FIELDS.contains(field);
    }

    /**
     * 应用热加载配置字段到运行中组件
     * <p>
     * 仅扫描 HOT_RELOADABLE_FIELDS 中的字段，并从配置 Map 中读取对应值进行更新。
     * 用于启动时从磁盘配置恢复运行时参数。
     *
     * @param config 配置字段 Map
     */
    private void applyHotReloadableFields(Map<String, Object> config) {
        for (String field : HOT_RELOADABLE_FIELDS) {
            if (config.containsKey(field)) {
                applyField(field, config.get(field));
            }
        }
    }

    /**
     * 应用单个配置字段到对应的属性或组件
     * <p>
     * 热加载字段会实时更新到运行组件（如 sessionManager、rateLimiter）；
     * 需重启字段仅更新 properties，不会影响运行时。
     *
     * @param field 配置字段名
     * @param value 配置值
     */
    @SuppressWarnings("unchecked")
    private void applyField(String field, Object value) {
        try {
            switch (field) {
                case "maxSessions" -> {
                    int v = toInt(value);
                    properties.setMaxSessions(v);
                    sessionManager.setMaxSessions(v);
                }
                case "rateLimitTokensPerSecond" -> {
                    int tps = toInt(value);
                    properties.setRateLimitTokensPerSecond(tps);
                    rateLimiter.setRateLimit(tps, properties.getRateLimitBurstCapacity());
                }
                case "rateLimitBurstCapacity" -> {
                    int burst = toInt(value);
                    properties.setRateLimitBurstCapacity(burst);
                    rateLimiter.setRateLimit(properties.getRateLimitTokensPerSecond(), burst);
                }
                case "agentHeartbeatInterval" -> properties.setAgentHeartbeatInterval(toInt(value));
                case "maxHeartbeatMisses" -> properties.setMaxHeartbeatMisses(toInt(value));
                case "sessionIdleTimeout" -> properties.setSessionIdleTimeout(toInt(value));
                // 端口等需重启字段也写入 properties（但不热生效）
                case "tcpControlPort" -> properties.setTcpControlPort(toInt(value));
                case "tcpAgentPort" -> properties.setTcpAgentPort(toInt(value));
                case "socks5GatewayPort" -> properties.setSocks5GatewayPort(toInt(value));
                case "httpManagementPort" -> properties.setHttpManagementPort(toInt(value));
                case "httpApiPort" -> properties.setHttpApiPort(toInt(value));
                case "wsApiGatewayPort" -> properties.setWsApiGatewayPort(toInt(value));
                case "dwsRemoteControlPort" -> properties.setDwsRemoteControlPort(toInt(value));
                case "bossThreads" -> properties.setBossThreads(toInt(value));
                case "workerThreads" -> properties.setWorkerThreads(toInt(value));
                case "agentRegisterKey" -> properties.setAgentRegisterKey(String.valueOf(value));
                case "sniffBytes" -> properties.setSniffBytes(toInt(value));
                case "adminPath" -> properties.setAdminPath(String.valueOf(value));
                case "apiPath" -> properties.setApiPath(String.valueOf(value));
                case "apiTokenEnabled" -> properties.setApiTokenEnabled(toBool(value));
                case "sshReverseTunnelEnabled" -> properties.setSshReverseTunnelEnabled(toBool(value));
                case "sshRemoteHost" -> properties.setSshRemoteHost(String.valueOf(value));
                case "sshRemotePort" -> properties.setSshRemotePort(toInt(value));
                case "sshReverseLocalPort" -> properties.setSshReverseLocalPort(toInt(value));
                case "sshReverseRemotePort" -> properties.setSshReverseRemotePort(toInt(value));
                case "sshUsername" -> properties.setSshUsername(String.valueOf(value));
                case "sshPassword" -> properties.setSshPassword(String.valueOf(value));
                case "sshKeyFile" -> properties.setSshKeyFile(String.valueOf(value));
                case "remoteApiHost" -> properties.setRemoteApiHost(String.valueOf(value));
                case "remoteApiPort" -> properties.setRemoteApiPort(toInt(value));
            }
        }
 catch (Exception e) {
            log.warn("[ConfigService] 应用配置字段失败: {}={}", field, value);
        }
    }

    /**
     * 将 Object 安全转换为 int
     *
     * @param value 要转换的对象（Number 或字符串）
     * @return int 值
     */
    private static int toInt(Object value) {
        if (value instanceof Number n) { return n.intValue(); }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * 将 Object 安全转换为 boolean
     *
     * @param value 要转换的对象（Boolean 或字符串）
     * @return boolean 值
     */
    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) { return b; }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
