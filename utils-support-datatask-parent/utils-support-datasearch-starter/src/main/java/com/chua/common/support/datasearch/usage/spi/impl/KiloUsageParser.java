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
 * Kilo Code CLI usage parser.
 *
 * <p>Kilo CLI is an OpenCode fork storing sessions in a SQLite database at
 * {@code ~/.local/share/kilo/kilo.db}. Unlike upstream OpenCode it keeps
 * denormalized token and cost columns directly on the {@code session} table:</p>
 *
 * <pre>{@code
 * CREATE TABLE session (
 *   id, model, cost,
 *   tokens_input INTEGER, tokens_output INTEGER,
 *   tokens_reasoning INTEGER, tokens_cache_read INTEGER,
 *   tokens_cache_write INTEGER,
 *   time_created TEXT, ... )
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kilo")
public class KiloUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = resolveDbPath();

    private static Path resolveDbPath() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "kilo", "kilo.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "kilo", "kilo.db");
    }

    private static final String SQL_SESSIONS =
            "SELECT id, model, cost, tokens_input, tokens_output, "
                    + "tokens_reasoning, tokens_cache_read, tokens_cache_write, "
                    + "time_created FROM session "
                    + "WHERE tokens_input > 0 OR tokens_output > 0 "
                    + "ORDER BY time_created ASC";

    private static final String PROVIDER_KILO = "kilo";

    /**
     * Returns the SPI name for Kilo.
     *
     * @return {@code "kilo"}
     */
    public String name() {
        return "kilo";
    }

    /**
     * Streams per-session usage records from the Kilo SQLite database.
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[kilo] database not found: {} (Kilo CLI not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("kilo", DB_PATH.toString());
        return engine.query(SQL_SESSIONS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[kilo] stream complete"));
    }

    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("tokens_input"));
        int outputTokens = asInt(row.get("tokens_output"));
        double cost = asDouble(row.get("cost"));
        int reasoning = asInt(row.get("tokens_reasoning"));
        int cacheRead = asInt(row.get("tokens_cache_read"));
        long startTime = parseIsoLike(asStr(row.get("time_created")));

        return AiUsage.builder()
                .provider(PROVIDER_KILO)
                .model(extractModelId(asStr(row.get("model"))))
                .requestId(asStr(row.get("id")))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .build();
    }

    /**
     * Extracts the plain model identifier from the session model column.
     *
     * <p>Kilo stores the model as a JSON object such as
     * {@code {"id":"cohere/north-mini-code:free","providerID":"kilo"}} —
     * only its {@code id} field is used; plain strings pass through.</p>
     *
     * @param raw raw model column value
     * @return model identifier such as {@code cohere/north-mini-code:free}
     */
    private String extractModelId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            com.chua.common.support.lang.json.JsonNode node =
                    com.chua.common.support.lang.json.Json.parse(trimmed);
            String id = node.get("id").toStringValue();
            return id.isBlank() ? "unknown" : id;
        } catch (Exception e) {
            log.debug("[kilo] model json parse failed: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Parses Kilo ISO-like timestamps such as {@code 2026-08-24T07:23:43.123}
     * or full instants with offset.
     *
     * @param ts raw timestamp string from the session table
     * @return epoch milliseconds, or 0 when blank or malformed
     */
    private long parseIsoLike(String ts) {
        if (ts == null || ts.isBlank()) {
            return 0L;
        }
        String candidate = ts.endsWith("Z") ? ts : ts + "Z";
        long millis = parseInstantToMillis(candidate);
        return millis != 0L ? millis : parseInstantToMillis(ts);
    }
}
