package com.chua.remote.support.gateway.core.auth;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.support.spi.GatewayTokenVerifier;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 DataSource（SQLite）的令牌验证实现
 *
 * <p>SPI 名称 "datasource"，表名 {@code gateway_tokens}。
 * 默认使用 SQLite 数据库，路径 {@code ~/.remote-gateway/gateway.db}。
 *
 * <p>通过 SPI {@code ServiceProvider.of(GatewayTokenVerifier.class).getExtension("datasource")} 加载。
 * 文件结构：
 * <pre>{@code
 * CREATE TABLE gateway_tokens (*   token        TEXT PRIMARY KEY,
 *   user_id      TEXT NOT NULL,
 *   display_name TEXT NOT NULL DEFAULT '',
 *   accessible_agent_ids  TEXT,  -- 逗号分隔
 *   accessible_target_ids TEXT,  -- 逗号分隔
 *   expires_at   TEXT,           -- ISO-8601
 *   created_at   INTEGER NOT NULL
 *)
 * }</pre>
 *
 * @author CH
 * @since 2026-05-25
 */
@Slf4j
@Spi("datasource")
public class DatasourceTokenVerifier implements GatewayTokenVerifier {

    /** 数据库表名 */
    private static final String TABLE_NAME = "gateway_tokens";

    /** 建表 SQL */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
            "  token        TEXT PRIMARY KEY," +
            "  user_id      TEXT NOT NULL," +
            "  display_name TEXT NOT NULL DEFAULT ''," +
            "  accessible_agent_ids  TEXT," +
            "  accessible_target_ids TEXT," +
            "  expires_at   TEXT," +
            "  created_at   INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
            ")";

    /** SQLite 数据库连接 URL */
    private final String dbUrl;
    /** 数据库连接 */
    private Connection connection;

    /**
     * 创建默认数据库令牌验证器，数据库路径为 ~/.remote-gateway/gateway.db
     */
    public DatasourceTokenVerifier() {
        this(System.getProperty("user.home") + "/.remote-gateway/gateway.db");
    }

    /**
     * 创建指定数据库路径的令牌验证器，自动初始化数据库和表结构
     *
     * @param dbPath SQLite 数据库文件路径
     */
    public DatasourceTokenVerifier(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        init();
    }

    // ===== 初始化 =====

