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

/**
 * CC Switch usage parser.
 *
 * <p>CC Switch (github.com/farion1231/cc-switch) is a Tauri desktop manager
 * for Claude Code / Codex / Gemini CLI providers. It keeps a SQLite database
 * at {@code ~/.cc-switch/cc-switch.db} whose {@code proxy_request_logs}
 * table records every routed request (proxy interception or CLI session
 * import) with full token, cost and latency detail.</p>
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
     * 返回 SPI 名称。
     *
     * @return {@code "ccswitch"}
     */
    @Override
    public String name() {
        return "ccswitch";
    }

    /**
     * 响应式流式入口：逐行流出请求日志，内存占用与日志总量无关。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[ccswitch] database not found: {} (CC Switch not installed)", DB_PATH);
            return Flux.empty();
        }
        return Flux.<AiUsage>create(sink -> {
            long count = 0L;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 PreparedStatement stmt = conn.prepareStatement(SQL_REQUEST_LOGS);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && !sink.isCancelled()) {
                    sink.next(toAiUsage(rs));
                    count++;
                }
                sink.complete();
                log.info("[ccswitch] streamed {} request log records", count);
            } catch (SQLException e) {
                log.warn("[ccswitch] parse failed: {}", e.getMessage(), e);
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

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

    private String finishReason(String appType, int statusCode) {
        String app = appType == null || appType.isBlank() ? "unknown" : appType;
        return statusCode == HTTP_OK ? app + ":stop" : app + ":http-" + statusCode;
    }
}
