// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;

/**
 * 打开编码 用量解析器 — 从本地 sqlite 数据库解析会话与消息用量
 *
 * <p>数据源: {@code %USERPROFILE%\.local\share\opencode\opencode.db}
 *
 * <p>解析 {@code message} 表中的 {@code data} JSON 列，提取每次请求的
 * 输入/输出/推理/缓存 令牌、费用、模型及服务商信息，
 * 映射为标准的 {@link AiUsage} 记录。
 *
 * <p>源的 {@code tokens.input} 只记未命中缓存的输入，与 {@code cache.read}、
 * {@code cache.write} 三段互斥（本机实测输入中位数 988、命中中位数 101376），
 * 因此三者相加才是全量输入；命中量另记于 {@code cacheTokens} 供补全器折算。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(OpencodeUsageParser.class); // 日志

    /**
     * DB 路径
    */
    private static final Path DB_PATH = resolveDbPath();

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        Path fallback = Path.of(System.getProperty("user.home"),
                ".local", "share", "opencode", "opencode.db");
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return firstExisting(Path.of(xdgDataHome, "opencode", "opencode.db"), fallback);
        }
        return firstExisting(fallback);
    }

    /**
     * 增量水位：上一轮已解析到的最大 {@code time_created}（epoch 毫秒）。
     *
     * <p>本机新库 {@code session_message} 已有 101 万条带 token 的 assistant 行，全量扫描
     * 单轮需 60~120 秒；而调度每 5 分钟一轮，全量重扫会让同步长期占用连接与内存。
     * 首轮（水位为 0）全量补齐，之后只取水位前移一天的数据。</p>
     */
    private static volatile long watermarkMillis = 0L;

    /**
     * 增量重叠窗口（毫秒）：一天。
     *
     * <p>消息行可能先落库、完成时才写入 token，只按 {@code time_created} 卡水位会把这类
     * 迟到的完成记录永久漏掉，故每次回看一天；重叠部分由 {@code UsageAggregationTask}
     * 的 requestId 去重挡住。</p>
     */
    private static final long OVERLAP_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * 新库 {@code session_message} 用量查询（9-03 之后的数据都在这里）。
     *
     * <p>与旧 {@code message} 表的列序保持一致，共用 {@link #toAiUsage}。首字时间取
     * {@code content} 里最早带 {@code time.created} 的块（文字/推理/工具），即模型第一个
     * 产出落库的时刻。</p>
     */
    private static final String SQL_SESSION_MESSAGES =
            "SELECT time_created, COALESCE(CAST(json_extract(data, '$.time.completed') AS INTEGER), time_updated), "
            + "CAST(json_extract(data, '$.finish') AS TEXT), "
            + "CAST(json_extract(data, '$.model.providerID') AS TEXT), "
            + "CAST(json_extract(data, '$.model.id') AS TEXT), "
            + "CAST(json_extract(data, '$.tokens.input') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.output') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.reasoning') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.read') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.write') AS INTEGER), "
            + "CAST(json_extract(data, '$.cost') AS REAL), "
            + "(SELECT MIN(CAST(json_extract(c.value, '$.time.created') AS INTEGER)) "
            + "   FROM json_each(json_extract(data, '$.content')) c "
            + "  WHERE json_extract(c.value, '$.time.created') IS NOT NULL), "
            + "id "
            + "FROM session_message "
            + "WHERE type = 'assistant' "
            + "  AND (CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
            + "    OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0) "
            + "  AND time_created > ? "
            + "ORDER BY time_created ASC";

    /**
     * 旧库 {@code message} 表用量查询：只补新库没有的行。
     *
     * <p>两表是并行的两套记录而非前后接力——9-14 旧表 4038 行而新表仅 465 行，
     * 8-10~9-02 更是只有旧表有；重叠 id 的 token 全量比对 25228 条完全一致，
     * 故按 id 反连接取并集，不会把同一条请求算两遍。</p>
     */
    private static final String SQL_LEGACY_MESSAGES =
            "SELECT time_created, COALESCE(CAST(json_extract(data, '$.time.completed') AS INTEGER), time_updated), "
            + "CAST(json_extract(data, '$.finish') AS TEXT), "
            + "CAST(json_extract(data, '$.providerID') AS TEXT), "
            + "CAST(json_extract(data, '$.modelID') AS TEXT), "
            + "CAST(json_extract(data, '$.tokens.input') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.output') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.reasoning') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.read') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.write') AS INTEGER), "
            + "CAST(json_extract(data, '$.cost') AS REAL), "
            + "(SELECT MIN(p.time_created) FROM part p "
            + " WHERE p.message_id = message.id "
            + "   AND json_extract(p.data, '$.type') IN ('text', 'reasoning', 'tool')), "
            + "id "
            + "FROM message "
            + "WHERE (CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
            + "    OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0) "
            + "  AND time_created > ? "
            + "  AND NOT EXISTS (SELECT 1 FROM session_message s "
            + "                   WHERE s.id = message.id AND s.type = 'assistant') "
            + "ORDER BY time_created ASC";

    /**
     * 名称。
     * @return 名称的结果
     */
    @Override
    public String name() {
        return "opencode";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[opencode] 数据库文件不存在: {}", DB_PATH);
            return List.of();
        }
        List<AiUsage> result = Wraps.list();
        long from = watermarkMillis <= 0 ? 0L : Math.max(0L, watermarkMillis - OVERLAP_MILLIS);
        long maxSeen = watermarkMillis;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            maxSeen = Math.max(maxSeen, query(conn, SQL_SESSION_MESSAGES, from, result));
            maxSeen = Math.max(maxSeen, query(conn, SQL_LEGACY_MESSAGES, from, result));
            if (maxSeen > watermarkMillis) {
                watermarkMillis = maxSeen;
            }
            log.info("[opencode] 解析完成，共 {} 条用量记录（起点 {}，新水位 {}）",
                    result.size(), from, watermarkMillis);
        } catch (SQLException e) {
            log.warn("[opencode] 解析失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 按水位执行一条用量查询，并把结果追加到集合。
     *
     * @param conn   数据库连接
     * @param sql    查询语句（最后一个占位符为起始时间）
     * @param from   起始时间（epoch 毫秒，0 表示全量）
     * @param result 结果收集器
     * @return 本次查询见到的最大 time_created；无数据时返回 0
     * @throws SQLException 当执行过程不满足前置条件时
     */
    private long query(Connection conn, String sql, long from, List<AiUsage> result) throws SQLException {
        long maxSeen = 0L;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, from);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(toAiUsage(rs));
                    long created = rs.getLong(1);
                    if (created > maxSeen) {
                        maxSeen = created;
                    }
                }
            }
        }
        return maxSeen;
    }

    /**
     * 转为AiUsage。
     *
     * @param rs 方法入参 rs
     * @return AiUsage 对象
     * @throws SQLException 当执行过程不满足前置条件时
     */
    private AiUsage toAiUsage(ResultSet rs) throws SQLException {
        long startTime = rs.getLong(1);
        long endTime = rs.getLong(2);
        String finishReason = rs.getString(3);
        String provider = rs.getString(4);
        String model = rs.getString(5);
        int inputTokens = rs.getInt(6);
        int outputTokens = rs.getInt(7);
        int reasoningTokens = rs.getInt(8);
        int cacheRead = rs.getInt(9);
        int cacheWrite = rs.getInt(10);
        double costDouble = rs.getDouble(11);
        long firstTokenTime = rs.getLong(12);
        String requestId = rs.getString(13);

        int promptTokens = Math.max(0, inputTokens) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
        int cacheHit = Math.min(cacheRead, promptTokens);
        int totalTokens = promptTokens + Math.max(0, outputTokens);
        BigDecimal totalCost = BigDecimal.valueOf(costDouble);
        long duration = endTime > startTime ? endTime - startTime : 0L;
        long firstToken = firstTokenTime > startTime ? firstTokenTime - startTime : 0L;

        return AiUsage.builder()
                .provider(provider)
                .model(model)
                .requestId(requestId)
                .finishReason(finishReason)
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .reasoningTokens(reasoningTokens > 0 ? reasoningTokens : null)
                .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                .totalCost(totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(duration > 0 ? duration : null)
                .firstTokenLatencyMillis(firstToken > 0 ? firstToken : null)
                .build();
    }
}