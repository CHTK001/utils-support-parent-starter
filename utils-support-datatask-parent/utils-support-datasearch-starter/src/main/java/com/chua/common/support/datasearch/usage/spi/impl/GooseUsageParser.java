package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Goose usage parser.
 *
 * <p>Goose (github.com/block/goose) stores sessions in a SQLite database at
 * {@code %APPDATA%\Block\goose\data\sessions\sessions.db} on Windows. The
 * {@code usage_ledger} table records one row per LLM call with exact token,
   * 缓存 和 cost 归因:</p>
 *
 * <pre>{@code
 * CREATE TABLE usage_ledger (
 *   id, session_id, created_timestamp,   -- epoch seconds
 *   model TEXT, input_tokens INTEGER, output_tokens INTEGER,
 *   total_tokens INTEGER, cache_read_tokens INTEGER,
 *   cache_write_tokens INTEGER, cost REAL, cost_source TEXT, ...
 * )
 * }</pre>_write_tokens INTEGER, cost REAL, cost_source TEXT, ...
 * )
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("goose")
public class GooseUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(System.getenv("APPDATA"),
            "Block", "goose", "data", "sessions", "sessions.db");

    private static final String SQL_LEDGER =
            "SELECT session_id, created_timestamp, model, input_tokens, output_tokens, "
                    + "total_tokens, cache_read_tokens, cache_write_tokens, cost, cost_source "
                    + "FROM usage_ledger "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_timestamp ASC";

    private static final String PROVIDER_GOOSE = "goose"; // 提供者goose
    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L; // 轮次seconds转为millis

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "goose"}
     */
    public String name() {
        return "goose";
    }

    /**
     * 流式解析全部用量台账记录。
     * @param row row
     * @return 转为AIusage的结果
     /**
      * 流全部。
      * @return 流全部的结果
      */
      * @param row row
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[goose] database not found: {} (Goose not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("goose", DB_PATH.toString());
        return engine.query(SQL_LEDGER)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[goose] stream complete"));
    }

    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        long createdAtSeconds = asLong(row.get("created_timestamp"));
        double cost = asDouble(row.get("cost"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        int cacheWrite = asInt(row.get("cache_write_tokens"));

        return AiUsage.builder()
                .provider(PROVIDER_GOOSE)
                .model(asStr(row.get("model")))
                .requestId(asStr(row.get("session_id")))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(asInt(row.get("total_tokens")) > 0
                        ? asInt(row.get("total_tokens")) : inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                .currency("USD")
                .estimated("estimated".equals(asStr(row.get("cost_source"))))
                .startTime(createdAtSeconds > 0
                        ? createdAtSeconds * EPOCH_SECONDS_TO_MILLIS : null)
                .build();
    }
}
