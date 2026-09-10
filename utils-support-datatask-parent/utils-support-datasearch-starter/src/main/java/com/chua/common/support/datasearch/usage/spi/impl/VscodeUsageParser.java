package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * VSCode Copilot usage parser.
 *
 * <p>GitHub Copilot CLI stores per-request usage in a local SQLite database at
 * {@code ~/.copilot/session-store.db}, table {@code assistant_usage_events}:</p>
 *
 * <pre>{@code
 * CREATE TABLE assistant_usage_events (
 *   id, session_id, turn_index, agent_id, model,
 *   input_tokens, output_tokens, cache_read_tokens,
 *   cache_write_tokens, reasoning_tokens, total_nano_aiu,
 *   duration_ms, time_to_first_token_ms, finish_reason, ...
 * )
 * }</pre>
 *
 * <p>Rows only appear after successful GitHub authentication
 * (fine-grained PAT via {@code GH_TOKEN} or OAuth login). The VSCode IDE
 * extension itself keeps usage server-side; only the CLI persists locally.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vscode")
public class VscodeUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".copilot", "session-store.db");

    private static final String SQL_USAGE_EVENTS =
            "SELECT created_at, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, reasoning_tokens, "
                    + "duration_ms, time_to_first_token_ms, finish_reason, session_id "
                    + "FROM assistant_usage_events "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    /**
     * Returns the SPI name for VSCode Copilot.
     *
     * @return {@code "vscode"}
     */
    /**
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @Override
    public String name() {
        return "vscode";
    }

    /**
     * Parses all usage events from the Copilot CLI session store.
     *
     * @return list of AiUsage records, one per billed API request
     */
    @Override protected List<AiUsage> parseAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[vscode] session store not found: {} (Copilot CLI not installed/authenticated)", DB_PATH);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
             PreparedStatement stmt = conn.prepareStatement(SQL_USAGE_EVENTS);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(toAiUsage(rs));
            }
            log.info("[vscode] parsed {} usage events", result.size());
        } catch (SQLException e) {
            log.warn("[vscode] parse failed: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * Converts one assistant_usage_events row into an AiUsage record.
     *
     * @param rs result set positioned on the row to convert
     * @return populated AiUsage record
     * @throws SQLException if column access fails
     */
    private AiUsage toAiUsage(ResultSet rs) throws SQLException {
                long startTime = parseInstantToMillis(rs.getString(1));
        String model = rs.getString(2);
        int inputTokens = rs.getInt(3);
        int outputTokens = rs.getInt(4);
        int cacheRead = rs.getInt(5);
        int cacheWrite = rs.getInt(6);
        int reasoning = rs.getInt(7);
        long duration = rs.getLong(8);
        long ttft = rs.getLong(9);
        String finishReason = rs.getString(10);
        String sessionId = rs.getString(11);

        return AiUsage.builder()
                .provider("copilot")
                .model(model)
                .requestId(sessionId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(duration > 0 ? duration : null)
                .firstTokenLatencyMillis(ttft > 0 ? ttft : null)
                .finishReason(finishReason)
                .build();
    }
}
