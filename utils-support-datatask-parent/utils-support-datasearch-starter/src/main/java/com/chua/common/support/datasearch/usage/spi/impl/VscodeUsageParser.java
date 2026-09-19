package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * <p>Cost is reported as {@code total_nano_aiu} — integer nano-AIU where
 * 10_000_000_000 ticks equals one US dollar. This parser converts it to USD
 * and also reads {@code token_details_json} to surface cache read/write
 * breakdown and per-token-type unit prices.</p>
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

    /**
     * Nano-AIU ticks per US dollar (GitHub's reported cost unit).
     */
    private static final long NANO_AIU_PER_USD = 10_000_000_000L;

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".copilot", "session-store.db");

    private static final String SQL_USAGE_EVENTS =
            "SELECT created_at, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, reasoning_tokens, "
                    + "total_nano_aiu, token_details_json, duration_ms, "
                    + "time_to_first_token_ms, finish_reason, session_id "
                    + "FROM assistant_usage_events "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    /**
     * 返回 SPI 名称（for VSCode Copilot）。
     *
     * @return {@code "vscode"}
     */
    @Override
    public String name() {
        return "vscode";
    }

    /**
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 解析全部用量事件（from the Copilot CLI session store）。
     *
     * @return list of AiUsage records, one per billed API request
     */
    @Override
    protected List<AiUsage> parseAll() {
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
     * 转换一条 assistant_usage_events 行为 AiUsage 记录。
     *
     * @param rs 结果集（current row）
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
        long nanoAiu = rs.getLong(8);
        String tokenDetailsJson = rs.getString(9);
        long duration = rs.getLong(10);
        long ttft = rs.getLong(11);
        String finishReason = rs.getString(12);
        String sessionId = rs.getString(13);

        BigDecimal costUsd = convertNanoAiuToUsd(nanoAiu);

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
                .totalCost(costUsd)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(duration > 0 ? duration : null)
                .firstTokenLatencyMillis(ttft > 0 ? ttft : null)
                .finishReason(finishReason)
                .build();
    }

    /**
     * 将 nano-AIU 计数转换为 USD。
     *
     * @param nanoAiu GitHub reported 整数 nano-AIU
     * @return USD 金额（0 或负数返回 null）
     */
    private BigDecimal convertNanoAiuToUsd(long nanoAiu) {
        if (nanoAiu <= 0) {
            return null;
        }
        return BigDecimal.valueOf(nanoAiu).divide(BigDecimal.valueOf(NANO_AIU_PER_USD));
    }
}
