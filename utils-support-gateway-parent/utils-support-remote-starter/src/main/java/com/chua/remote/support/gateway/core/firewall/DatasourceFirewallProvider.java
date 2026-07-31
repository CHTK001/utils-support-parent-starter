package com.chua.remote.support.gateway.core.firewall;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.support.spi.GatewayFirewallProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * 基于 DataSource（SQLite）的防火墙 SPI 实现
 *
 * <p>SPI 名称 "datasource"，使用三张表：
 * <ul>
 *   <li>{@code gateway_ip_list} — 黑名单/白名单</li>
 *   <li>{@code gateway_firewall_config} — 配置项（过滤模式、限流开关等）</li>
 *   <li>{@code gateway_access_logs} — 访问日志（最近 10000 条）</li>
 * </ul>
 *
 * <p>默认 SQLite 数据库路径 {@code ~/.remote-gateway/gateway.db}。
 *
 * @author CH
 * @since 2026-05-25
 */
@Slf4j
@Spi("datasource")
public class DatasourceFirewallProvider implements GatewayFirewallProvider {

    /** 访问日志最大保留条数 */
    private static final int MAX_LOG_ENTRIES = 10000;

    // ===== 建表 SQL =====

    /** IP 黑白名单表 DDL */
    private static final String CREATE_IP_LIST =
            "CREATE TABLE IF NOT EXISTS gateway_ip_list (" +
            "  ip         TEXT PRIMARY KEY," +
            // ULL," +   // 'blocked' or 'allowed'
            "  type       TEXT NOT NULL," +
            "  reason     TEXT DEFAULT ''," +
            "  blocked_at TEXT," +
            "  created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
            ")";

    /** 防火墙配置表 DDL */
    private static final String CREATE_CONFIG =
            "CREATE TABLE IF NOT EXISTS gateway_firewall_config (" +
            "  key   TEXT PRIMARY KEY," +
            "  value TEXT NOT NULL" +
            ")";

    /** 访问日志表 DDL */
    private static final String CREATE_ACCESS_LOGS =
            "CREATE TABLE IF NOT EXISTS gateway_access_logs (" +
            "  id              INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  ip              TEXT NOT NULL," +
            "  path            TEXT NOT NULL," +
            "  method          TEXT NOT NULL," +
            "  status_code     INTEGER NOT NULL," +
            "  response_time_ms INTEGER NOT NULL DEFAULT 0," +
            "  user_agent      TEXT DEFAULT ''," +
            "  log_time        TEXT NOT NULL" +
            ")";

    /** SQLite 数据库 JDBC URL */
    private final String dbUrl;
    /** 数据库连接 */
    private Connection connection;

    /**
     * 默认构造器，数据库路径为 {@code ~/.remote-gateway/gateway.db}
     */
    public DatasourceFirewallProvider() {
        this(System.getProperty("user.home") + "/.remote-gateway/gateway.db");
    }

    /**
     * 指定数据库路径的构造器
     *
     * @param dbPath SQLite 数据库文件路径
     */
    public DatasourceFirewallProvider(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        init();
    }

