package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode usage parser.
 *
 * <p>Parses session and per-message usage from the local SQLite database at
 * {@code ~/.local/share/opencode/opencode.db}, reading the JSON {@code data}
 * column of the {@code message} table.</p>
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

    /**
     * Returns the SPI name for OpenCode.
     *
     * @return {@code "opencode"}
     */
    public String name() {
        return "opencode";
    }

    /**
     * Streams usage rows from the OpenCode SQLite database.
     *
     * <p>The JDBC cursor runs on boundedElastic; rows are emitted lazily so
     * memory stays flat regardless of table size.</p>
     *
     * @return stream of AiUsage records ordered by creation time
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[opencode] database file not found: {}", DB_PATH);
            return Flux.empty();
        }
        return Flux.<AiUsage>create(sink -> {
            long count = 0L;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 PreparedStatement stmt = conn.prepareStatement(SQL_MESSAGES);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && !sink.isCancelled()) {
                    sink.next(toAiUsage(rs));
                    count++;
                }
                sink.complete();
                log.info("[opencode] streamed {} usage records", count);
            } catch (SQLException e) {
                log.warn("[opencode] parse failed: {}", e.getMessage(), e);
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
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
