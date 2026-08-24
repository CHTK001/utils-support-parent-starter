package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * OpenCode usage parser.
 *
 * <p>Parses session and per-message usage from the local SQLite database at
 * {@code ~/.local/share/opencode/opencode.db}, reading the JSON {@code data}
 * column of the {@code message} table through {@link SqliteReactorEngine}.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");

    private static final String SQL_MESSAGES =
            "SELECT time_created AS time_created, "
                    + "json_extract(data, '$.providerID') AS provider_id, "
                    + "json_extract(data, '$.modelID') AS model_id, "
                    + "json_extract(data, '$.tokens.input') AS input_tokens, "
                    + "json_extract(data, '$.tokens.output') AS output_tokens, "
                    + "json_extract(data, '$.tokens.reasoning') AS reasoning_tokens, "
                    + "json_extract(data, '$.tokens.cache.read') AS cache_read_tokens, "
                    + "json_extract(data, '$.tokens.cache.write') AS cache_write_tokens, "
                    + "json_extract(data, '$.cost') AS cost "
                    + "FROM message "
                    + "WHERE json_extract(data, '$.tokens.input') > 0 "
                    + "   OR json_extract(data, '$.tokens.output') > 0 "
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
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[opencode] database file not found: {}", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("opencode", DB_PATH.toString());
        return engine.query(SQL_MESSAGES)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[opencode] stream complete"));
    }

    private AiUsage toAiUsage(Map<String, Object> row) {
        long startTime = asLong(row.get("time_created"));
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        int reasoningTokens = asInt(row.get("reasoning_tokens"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        double costDouble = asDouble(row.get("cost"));

        BigDecimal totalCost = BigDecimal.valueOf(costDouble);

        return AiUsage.builder()
                .provider(asStr(row.get("provider_id")))
                .model(asStr(row.get("model_id")))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .reasoningTokens(reasoningTokens > 0 ? reasoningTokens : null)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .build();
    }
}
