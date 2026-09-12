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
   * VS 编码 Copilot usage parser.
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
 * }</pre>duration_ms, time_to_first_token_ms, finish_reason, ...
 * )
 * }</pre>
 *
 * <p>Rows only appear after successful GitHub authentication
   * (罚金-grained PAT via {@code GH_TOKEN} 或 OAuth login). The VS Code IDE
   * 延伸 itself keeps usage 服务端-side; only the CLI persists 本地.</p>
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
      * 返回 the SPI 名称 for VS Code Copilot.
     *
     * @return {@code "vscode"}
     */
    /**
      * 响应式流式入口：订阅时才执行装载，配合 限制rate/取 可控制内存水位。
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
      * 解析 全部 usage 事件 从 the Copilot CLI 会话 存储.
     *
     * @return list 的 aiusage records, one per 账单 API 请求
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
      * 转换 one assistant_usage_事件 row into an AIusage record.
     *
     * @param rs 结果 设置 位置 on the row 转为 转换
     * @return populated aiusage record
     * @throws SQLException if column access 失败
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
