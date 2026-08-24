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
 * CC Switch usage parser.
 *
 * <p>CC Switch (github.com/farion1231/cc-switch) is a Tauri desktop manager
 * for Claude Code / Codex / Gemini CLI providers. It keeps a SQLite database
 * at {@code ~/.cc-switch/cc-switch.db} whose {@code proxy_request_logs}
 * table records every routed request (proxy interception or CLI session
 * import) with full token, cost and latency detail:</p>
 *
 * <pre>{@code
 * CREATE TABLE proxy_request_logs (
 *   request_id TEXT, provider_id TEXT, app_type TEXT,
 *   model TEXT, input_tokens INTEGER, output_tokens INTEGER,
 *   cache_read_tokens INTEGER, cache_creation_tokens INTEGER,
 *   total_cost_usd TEXT, latency_ms INTEGER, first_token_ms INTEGER,
 *   duration_ms INTEGER, status_code INTEGER, session_id TEXT,
 *   created_at INTEGER,            -- epoch seconds
 *   data_source TEXT, ... )
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ccswitch")
public class CcswitchUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".cc-switch", "cc-switch.db");

    private static final String SQL_REQUEST_LOGS =
            "SELECT request_id, app_type, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_creation_tokens, total_cost_usd, "
                    + "duration_ms, first_token_ms, status_code, session_id, created_at "
                    + "FROM proxy_request_logs "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    private static final String PROVIDER_CC_SWITCH = "cc-switch";
    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L;
    private static final int HTTP_OK = 200;

    /**
     * Returns the SPI name for CC Switch.
     *
     * @return {@code "ccswitch"}
     */
    @Override
    /**
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    public String name() {
        return "ccswitch";
    }

    /**
     * Parses all request logs from the CC Switch SQLite database.
     *
     * @return list of AiUsage records, one per logged API request
     */
    private List<AiUsage> parseAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[ccswitch] database not found: {} (CC Switch not installed)", DB_PATH);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
             PreparedStatement stmt = conn.prepareStatement(SQL_REQUEST_LOGS);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(toAiUsage(rs));
            }
            log.info("[ccswitch] parsed {} request log records", result.size());
        } catch (SQLException e) {
            log.warn("[ccswitch] parse failed: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * Converts one proxy_request_logs row into an AiUsage record.
     *
     * @param rs result set positioned on the row to convert
     * @return populated AiUsage record
     * @throws SQLException if column access fails
     */
    private AiUsage toAiUsage(ResultSet rs) throws SQLException {
        String requestId = rs.getString(1);
        String appType = rs.getString(2);
        String model = rs.getString(3);
        int inputTokens = rs.getInt(4);
        int outputTokens = rs.getInt(5);
        int cacheRead = rs.getInt(6);
        int cacheCreation = rs.getInt(7);
        double costUsd = parseCost(rs.getString(8));
        long durationMs = rs.getLong(9);
        long firstTokenMs = rs.getLong(10);
        int statusCode = rs.getInt(11);
        String sessionId = rs.getString(12);
        long createdAtSeconds = rs.getLong(13);

        return AiUsage.builder()
                .provider(PROVIDER_CC_SWITCH)
                .model(model)
                .requestId(firstNonBlank(requestId, sessionId))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(costUsd > 0 ? BigDecimal.valueOf(costUsd) : null)
                .currency("USD")
                .startTime(createdAtSeconds > 0
                        ? createdAtSeconds * EPOCH_SECONDS_TO_MILLIS : null)
                .durationMillis(durationMs > 0 ? durationMs : null)
                .firstTokenLatencyMillis(firstTokenMs > 0 ? firstTokenMs : null)
                .finishReason(finishReason(appType, statusCode))
                .build();
    }

    /**
     * Parses a TEXT cost column into a double value.
     *
     * @param raw raw cost string such as {@code "0.0123"}
     * @return parsed value, or 0 when blank or malformed
     */
    private double parseCost(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0d;
        }
    }

    /**
     * Builds a traceable finish reason combining app type and HTTP status.
     *
     * @param appType   target application such as claude / codex / gemini
     * @param statusCode HTTP status code of the routed request
     * @reason reason string such as {@code "claude:200"} or {@code "claude:error-500"}
     */
    private String finishReason(String appType, int statusCode) {
        String app = appType == null || appType.isBlank() ? "unknown" : appType;
        return statusCode == HTTP_OK ? app + ":stop" : app + ":http-" + statusCode;
    }
}
