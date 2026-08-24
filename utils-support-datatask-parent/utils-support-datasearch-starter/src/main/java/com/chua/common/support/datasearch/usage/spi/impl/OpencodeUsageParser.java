package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode 用量解析器 — 从本地 SQLite 数据库解析会话与消息用量。
 *
 * <p>数据源为 {@code %USERPROFILE%\.local\share\opencode\opencode.db}，
 * 读取 {@code message} 表中的 {@code data} JSON 列，提取每次请求的
 * input/output/reasoning/cache tokens、费用、模型及服务商信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");

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
                    + "WHERE CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
                    + "   OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0 "
                    + "ORDER BY time_created ASC";

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
