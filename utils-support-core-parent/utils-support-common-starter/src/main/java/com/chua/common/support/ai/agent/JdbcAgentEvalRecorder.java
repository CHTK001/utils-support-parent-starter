package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.AiUsage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于关系数据库（JDBC）的评测 / 审计记录器：把每次 Agent 运行结果落库持久保存。
 *
 * <p>实现 {@link AgentEvalHook}，与 {@link InMemoryAgentEvalRecorder}（进程内有界
 * 环形缓冲）互补：本实现把记录写入数据库表（构造时幂等建表），可跨进程、跨实例、
 * 跨重启查询，适合评测回归对比与审计留痕。查询 API 与内存版对齐
 * （{@link #records()} / {@link #size()} / {@link #clear()}），另提供按 Agent 过滤。</p>
 *
 * <p><b>失败语义</b>：遵循 {@link AgentEvalHook} 契约——落库失败不抛出、不阻塞、
 * 不改变运行结果，仅记录 WARN 日志（审计缺失可接受，主流程不可中断）。</p>
 *
 * <p><b>主键</b>：不依赖数据库自增（各库方言不同），由进程内单调计数器生成
 * （基于当前时间戳，同一进程内严格递增，可按 id 排序还原发生顺序）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class JdbcAgentEvalRecorder implements AgentEvalHook {

    /**
     * 默认表名
     */
    public static final String DEFAULT_TABLE_NAME = "agent_eval_record";

    /**
     * usage JSON 序列化器（ObjectMapper 线程安全，静态共享）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * usage JSON 反序列化类型
     */
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    /**
     * 数据源
     */
    private final DataSource dataSource;

    /**
     * 表名（已校验合法性）
     */
    private final String tableName;

    /**
     * 主键生成器（进程内单调递增）
     */
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    /**
     * 使用默认表名。
     *
     * @param dataSource 数据源，不能为 null
     */
    public JdbcAgentEvalRecorder(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME);
    }

    /**
     * 完整构造：建表（幂等）。
     *
     * <p>建表失败直接抛出异常（fail fast）——表不可用时记录器无法工作。</p>
     *
     * @param dataSource 数据源，不能为 null
     * @param tableName  表名（仅允许字母 / 数字 / 下划线），null 时用默认表名
     */
    public JdbcAgentEvalRecorder(DataSource dataSource, String tableName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
        this.tableName = normalizeTableName(tableName);
        try {
            createTableIfAbsent();
        } catch (SQLException e) {
            throw new IllegalStateException("Agent 评测记录表初始化失败: " + this.tableName, e);
        }
    }

    @Override
    public void onRunStart(AgentEvalContext context) {
        // 开始事件无持久信息，记录器只在结束时落库
    }

    @Override
    public void onRunEnd(AgentEvalRecord record) {
        if (record == null) {
            return;
        }
        String usageJson = null;
        try {
            usageJson = record.usage() == null ? null : OBJECT_MAPPER.writeValueAsString(record.usage());
        } catch (Exception e) {
            // 用量序列化失败不丢整条记录，仅缺 usage 列
            log.warn("Agent 评测记录 usage 序列化失败, agentId={}",
                    record.context() == null ? null : record.context().agentId(), e);
        }
        AgentEvalContext context = record.context();
        String sql = "INSERT INTO " + tableName
                + " (id, agent_id, session_id, mode, input, output, success, error,"
                + " duration_millis, usage_json, start_millis, end_millis)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, idGenerator.incrementAndGet());
            setNullableString(statement, 2, context == null ? null : context.agentId());
            setNullableString(statement, 3, context == null ? null : context.sessionId());
            setNullableString(statement, 4, context == null ? null : context.mode());
            setNullableString(statement, 5, context == null ? null : context.input());
            setNullableString(statement, 6, record.output());
            statement.setBoolean(7, record.success());
            setNullableString(statement, 8, record.error());
            statement.setLong(9, record.durationMillis());
            setNullableString(statement, 10, usageJson);
            statement.setLong(11, context == null ? 0L : context.startMillis());
            statement.setLong(12, (context == null ? 0L : context.startMillis()) + record.durationMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("Agent 评测记录落库失败, agentId={}",
                    context == null ? null : context.agentId(), e);
        }
    }

    /**
     * 读取全部记录（按发生顺序）。
     *
     * @return 记录快照；查询失败返回空列表
     */
    public List<AgentEvalRecord> records() {
        return recordsByAgent(null);
    }

    /**
     * 按 Agent 过滤读取记录（按发生顺序）。
     *
     * @param agentId Agent 标识；null / 空表示不过滤
     * @return 记录快照；查询失败返回空列表
     */
    public List<AgentEvalRecord> recordsByAgent(String agentId) {
        List<AgentEvalRecord> records = new ArrayList<>();
        boolean filter = agentId != null && !agentId.isBlank();
        String sql = "SELECT agent_id, session_id, mode, input, output, success, error,"
                + " duration_millis, usage_json, start_millis FROM " + tableName;
        if (filter) {
            sql += " WHERE agent_id = ?";
        }
        sql += " ORDER BY id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (filter) {
                statement.setString(1, agentId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    AgentEvalRecord record = readRecord(rs);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Agent 评测记录查询失败, agentId={}", agentId, e);
        }
        return records;
    }

    /**
     * 当前记录总数。
     *
     * @return 记录数；查询失败返回 0
     */
    public int size() {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? (int) rs.getLong(1) : 0;
        } catch (SQLException e) {
            log.warn("Agent 评测记录计数失败", e);
            return 0;
        }
    }

    /**
     * 清空全部记录。
     */
    public void clear() {
        String sql = "DELETE FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            log.warn("Agent 评测记录清空失败", e);
        }
    }

    /**
     * 从结果集当前行重建记录。
     *
     * <p>usage JSON 解析失败不丢记录，仅 usage 字段为 null。</p>
     */
    private AgentEvalRecord readRecord(ResultSet rs) throws SQLException {
        String agentId = rs.getString("agent_id");
        String sessionId = rs.getString("session_id");
        String mode = rs.getString("mode");
        String input = rs.getString("input");
        String output = rs.getString("output");
        boolean success = rs.getBoolean("success");
        String error = rs.getString("error");
        long durationMillis = rs.getLong("duration_millis");
        String usageJson = rs.getString("usage_json");
        long startMillis = rs.getLong("start_millis");
        AiUsage usage = usageJson == null ? null : parseUsage(usageJson, agentId);
        AgentEvalContext context = new AgentEvalContext(agentId, sessionId, input, mode, startMillis);
        return new AgentEvalRecord(context, output, success, error, durationMillis, usage);
    }

    /**
     * 把 usage JSON 重建为 {@link AiUsage}。
     *
     * <p>{@link AiUsage} 由 Lombok {@code @Builder} 生成，没有公开无参构造器，
     * Jackson 无法直接反序列化；这里按「JSON 属性名 → builder 链式方法」泛化映射，
     * 数值类型做宽松转换（JSON 小数默认解析为 Double，费用字段目标是 BigDecimal）。
     * 未识别的属性跳过，不丢整条记录。</p>
     */
    private AiUsage parseUsage(String usageJson, String agentId) {
        try {
            Map<String, Object> fields = OBJECT_MAPPER.readValue(usageJson, MAP_TYPE);
            Object builder = AiUsage.builder();
            Class<?> builderClass = builder.getClass();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                if (entry.getValue() != null) {
                    applyBuilderField(builder, builderClass, entry.getKey(), entry.getValue());
                }
            }
            return ((AiUsage.AiUsageBuilder) builder).build();
        } catch (Exception e) {
            log.warn("Agent 评测记录 usage 反序列化失败, agentId={}", agentId);
            return null;
        }
    }

    /**
     * 把单个 JSON 属性应用到 builder：先按精确参数类型匹配，失败再做宽松类型转换。
     */
    private static void applyBuilderField(Object builder, Class<?> builderClass,
                                          String name, Object value) {
        try {
            java.lang.reflect.Method exact = builderClass.getMethod(name, value.getClass());
            exact.invoke(builder, value);
            return;
        } catch (NoSuchMethodException ignored) {
            // 落到宽松匹配
        } catch (Exception e) {
            log.debug("Agent 评测记录 usage 属性应用失败, field={}", name);
            return;
        }
        for (java.lang.reflect.Method method : builderClass.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Object converted = coerceValue(value, method.getParameterTypes()[0]);
            if (converted != null) {
                try {
                    method.invoke(builder, converted);
                    return;
                } catch (Exception e) {
                    log.debug("Agent 评测记录 usage 属性应用失败, field={}", name);
                    return;
                }
            }
        }
    }

    /**
     * JSON 反序列化值 → builder 方法参数类型的宽松转换。
     *
     * @return 可用值；不可转换返回 null
     */
    private static Object coerceValue(Object value, Class<?> target) {
        if (target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            if (target == Integer.class || target == int.class) {
                return number.intValue();
            }
            if (target == Long.class || target == long.class) {
                return number.longValue();
            }
            if (target == Double.class || target == double.class) {
                return number.doubleValue();
            }
            if (target == BigDecimal.class) {
                return new BigDecimal(number.toString());
            }
        }
        return null;
    }

    /**
     * 建表（幂等）。
     */
    private void createTableIfAbsent() throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id BIGINT NOT NULL PRIMARY KEY, "
                + "agent_id VARCHAR(128), "
                + "session_id VARCHAR(128), "
                + "mode VARCHAR(64), "
                + "input TEXT, "
                + "output TEXT, "
                + "success BOOLEAN NOT NULL, "
                + "error TEXT, "
                + "duration_millis BIGINT NOT NULL, "
                + "usage_json TEXT, "
                + "start_millis BIGINT NOT NULL, "
                + "end_millis BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
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
