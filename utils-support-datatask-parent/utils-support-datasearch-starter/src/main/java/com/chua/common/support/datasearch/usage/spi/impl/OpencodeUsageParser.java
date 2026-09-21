package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
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
     * 消息表用量查询：一次请求一行，附带起止时间（算耗时）、首字时间、结束原因与消息主键（作请求号）
     *
     * <p>结束时间取 {@code $.time.completed} 而不是行上的 {@code time_updated}：后者在
     * 140885 条里有 107763 条晚于真正的完成时刻（消息行完成后还会被更新），拿它算耗时偏大。
     * 首字时间取该消息最早的文字/推理/工具块，即模型第一个产出落库的时刻。</p>
     */
    private static final String SQL_MESSAGES =
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
            + "WHERE CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
            + "   OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0 "
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
        List<AiUsage> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            try (PreparedStatement stmt = conn.prepareStatement(SQL_MESSAGES)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(toAiUsage(rs));
                    }
                }
            }
            log.info("[opencode] 解析完成，共 {} 条用量记录", result.size());
        } catch (SQLException e) {
            log.warn("[opencode] 解析失败: {}", e.getMessage(), e);
        }
        return result;
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