    /**
     * 初始化数据库连接和表结构
     * <p>
     * 加载 SQLite JDBC 驱动，建立数据库连接，并执行建表语句。
     */
    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(dbUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
            log.info("[DatasourceTokenVerifier] SQLite 初始化完成，数据库路径: {}", dbUrl);
        }
 catch (Exception e) {
            log.error("[DatasourceTokenVerifier] SQLite 初始化失败: {}", e.getMessage());
            throw new RuntimeException("SQLite 初始化失败", e);
        }
    }

    // ===== GatewayTokenVerifier =====

    /**
     * authenticate
     * @param token 参数
     * @return authenticate结果
     */
    @Override
    public TokenAuth authenticate(String token) {
        if (token == null || token.isEmpty()) { return null; }
        TokenAuthImpl auth = findToken(token);
        if (auth == null) { return null; }
        // 检查过期
        if (auth.expiresAt != null && Instant.now().isAfter(auth.expiresAt)) {
            deleteToken(token);
            log.info("[DatasourceTokenVerifier] 令牌已过期，自动删除: userId={} token={}...",
                    auth.userId, token.substring(0, 8));
            return null;
        }
        return auth;
    }

    /**
     * listTokens
     * @return listTokens结果
     */
    @Override
    public Map<String, TokenAuth> listTokens() {
        Map<String, TokenAuth> result = new LinkedHashMap<>();
        String sql = "SELECT * FROM " + TABLE_NAME + " ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                TokenAuthImpl auth = mapRow(rs);
                result.put(auth.token, auth);
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceTokenVerifier] 查询令牌列表失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * createToken
     * @param auth 参数
     * @return createToken结果
     */
    @Override
    public String createToken(TokenAuth auth) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String expiresAt = null;
        if (auth.getExpiresAt() != null && !auth.getExpiresAt().isEmpty()) {
            expiresAt = auth.getExpiresAt();
        }

        String sql = "INSERT INTO " + TABLE_NAME +
                " (token, user_id, display_name, accessible_agent_ids, accessible_target_ids, expires_at) " +
                "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, auth.getUserId());
            ps.setString(3, auth.getDisplayName());
            ps.setString(4, joinList(auth.getAccessibleAgentIds()));
            ps.setString(5, joinList(auth.getAccessibleTargetIds()));
            ps.setString(6, expiresAt);
            ps.executeUpdate();
            log.info("[DatasourceTokenVerifier] 新建令牌: userId={} token={}...",
                    auth.getUserId(), token.substring(0, 8));
        }
 catch (SQLException e) {
            log.warn("[DatasourceTokenVerifier] 创建令牌失败: {}", e.getMessage());
        }
        return token;
    }

    /**
     * editToken
     * @param token 参数
     * @param Map<String 参数
     * @param updates 参数
     * @return editToken结果
     */
    @Override
    public boolean editToken(String token, Map<String, Object> updates) {
        if (findToken(token) == null) { return false; }

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (updates.containsKey("displayName")) {
            setClauses.add("display_name = ?");
            params.add(updates.get("displayName"));
        }
        if (updates.containsKey("accessibleAgentIds")) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) updates.get("accessibleAgentIds");
            setClauses.add("accessible_agent_ids = ?");
            params.add(joinList(ids));
        }
        if (updates.containsKey("expiresAt")) {
            Object val = updates.get("expiresAt");
            setClauses.add("expires_at = ?");
            params.add(val != null && !val.toString().isEmpty() ? val : null);
        }

        if (setClauses.isEmpty()) { return false; }

        params.add(token);
        String sql = "UPDATE " + TABLE_NAME + " SET " +
                String.join(", ", setClauses) + " WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p != null) {
                    ps.setString(i + 1, p.toString());
                }
 else {
                    ps.setNull(i + 1, Types.VARCHAR);
                }
            }
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[DatasourceTokenVerifier] 编辑令牌: token={}... rows={}",
                        token.substring(0, 8), rows);
            }
            return rows > 0;
        }
 catch (SQLException e) {
            log.warn("[DatasourceTokenVerifier] 编辑令牌失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * deleteToken
     * @param token 参数
     * @return deleteToken结果
     */
    @Override
    public boolean deleteToken(String token) {
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[DatasourceTokenVerifier] 删除令牌: token={}...", token.substring(0, 8));
            }
            return rows > 0;
        }
 catch (SQLException e) {
            log.warn("[DatasourceTokenVerifier] 删除令牌失败: {}", e.getMessage());
            return false;
        }
    }

    // ===== 内部方法 =====

    /**
     * 根据 token 字符串从数据库查询令牌信息
     *
     * @param token 令牌字符串
     * @return 令牌认证信息，未找到返回 null
     */
    private TokenAuthImpl findToken(String token) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return mapRow(rs); }
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceTokenVerifier] 查询令牌失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将数据库结果集的一行映射为 TokenAuthImpl 对象
     *
     * @param rs SQLite 查询结果集（当前位置需有数据）
     * @return 令牌认证信息对象
     * @throws SQLException 数据库访问异常
     */
    private TokenAuthImpl mapRow(ResultSet rs) throws SQLException {
        String token = rs.getString("token");
        String userId = rs.getString("user_id");
        String displayName = rs.getString("display_name");
        String agentIdsStr = rs.getString("accessible_agent_ids");
        String targetIdsStr = rs.getString("accessible_target_ids");
        String expiresAtStr = rs.getString("expires_at");

        List<String> agentIds = splitList(agentIdsStr);
        List<String> targetIds = splitList(targetIdsStr);
        Instant expiresAt = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;

        return new TokenAuthImpl(token, userId, displayName, agentIds, targetIds, expiresAt);
    }

    /**
     * 将字符串列表连接为逗号分隔的单个字符串（转义其中的逗号）
     *
     * @param list 字符串列表
     * @return 逗号分隔的字符串，列表为空或 null 时返回 null
     */
    private static String joinList(List<String> list) {
        if (list == null || list.isEmpty()) { return null; }
        return list.stream().map(s -> s.replace(",", "\\,")).collect(Collectors.joining(","));
    }

    /**
     * 将逗号分隔的字符串解析为字符串列表（处理转义的逗号）
     *
     * @param str 逗号分隔的字符串
     * @return 字符串列表，输入为空或 null 时返回 null
     */
    private static List<String> splitList(String str) {
        if (str == null || str.isEmpty()) { return null; }
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ',') {
                result.add(sb.toString());
                sb.setLength(0);
            }
 else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result;
    }

    // ===== TokenAuthImpl =====

    /**
     * 令牌认证信息的包级私有实现
     * <p>
     * 用于数据库查询结果的映射，无需对外暴露 Setter 方法。
     */
    static class TokenAuthImpl implements TokenAuth {
        /** 令牌字符串 */
        final String token;
        /** 用户 ID */
        String userId;
        /** 显示名称 */
        String displayName;
        /** 可访问的 Agent ID 列表（null 表示全部） */
        List<String> accessibleAgentIds;
        /** 可访问的 Target ID 列表（null 表示全部） */
        List<String> accessibleTargetIds;
        /** 令牌过期时间（null 表示永不过期） */
        Instant expiresAt;

        TokenAuthImpl(String token, String userId, String displayName,
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
        @Override public List<String> getAccessibleAgentIds() { return accessibleAgentIds; }
        @Override public List<String> getAccessibleTargetIds() { return accessibleTargetIds; }
        @Override public String getExpiresAt() { return expiresAt != null ? expiresAt.toString() : null; }
    }
}