    /**
     * 初始化数据库连接，创建三张表并写入默认配置
     */
    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(dbUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_IP_LIST);
                stmt.execute(CREATE_CONFIG);
                stmt.execute(CREATE_ACCESS_LOGS);
            }
            // 初始化默认配置
            initDefaultConfig("ip_filter_mode", "DISABLED");
            initDefaultConfig("rate_limit_enabled", "false");
            log.info("[DatasourceFirewallProvider] SQLite 初始化完成，数据库路径: {}", dbUrl);
        }
 catch (Exception e) {
            log.error("[DatasourceFirewallProvider] SQLite 初始化失败: {}", e.getMessage());
            throw new RuntimeException("SQLite 初始化失败", e);
        }
    }

    /**
     * 初始化默认配置项（不存在时才插入）
     *
     * @param key          配置键
     * @param defaultValue 默认值
     */
    private void initDefaultConfig(String key, String defaultValue) {
        String sql = "INSERT OR IGNORE INTO gateway_firewall_config (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, defaultValue);
            ps.executeUpdate();
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 初始化配置失败: key={} err={}", key, e.getMessage());
        }
    }

    /**
     * 获取配置项
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值，不存在时返回 defaultValue
     */
    private String getConfig(String key, String defaultValue) {
        String sql = "SELECT value FROM gateway_firewall_config WHERE key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getString("value"); }
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 读取配置失败: key={}", key);
        }
        return defaultValue;
    }

    /**
     * 设置配置项
     *
     * @param key   配置键
     * @param value 配置值
     */
    private void setConfig(String key, String value) {
        String sql = "INSERT OR REPLACE INTO gateway_firewall_config (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 保存配置失败: key={}", key);
        }
    }

    // ===== 请求级操作 =====

    /**
     * 检查请求是否允许通过
     *
     * <p>根据 IP 过滤模式（黑名单/白名单/禁用）和限流开关进行判断。
     *
     * @param clientIp 客户端 IP
     * @param path     请求路径
     * @return 拒绝原因，允许通过时返回 null
     */
    @Override
    public String checkRequest(String clientIp, String path) {
        // IP 过滤
        String mode = getConfig("ip_filter_mode", "DISABLED");
        if ("BLACKLIST".equals(mode)) {
            if (isInList(clientIp, "blocked")) { return "IP 已被封禁"; }
        } else if ("WHITELIST".equals(mode)) {
            if (!isInList(clientIp, "allowed")) { return "IP 不在白名单中"; }
        }
        // 限流
        if ("true".equals(getConfig("rate_limit_enabled", "false"))) {
            // 简易限流：基于时间窗口统计，每分钟最多 60 次
            long count = countRecentLogs(clientIp, 60);
            if (count > 60) { return "请求过于频繁"; }
        }
        return null;
    }

    /**
     * 记录访问日志
     *
     * @param clientIp      客户端 IP
     * @param path          请求路径
     * @param method        HTTP 方法
     * @param statusCode    响应状态码
     * @param responseTimeMs 响应耗时（毫秒）
     */
    @Override
    public void recordAccess(String clientIp, String path, String method,
                             int statusCode, long responseTimeMs) {
        String sql = "INSERT INTO gateway_access_logs (ip, path, method, status_code, response_time_ms, log_time) " +
                "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientIp);
            ps.setString(2, path);
            ps.setString(3, method);
            ps.setInt(4, statusCode);
            ps.setLong(5, responseTimeMs);
            ps.setString(6, java.time.Instant.now().toString());
            ps.executeUpdate();
        }
 catch (SQLException e) {
            if (log.isTraceEnabled()) {
                log.trace("[DatasourceFirewallProvider] 记录访问日志失败: {}", e.getMessage());
            }
        }
        // 日志数量限制：清除旧数据
        trimLogs();
    }

    // ===== IP 过滤 =====

    /**
     * getIpFilterMode
     * @return getIpFilterMode结果
     */
    @Override
    public String getIpFilterMode() {
        return getConfig("ip_filter_mode", "DISABLED");
    }

    /**
     * setIpFilterMode
     * @param mode 参数
     */
    @Override
    public void setIpFilterMode(String mode) {
        setConfig("ip_filter_mode", mode);
        log.info("[DatasourceFirewallProvider] IP 过滤模式切换为: {}", mode);
    }

    /**
     * blockIp
     * @param ip 参数
     * @param reason 参数
     */
    @Override
    public void blockIp(String ip, String reason) {
        String sql = "INSERT OR REPLACE INTO gateway_ip_list (ip, type, reason, blocked_at) VALUES (?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, "blocked");
            ps.setString(3, reason != null ? reason : "手动封禁");
            ps.setString(4, java.time.Instant.now().toString());
            ps.executeUpdate();
            log.info("[DatasourceFirewallProvider] 封禁 IP: {} 原因: {}", ip, reason);
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 封禁 IP 失败: {}", e.getMessage());
        }
    }

    /**
     * unblockIp
     * @param ip 参数
     * @return unblockIp结果
     */
    @Override
    public boolean unblockIp(String ip) {
        String sql = "DELETE FROM gateway_ip_list WHERE ip = ? AND type = 'blocked'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[DatasourceFirewallProvider] 解封 IP: {}", ip);
                return true;
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 解封 IP 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * allowIp
     * @param ip 参数
     */
    @Override
    public void allowIp(String ip) {
        String sql = "INSERT OR REPLACE INTO gateway_ip_list (ip, type, reason, blocked_at) VALUES (?,?,?,NULL)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, "allowed");
            ps.setString(3, "");
            ps.executeUpdate();
            log.info("[DatasourceFirewallProvider] 白名单添加 IP: {}", ip);
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 白名单添加 IP 失败: {}", e.getMessage());
        }
    }

    /**
     * removeAllowedIp
     * @param ip 参数
     * @return removeAllowedIp结果
     */
    @Override
    public boolean removeAllowedIp(String ip) {
        String sql = "DELETE FROM gateway_ip_list WHERE ip = ? AND type = 'allowed'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[DatasourceFirewallProvider] 白名单移除 IP: {}", ip);
                return true;
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 白名单移除失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * getBlockedList
     * @return getBlockedList结果
     */
    @Override
    public List<Map<String, Object>> getBlockedList() {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT ip, reason, blocked_at FROM gateway_ip_list WHERE type = 'blocked' ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ip", rs.getString("ip"));
                m.put("reason", rs.getString("reason"));
                m.put("blockedAt", rs.getString("blocked_at"));
                result.add(m);
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询黑名单失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * getAllowedList
     * @return getAllowedList结果
     */
    @Override
    public List<String> getAllowedList() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT ip FROM gateway_ip_list WHERE type = 'allowed' ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString("ip"));
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询白名单失败: {}", e.getMessage());
        }
        return result;
    }

    // ===== 限流 =====

    /**
     * isRateLimitEnabled
     * @return isRateLimitEnabled结果
     */
    @Override
    public boolean isRateLimitEnabled() {
        return "true".equals(getConfig("rate_limit_enabled", "false"));
    }

    /**
     * setRateLimitEnabled
     * @param enabled 参数
     */
    @Override
    public void setRateLimitEnabled(boolean enabled) {
        setConfig("rate_limit_enabled", String.valueOf(enabled));
        log.info("[DatasourceFirewallProvider] 限流{}", enabled ? "已启用" : "已禁用");
    }

    // ===== 访问日志 =====

    /**
     * getAccessStats
     * @return getAccessStats结果
     */
    @Override
    public Map<String, Object> getAccessStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Statement stmt = connection.createStatement()) {
            // 总请求数
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM gateway_access_logs")) {
                stats.put("totalRequests", rs.next() ? rs.getLong("cnt") : 0L);
            }
            // Top 10 IPs
            stats.put("topIps", queryTop("ip", 10));
            // Top 10 路径
            stats.put("topPaths", queryTop("path", 10));
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询访问统计失败: {}", e.getMessage());
            stats.put("totalRequests", 0L);
            stats.put("topIps", List.of());
            stats.put("topPaths", List.of());
        }
        return stats;
    }

    /**
     * getAccessLogs
     * @param ipFilter 参数
     * @param count 参数
     * @return getAccessLogs结果
     */
    @Override
    public List<Map<String, Object>> getAccessLogs(String ipFilter, int count) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql;
        boolean hasFilter = ipFilter != null && !ipFilter.isEmpty();
        if (hasFilter) {
            sql = "SELECT * FROM gateway_access_logs WHERE ip = ? ORDER BY id DESC LIMIT ?";
        }
 else {
            sql = "SELECT * FROM gateway_access_logs ORDER BY id DESC LIMIT ?";
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (hasFilter) {
                ps.setString(1, ipFilter);
                ps.setInt(2, count);
            }
 else {
                ps.setInt(1, count);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", rs.getString("ip"));
                    m.put("path", rs.getString("path"));
                    m.put("method", rs.getString("method"));
                    m.put("statusCode", rs.getInt("status_code"));
                    m.put("responseTimeMs", rs.getLong("response_time_ms"));
                    m.put("timestamp", rs.getString("log_time"));
                    result.add(m);
                }
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询访问日志失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * clearAccessLogs
     */
    @Override
    public void clearAccessLogs() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM gateway_access_logs");
            log.info("[DatasourceFirewallProvider] 访问日志已清除");
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 清除日志失败: {}", e.getMessage());
        }
    }

    // ===== 实时指标 =====

    /**
     * getRealtimeMetrics
     * @return getRealtimeMetrics结果
     */
    @Override
    public Map<String, Object> getRealtimeMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        try (Statement stmt = connection.createStatement()) {
            // 最近 60 秒 QPS 时序（按秒分桶）
            List<Long> timeSeries = new ArrayList<>(Collections.nCopies(60, 0L));
            long nowSec = System.currentTimeMillis() / 1000;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT CAST(strftime('%s', log_time) AS INTEGER) AS sec, COUNT(*) AS cnt " +
                    "FROM gateway_access_logs WHERE log_time >= datetime('now', '-60 seconds') " +
                    "GROUP BY sec ORDER BY sec")) {
                while (rs.next()) {
                    long sec = rs.getLong("sec");
                    long cnt = rs.getLong("cnt");
                    int idx = (int) (sec - (nowSec - 59));
                    if (idx >= 0 && idx < 60) { timeSeries.set(idx, cnt); }
                }
            }
            long currentQps = timeSeries.get(59);
            long sum = 0;
            long peak = 0;
            int nonZero = 0;
            for (long v : timeSeries) {
                if (v > 0) { sum += v; nonZero++; }
                if (v > peak) { peak = v; }
            }
            metrics.put("currentQps", currentQps);
            metrics.put("avgQps", nonZero > 0 ? sum / nonZero : 0);
            metrics.put("peakQps", peak);
            metrics.put("qpsTimeSeries", timeSeries);

            // 总请求数
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM gateway_access_logs")) {
                metrics.put("totalRequests", rs.next() ? rs.getLong("cnt") : 0L);
            }
            // 平均响应时间
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT AVG(response_time_ms) AS avg_rt FROM gateway_access_logs WHERE log_time >= datetime('now', '-60 seconds')")) {
                metrics.put("avgResponseTimeMs", rs.next() ? (long) rs.getDouble("avg_rt") : 0L);
            }
            // 状态码分布（最近 60 秒）
            Map<String, Object> statusDist = new LinkedHashMap<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT status_code, COUNT(*) AS cnt FROM gateway_access_logs " +
                    "WHERE log_time >= datetime('now', '-60 seconds') GROUP BY status_code")) {
                while (rs.next()) { statusDist.put(String.valueOf(rs.getInt("status_code")), rs.getLong("cnt")); }
            }
            metrics.put("statusCodeDistribution", statusDist);
            // Top 10 IP（最近 60 秒）
            List<Map<String, Object>> topIps = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT ip, COUNT(*) AS cnt FROM gateway_access_logs " +
                    "WHERE log_time >= datetime('now', '-60 seconds') GROUP BY ip ORDER BY cnt DESC LIMIT 10")) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", rs.getString("ip"));
                    m.put("count", rs.getLong("cnt"));
                    topIps.add(m);
                }
            }
            metrics.put("topIpsRecent", topIps);
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询实时指标失败: {}", e.getMessage());
            metrics.put("currentQps", 0);
            metrics.put("avgQps", 0);
            metrics.put("peakQps", 0);
            metrics.put("totalRequests", 0L);
            metrics.put("avgResponseTimeMs", 0L);
            metrics.put("statusCodeDistribution", Map.of());
            metrics.put("qpsTimeSeries", List.of());
            metrics.put("topIpsRecent", List.of());
        }
        return metrics;
    }

    /**
     * 获取状态
     * @return 获取状态结果
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", getIpFilterMode());
        status.put("rateLimitEnabled", isRateLimitEnabled());
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt FROM gateway_ip_list WHERE type = 'blocked'")) {
                status.put("blockedCount", rs.next() ? rs.getInt("cnt") : 0);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt FROM gateway_ip_list WHERE type = 'allowed'")) {
                status.put("allowedCount", rs.next() ? rs.getInt("cnt") : 0);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt FROM gateway_access_logs")) {
                status.put("logCount", rs.next() ? rs.getLong("cnt") : 0L);
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询状态失败: {}", e.getMessage());
        }
        return status;
    }

    // ===== 内部方法 =====

    /**
     * 检查 IP 是否存在于指定类型的列表中
     *
     * @param ip   客户端 IP
     * @param type 列表类型：blocked 或 allowed
     * @return 存在返回 true
     */
    private boolean isInList(String ip, String type) {
        String sql = "SELECT 1 FROM gateway_ip_list WHERE ip = ? AND type = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] IP 列表查询失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 统计指定 IP 在最近 N 秒内的请求次数
     *
     * @param ip      客户端 IP
     * @param seconds 时间窗口（秒）
     * @return 请求次数
     */
    private long countRecentLogs(String ip, int seconds) {
        String sql = "SELECT COUNT(*) AS cnt FROM gateway_access_logs WHERE ip = ? " +
                "AND log_time >= datetime('now', ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, "-" + seconds + " seconds");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getLong("cnt"); }
            }
        }
 catch (SQLException e) {
            // SQLite datetime 函数兼容性处理
            if (log.isTraceEnabled()) {
                log.trace("[DatasourceFirewallProvider] 限流统计失败: {}", e.getMessage());
            }
        }
        return 0;
    }

    /**
     * 查询指定列的 Top N 排名
     *
     * @param column 排名字段（ip / path）
     * @param limit  返回条数
     * @return 排名列表
     */
    private List<Map<String, Object>> queryTop(String column, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT " + column + ", COUNT(*) AS cnt FROM gateway_access_logs " +
                "GROUP BY " + column + " ORDER BY cnt DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(column, rs.getString(column));
                    m.put("count", rs.getLong("cnt"));
                    result.add(m);
                }
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceFirewallProvider] 查询 Top {} 失败: {}", column, e.getMessage());
        }
        return result;
    }

    /**
     * 裁剪过旧的日志记录，仅保留最近 {@link #MAX_LOG_ENTRIES} 条
     */
    private void trimLogs() {
        // 保留最近 MAX_LOG_ENTRIES 条，删除多余旧数据
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM gateway_access_logs WHERE id NOT IN " +
                    "(SELECT id FROM gateway_access_logs ORDER BY id DESC LIMIT " + MAX_LOG_ENTRIES + ")");
        }
 catch (SQLException e) {
            if (log.isTraceEnabled()) {
                log.trace("[DatasourceFirewallProvider] 日志裁剪失败: {}", e.getMessage());
            }
        }
    }
}
