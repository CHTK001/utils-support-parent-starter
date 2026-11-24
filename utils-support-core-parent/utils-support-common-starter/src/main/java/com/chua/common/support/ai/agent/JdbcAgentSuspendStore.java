package com.chua.common.support.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;

/**
 * 基于关系数据库（JDBC）的 {@link AgentSuspendStore} 实现：跨进程 / 多实例共享挂起状态。
 *
 * <p><b>适用场景</b>：Agent 服务多实例部署时，「人工确认 / 结构化提问」的暂停-恢复
 * 上下文必须落在共享存储，任一实例都能完成恢复。本实现只依赖
 * {@link DataSource}（JDK 自带契约），不绑定具体数据库；表结构在构造时
 * 自动创建（{@code CREATE TABLE IF NOT EXISTS}，幂等）。</p>
 *
 * <p><b>载荷编解码</b>：载荷经 {@link AgentSuspendPayloadCodec} 转字节落库。
 * 默认 {@link AgentSuspendPayloadCodec#javaSerialization()} 仅适用于
 * {@link java.io.Serializable} 载荷；langchain4j / agentscope 等引擎的 SDK 类型
 * 载荷需注入对应引擎的专用编解码器，否则写入时序列化失败（该条目将无法恢复）。</p>
 *
 * <p><b>TTL 与清扫</b>：与 {@link InMemoryAgentSuspendStore} 同口径——读取时惰性
 * 判断过期并顺手删除；可开启守护线程周期性清扫无人读取的过期条目。
 * {@code expire_at} 冗余列用于 SQL 级清理。</p>
 *
 * <p><b>失败语义</b>：存储故障（连接失败、SQL 错误、编解码失败）不抛出、不中断
 * Agent 主流程，仅记录 WARN 日志：写入失败该条目将无法恢复，读取失败按
 * 「条目不存在」处理（调用方会提示恢复链接失效，用户可重新发起）。</p>
 *
 * <p><b>线程安全</b>：无共享可变状态（连接每次从 DataSource 获取），可并发读写。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class JdbcAgentSuspendStore implements AgentSuspendStore {

    /**
     * 默认表名
     */
    public static final String DEFAULT_TABLE_NAME = "agent_suspend_state";

    /**
     * 默认条目存活时长：30 分钟
     */
    public static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * 默认清扫周期：60 秒
     */
    public static final long DEFAULT_SWEEP_INTERVAL_MILLIS = 60L * 1000L;

    /**
     * attributes JSON 序列化器（ObjectMapper 线程安全，静态共享）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * attributes 反序列化类型
     */
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    /**
     * 数据源
     */
    private final DataSource dataSource;

    /**
     * 载荷编解码器
     */
    private final AgentSuspendPayloadCodec codec;

    /**
     * 默认 TTL（条目未显式指定时使用），非正数视为不过期
     */
    private final long defaultTtlMillis;

    /**
     * 表名（已校验合法性）
     */
    private final String tableName;

    /**
     * 清扫线程（可选）
     */
    private volatile Thread sweeper;

    /**
     * 使用默认表名、Java 序列化编解码、默认 TTL，不启动清扫线程。
     *
     * @param dataSource 数据源，不能为 null
     */
    public JdbcAgentSuspendStore(DataSource dataSource) {
        this(dataSource, AgentSuspendPayloadCodec.javaSerialization(),
                DEFAULT_TTL_MILLIS, DEFAULT_TABLE_NAME, false);
    }

    /**
     * 完整构造：建表并按需启动清扫线程。
     *
     * <p>建表失败直接抛出异常（fail fast）——表不可用时本实现无法工作，
     * 带病启动只会把故障推迟到首次读写。</p>
     *
     * @param dataSource       数据源，不能为 null
     * @param codec            载荷编解码器，null 时用 Java 序列化
     * @param defaultTtlMillis 默认 TTL（毫秒），条目 TTL 非正时应用；非正数视为不过期
     * @param tableName        表名（仅允许字母 / 数字 / 下划线），null 时用默认表名
     * @param startSweeper     是否启动守护清扫线程
     */
    public JdbcAgentSuspendStore(DataSource dataSource, AgentSuspendPayloadCodec codec,
                                 long defaultTtlMillis, String tableName, boolean startSweeper) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
        this.codec = codec == null ? AgentSuspendPayloadCodec.javaSerialization() : codec;
        this.defaultTtlMillis = defaultTtlMillis > 0 ? defaultTtlMillis : 0;
        this.tableName = normalizeTableName(tableName);
        try {
            createTableIfAbsent();
        } catch (SQLException e) {
            throw new IllegalStateException("Agent 挂起状态表初始化失败: " + this.tableName, e);
        }
        if (startSweeper) {
            startSweeper();
        }
    }

    @Override
    public void put(AgentSuspendState state) {
        if (state == null || state.key() == null) {
            return;
        }
        AgentSuspendState effective = state;
        if (effective.ttlMillis() <= 0 && defaultTtlMillis > 0) {
            effective = new AgentSuspendState(effective.key(), effective.agentId(), effective.payload(),
                    effective.attributes(), effective.createdAt(), defaultTtlMillis);
        }
        byte[] payloadBytes;
        String attributesJson;
        try {
            payloadBytes = codec.encode(effective.payload());
            attributesJson = effective.attributes().isEmpty()
                    ? null : OBJECT_MAPPER.writeValueAsString(effective.attributes());
        } catch (Exception e) {
            // 载荷 / 元信息不可编码：写入必然失败，按「条目无法恢复」降级，不中断主流程
            log.warn("Agent 挂起状态编码失败, key={}, agentId={}", effective.key(), effective.agentId(), e);
            return;
        }
        // 覆盖写：先删后插（跨库通用；同 key 并发时最后写入者胜出）
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + tableName + " WHERE suspend_key = ?");
                 PreparedStatement insert = connection.prepareStatement(insertSql())) {
                delete.setString(1, effective.key());
                delete.executeUpdate();
                insert.setString(1, effective.key());
                setNullableString(insert, 2, effective.agentId());
                insert.setBytes(3, payloadBytes);
                setNullableString(insert, 4, attributesJson);
                insert.setLong(5, effective.createdAt());
                insert.setLong(6, effective.ttlMillis());
                insert.setLong(7, effective.expireAt());
                insert.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Agent 挂起状态写入失败, key={}, agentId={}", effective.key(), effective.agentId(), e);
        }
    }

    @Override
    public AgentSuspendState get(String key) {
        if (key == null) {
            return null;
        }
        String sql = "SELECT suspend_key, agent_id, payload, attributes, created_at, ttl_millis"
                + " FROM " + tableName + " WHERE suspend_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                AgentSuspendState state = readState(rs);
                if (state == null) {
                    // 坏数据已在读取时清理，按「条目不存在」处理
                    return null;
                }
                if (state.isExpired()) {
                    remove(key);
                    return null;
                }
                return state;
            }
        } catch (Exception e) {
            log.warn("Agent 挂起状态读取失败, key={}", key, e);
            return null;
        }
    }

    @Override
    public AgentSuspendState remove(String key) {
        if (key == null) {
            return null;
        }
        String sql = "SELECT suspend_key, agent_id, payload, attributes, created_at, ttl_millis"
                + " FROM " + tableName + " WHERE suspend_key = ?";
        AgentSuspendState state = null;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    state = readState(rs);
                }
            }
        } catch (Exception e) {
            log.warn("Agent 挂起状态读取失败(移除前查询), key={}", key, e);
            return null;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE suspend_key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("Agent 挂起状态删除失败, key={}", key, e);
        }
        // 一次性消费语义：已过期条目同样清除，但按「不存在」返回
        return state != null && !state.isExpired() ? state : null;
    }

    @Override
    public int size() {
        // 有效条目 = 未设置过期时间 或 尚未到过期时刻
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE expire_at <= 0 OR expire_at > ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? (int) rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Agent 挂起状态计数失败", e);
            return 0;
        }
    }

    @Override
    public void close() {
        Thread thread = sweeper;
        if (thread != null) {
            thread.interrupt();
            sweeper = null;
        }
    }

    /**
     * 清扫已过期条目（供守护线程与运维脚本调用）。
     *
     * <p>只删除 {@code expire_at > 0} 且已到期的行；永不过期（{@code expire_at = 0}）
     * 的条目不清理。清扫失败仅记录日志。</p>
     *
     * @return 本次清除的条目数；清扫失败返回 -1
     */
    public int sweep() {
        String sql = "DELETE FROM " + tableName + " WHERE expire_at > 0 AND expire_at <= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("Agent 挂起状态清扫失败, table={}", tableName, e);
            return -1;
        }
    }

    /**
     * 启动守护清扫线程：周期移除无人读取的过期条目。
     */
    private void startSweeper() {
        if (sweeper != null) {
            return;
        }
        synchronized (this) {
            if (sweeper != null) {
                return;
            }
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(DEFAULT_SWEEP_INTERVAL_MILLIS);
                        sweep();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "agent-suspend-store-sweeper");
            thread.setDaemon(true);
            thread.start();
            sweeper = thread;
        }
    }

    /**
     * 建表（幂等）。
     */
    private void createTableIfAbsent() throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "suspend_key VARCHAR(128) NOT NULL PRIMARY KEY, "
                + "agent_id VARCHAR(128), "
                + "payload BLOB, "
                + "attributes VARCHAR(8192), "
                + "created_at BIGINT NOT NULL, "
                + "ttl_millis BIGINT NOT NULL, "
                + "expire_at BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /**
     * 插入语句。
     */
    private String insertSql() {
        return "INSERT INTO " + tableName
                + " (suspend_key, agent_id, payload, attributes, created_at, ttl_millis, expire_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    }

    /**
     * 从结果集当前行重建挂起状态。
     *
     * <p>载荷解码 / 元信息解析失败视为坏数据：删除该行并返回 null，
     * 避免坏条目永久占位。</p>
     */
    private AgentSuspendState readState(ResultSet rs) throws SQLException {
        String key = rs.getString("suspend_key");
        String agentId = rs.getString("agent_id");
        byte[] payloadBytes = rs.getBytes("payload");
        String attributesJson = rs.getString("attributes");
        long createdAt = rs.getLong("created_at");
        long ttlMillis = rs.getLong("ttl_millis");
        Object payload;
        Map<String, Object> attributes;
        try {
            payload = codec.decode(payloadBytes);
            attributes = attributesJson == null
                    ? Map.of() : OBJECT_MAPPER.readValue(attributesJson, ATTRIBUTES_TYPE);
        } catch (Exception e) {
            log.warn("Agent 挂起状态解码失败, 清理坏数据, key={}", key, e);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM " + tableName + " WHERE suspend_key = ?")) {
                statement.setString(1, key);
                statement.executeUpdate();
            } catch (SQLException cleanupError) {
                log.warn("Agent 挂起状态坏数据清理失败, key={}", key, cleanupError);
            }
            // 按调用方约定返回 null = 条目不存在 / 不可恢复
            return null;
        }
        return new AgentSuspendState(key, agentId, payload, attributes, createdAt, ttlMillis);
    }

    /**
     * 设置可空字符串参数。
     */
    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /**
     * 校验并归一表名（防 SQL 拼接注入：仅允许字母 / 数字 / 下划线）。
     */
    private static String normalizeTableName(String tableName) {
        String name = tableName == null || tableName.isBlank() ? DEFAULT_TABLE_NAME : tableName.trim();
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名（仅允许字母/数字/下划线）: " + name);
        }
        return name;
    }
}
