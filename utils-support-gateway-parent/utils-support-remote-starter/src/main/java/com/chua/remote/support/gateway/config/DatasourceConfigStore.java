package com.chua.remote.support.gateway.config;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.support.spi.GatewayConfigStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 DataSource（SQLite）的配置持久化实现
 *
 * <p>SPI 名称 "datasource"，表名 {@code gateway_config}。
 * 默认使用 SQLite 数据库，路径 {@code ~/.remote-gateway/gateway.db}。
 *
 * <p>配置以 key-value 形式存储，value 为 JSON 字符串。
 *
 * @since 4.0.0.41

 * @author CH
 */@Slf4j
@Spi("datasource")
public class DatasourceConfigStore implements GatewayConfigStore {

    /** 配置存储表名 */
    private static final String TABLE_NAME = "gateway_config";

    /** 建表 SQL */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
            "  config_key   TEXT PRIMARY KEY," +
            "  config_value TEXT NOT NULL," +
            "  updated_at   INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
            ")";

    /** SQLite 数据库 JDBC URL */
    private final String dbUrl;
    /** 数据库连接 */
    private Connection connection;
    /** Jackson ObjectMapper，用于配置值的 JSON 序列化/反序列化 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 默认构造器，数据库路径为 {@code ~/.remote-gateway/gateway.db}
     */
    public DatasourceConfigStore() {
        this(System.getProperty("user.home") + "/.remote-gateway/gateway.db");
    }

    /**
     * 指定数据库路径的构造器
     *
     * @param dbPath SQLite 数据库文件路径
     */
    public DatasourceConfigStore(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        init();
    }

    /**
     * 初始化数据库连接并创建配置表
     *
     * <p>加载 SQLite JDBC 驱动，建立连接，执行建表 DDL。
     */
    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(dbUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
            log.info("[DatasourceConfigStore] SQLite 初始化完成，数据库路径: {}", dbUrl);
        }
 catch (Exception e) {
            log.error("[DatasourceConfigStore] SQLite 初始化失败: {}", e.getMessage());
            throw new RuntimeException("SQLite 初始化失败", e);
        }
    }

    /**
     * 从数据库加载全部配置
     *
     * <p>遍历 {@code gateway_config} 表，将 value 列作为 JSON 解析后返回。
     * 若 JSON 解析失败则直接作为字符串存储。
     *
     * @return 配置键值对映射
     */
    @Override
    public Map<String, Object> load() {
        Map<String, Object> result = new LinkedHashMap<>();
        String sql = "SELECT config_key, config_value FROM " + TABLE_NAME;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String key = rs.getString("config_key");
                String value = rs.getString("config_value");
                try {
                    Object parsed = mapper.readValue(value, new TypeReference<>() {});
                    result.put(key, parsed);
                }
 catch (Exception e) {
                    // 非 JSON 值直接存为字符串
                    result.put(key, value);
                }
            }
        }
 catch (SQLException e) {
            log.warn("[DatasourceConfigStore] 加载配置失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 将配置批量持久化到数据库
     *
     * <p>使用 UPSERT 语义，已存在的 key 更新 value 和 updated_at，
     * 不存在的 key 插入新记录。支持事务回滚。
     *
     * @param config 配置键值对映射
     */
    @Override
    public void save(Map<String, Object> config) {
        String upsertSql = "INSERT INTO " + TABLE_NAME + " (config_key, config_value, updated_at) " +
                "VALUES (?, ?, strftime('%s','now')) " +
                "ON CONFLICT(config_key) DO UPDATE SET config_value = excluded.config_value, " +
                "updated_at = excluded.updated_at";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(upsertSql)) {
                for (Map.Entry<String, Object> entry : config.entrySet()) {
                    String valueJson;
                    if (entry.getValue() instanceof String s) {
                        valueJson = mapper.writeValueAsString(s);
                    }
 else {
                        valueJson = mapper.writeValueAsString(entry.getValue());
                    }
                    ps.setString(1, entry.getKey());
                    ps.setString(2, valueJson);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            log.debug("[DatasourceConfigStore] 配置已保存，共 {} 项", config.size());
        }
 catch (SQLException e) {
            try { connection.rollback(); }
 catch (SQLException ignored) {}
            log.warn("[DatasourceConfigStore] 保存配置失败: {}", e.getMessage());
        }
 catch (Exception e) {
            try { connection.rollback(); }
 catch (SQLException ignored) {}
            log.warn("[DatasourceConfigStore] 序列化配置失败: {}", e.getMessage());
        }
 finally {
            try { connection.setAutoCommit(true); }
 catch (SQLException ignored) {}
        }
    }
}
