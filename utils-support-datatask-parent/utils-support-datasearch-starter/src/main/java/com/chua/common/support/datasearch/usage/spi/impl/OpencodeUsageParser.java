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
 * 输入/输出/ReasonML/缓存 令牌、费用、模型及服务商信息，
 * 映射为标准的 {@link AiUsage} 记录。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(OpencodeUsageParser.class); // 日志

    /** DB 路径 */
    private static final Path DB_PATH = resolveDbPath();

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    /**
     * SQL: 从 消息 表按 令牌 用量筛选并返回每条请求的用量字段
     *
     * @param rs R
     * @return 转为AIusage的结果
     */
    private static final String SQL_MESSAGES =
            "SELECT time_created, "
            + "CAST(json_extract(data, '$.providerID') AS TEXT), "
            + "CAST(json_extract(data, '$.modelID') AS TEXT), "
            + "CAST(json_extract(data, '$.tokens.input') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.output') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.reasoning') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.read') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.write') AS INTEGER), "
            + "CAST(json_extract(data, '$.cost') AS REAL) "
            + "FROM message "
            /**
             * 名称。
             * @return 名称的结果
             * @param rs R
             */
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
        String provider = rs.getString(2);
        String model = rs.getString(3);
        int inputTokens = rs.getInt(4);
        int outputTokens = rs.getInt(5);
        int reasoningTokens = rs.getInt(6);
        int cacheRead = rs.getInt(7);
        int cacheWrite = rs.getInt(8);
        double costDouble = rs.getDouble(9);

        int totalTokens = inputTokens + outputTokens;
        BigDecimal totalCost = BigDecimal.valueOf(costDouble);

        return AiUsage.builder()
                .provider(provider)
                .model(model)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .reasoningTokens(reasoningTokens > 0 ? reasoningTokens : null)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .build();
    }
}
