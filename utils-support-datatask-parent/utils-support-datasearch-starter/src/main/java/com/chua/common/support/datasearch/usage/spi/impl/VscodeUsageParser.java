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
 * VS Code Copilot 用量解析器。
 *
 * <p>GitHub Copilot 命令行客户端将每次请求的用量写入本地 SQLite 数据库
 * {@code ~/.copilot/session-store.db} 的 {@code assistant_usage_events} 表：</p>
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
 * <p>费用通过 {@code total_nano_aiu} 字段记录，每 10,000,000,000 个计数等于一美元。
 * 本解析器将计数换算为美元，并从独立字段读取缓存读写量、推理量和延迟。
 * 每行对应一次请求，但 {@code session_id} 会被同一会话的多次请求共用，
 * 因此请求号取 {@code session_id + "-" + id}。</p>
 *
 * <p>完成 GitHub 身份认证后才会写入记录；VS Code 扩展本身仍由服务端保存用量，
 * 仅命令行客户端会持久化本地记录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vscode")
public class VscodeUsageParser extends BaseUsageParser {

    /**
     * 每美元对应的纳诺人工智能单位计数。
     */
    private static final long NANO_AIU_PER_USD = 10_000_000_000L;

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".copilot", "session-store.db");

    private static final String SQL_USAGE_EVENTS =
            "SELECT created_at, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, reasoning_tokens, "
                    + "total_nano_aiu, duration_ms, "
                    + "time_to_first_token_ms, finish_reason, session_id, id "
                    + "FROM assistant_usage_events "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    /**
     * 返回 SPI 名称（用于 VS Code Copilot）。
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
     * 解析 Copilot 命令行客户端会话存储中的全部用量记录。
     *
     * @return 每条计费请求对应的用量记录列表
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
     * 将一条 {@code assistant_usage_events} 记录转换为用量对象。
     *
     * @param rs 当前记录的结果集
     * @return 填充完整的用量记录
     * @throws SQLException 读取字段失败时抛出
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
        long duration = rs.getLong(9);
        long ttft = rs.getLong(10);
        String finishReason = rs.getString(11);
        String sessionId = rs.getString(12);
        long eventId = rs.getLong(13);

        BigDecimal costUsd = convertNanoAiuToUsd(nanoAiu);

        // reasoning_tokens 含在 output_tokens 之内：token_details_json 只有 input / cache_read /
        // cache_write / output 四类计价段，output 段计数等于 output_tokens，没有独立推理段。
        int reasoningHit = Math.max(0, Math.min(reasoning, outputTokens));
        int netOutput = outputTokens - reasoningHit;

        return AiUsage.builder()
                .provider("copilot")
                .model(model)
                .requestId(sessionId + "-" + eventId)
                .inputTokens(inputTokens)
                .outputTokens(netOutput)
                .totalTokens(inputTokens + netOutput)
                .reasoningTokens(reasoningHit > 0 ? reasoningHit : null)
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
     * @param nanoAiu GitHub 记录的纳诺人工智能单位计数
     * @return 美元金额，计数为零或负数时返回 null
     */
    private BigDecimal convertNanoAiuToUsd(long nanoAiu) {
        if (nanoAiu <= 0) {
            return null;
        }
        return BigDecimal.valueOf(nanoAiu).divide(BigDecimal.valueOf(NANO_AIU_PER_USD));
    }
}
