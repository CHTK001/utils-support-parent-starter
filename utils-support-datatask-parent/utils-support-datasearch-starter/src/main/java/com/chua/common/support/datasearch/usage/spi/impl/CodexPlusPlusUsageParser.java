package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Codex++ 用量解析器 — 从本地 sqlite 数据库解析会话用量。
 *
 * <p>数据源为 {@code %USERPROFILE%\.codex\state_5.sqlite} 中的 {@code threads} 表。
 * 该表按会话（thread）聚合 令牌_used，但不区分输入/输出缓存粒度。</p>
 *
 * <p>拆细口径在同库记的 {@code rollout_path} 指向的转录里：每个
 * {@code payload.type=token_count} 事件带 {@code info.total_token_usage}
 * （会话累计，其 total_tokens 与 {@code tokens_used} 一致，其中 input 含缓存），
 * {@code payload.type=task_complete} 事件带本轮的 {@code duration_ms} 与
 * {@code time_to_first_token_ms}。因此读一次转录即可补齐输入/输出/缓存/推理
 * 拆分与耗时；转录缺失或读不动时退回只带总量的原始行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codex++")
public class CodexPlusPlusUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".codex", "state_5.sqlite");

    /**
     * 会话行查询：附带转录路径，供进一步拆细 Token 口径与取耗时
     */
    private static final String SQL_THREADS =
            "SELECT id, rollout_path, created_at_ms, model, model_provider, tokens_used FROM threads "
                    + "WHERE tokens_used > 0 ORDER BY created_at_ms ASC";

    /**
     * 转录里的结束标志事件名
     */
    private static final String EVENT_TASK_COMPLETE = "task_complete";

    /**
     * 响应式流式入口：通过 sqlitereactorengine 流出会话记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[codex++] 数据库文件不存在: {}", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("codex", DB_PATH.toString());
        return engine.query(SQL_THREADS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[codex++] 流式解析完成"));
    }

    /**
     * 返回 SPI 名称。
     *
     * @return 提供者名称
     */
    @Override
    public String name() {
        return "codex++";
    }

    /**
     * 将会话行转为用量记录，并用其转录补齐拆分字段与耗时。
     *
     * @param row 数据库行
     * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        long createdAt = asLong(row.get("created_at_ms"));
        AiUsage usage = AiUsage.builder()
                .provider(asStr(row.get("model_provider")))
                .model(asStr(row.get("model")))
                .requestId(asStr(row.get("id")))
                .totalTokens(asInt(row.get("tokens_used")))
                .currency("USD")
                .startTime(createdAt > 0 ? createdAt : null)
                .build();
        enrichFromRollout(usage, asStr(row.get("rollout_path")));
        return usage;
    }

    /**
     * 读取会话转录，把累计用量拆分与本轮耗时写回记录。
     *
     * @param usage      待补全记录
     * @param rolloutPath 转录文件路径，缺失时跳过
     */
    private void enrichFromRollout(AiUsage usage, String rolloutPath) {
        if (rolloutPath.isBlank()) {
            return;
        }
        Path file = Path.of(rolloutPath);
        if (!Files.isRegularFile(file)) {
            log.debug("[codex++] 转录文件不存在: {}", file);
            return;
        }
        JsonNode totals = null;
        long duration = 0L;
        long firstToken = 0L;
        boolean completed = false;
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : (Iterable<String>) lines::iterator) {
                JsonNode payload = readPayload(line);
                if (payload == null) {
                    continue;
                }
                String type = payload.get("type").toStringValue();
                if ("token_count".equals(type)) {
                    JsonNode total = payload.get("info").get("total_token_usage");
                    if (!total.isMissingValue()) {
                        totals = total;
                    }
                } else if (EVENT_TASK_COMPLETE.equals(type)) {
                    completed = true;
                    duration += payload.get("duration_ms").toLongValue(0L);
                    if (firstToken <= 0L) {
                        firstToken = payload.get("time_to_first_token_ms").toLongValue(0L);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[codex++] 转录读取失败 {}: {}", file.getFileName(), e.getMessage());
            return;
        }
        applyTotals(usage, totals);
        if (duration > 0L) {
            usage.setDurationMillis(duration);
        }
        if (firstToken > 0L) {
            usage.setFirstTokenLatencyMillis(firstToken);
        }
        if (completed) {
            usage.setFinishReason(EVENT_TASK_COMPLETE);
        }
    }

    /**
     * 用转录里的累计用量拆分输入/输出/缓存/推理令牌。
     *
     * <p>{@code input_tokens} 含缓存命中部分，按契约原样出数，命中量另记于
     * {@code cacheTokens}；{@code cache_creation} 是写入量、不含在 {@code input_tokens}
     * 之内，并入全量输入以免漏计。口径与 Codex 系其它解析器一致。</p>
     *
     * <p>{@code output_tokens} 含推理量，按契约净出后只留可见输出，推理量单记于
     * {@code reasoningTokens}，{@code totalTokens} 随之回算为 全量输入 + 净输出。</p>
     *
     * @param usage 待补全记录
     * @param totals 累计用量节点，可为 空
     */
    private void applyTotals(AiUsage usage, JsonNode totals) {
        if (totals == null) {
            return;
        }
        int input = totals.get("input_tokens").toIntValue(0);
        int cached = Math.max(0, Math.min(totals.get("cached_input_tokens").toIntValue(0), input));
        int cacheWrite = Math.max(totals.get("cache_creation_input_tokens").toIntValue(0),
                totals.get("cache_write_input_tokens").toIntValue(0));
        int completion = totals.get("output_tokens").toIntValue(0);
        // reasoning_output_tokens 是 output_tokens 的子集，按契约净出后单列。
        int reasoning = Math.min(Math.max(0, totals.get("reasoning_output_tokens").toIntValue(0)),
                Math.max(0, completion));
        int output = Math.max(0, completion - reasoning);
        if (input > 0) {
            usage.setInputTokens(input + Math.max(0, cacheWrite));
        }
        if (output > 0) {
            usage.setOutputTokens(output);
        }
        if (cached > 0) {
            usage.setCacheTokens(cached);
        }
        if (reasoning > 0) {
            usage.setReasoningTokens(reasoning);
        }
        // 库里的 tokens_used 含推理量，净出之后只有回算才满足 totalTokens = 输入 + 输出。
        if (input > 0 && completion > 0) {
            usage.setTotalTokens(Integer.valueOf(input + Math.max(0, cacheWrite) + output));
        }
    }

    /**
     * 取转录行的 payload 节点。
     *
     * @param line 转录单行
     * @return payload 节点；非事件行或解析失败时返回 空
     */
    private JsonNode readPayload(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode payload = Json.parse(line).get("payload");
            return payload.isMissingValue() ? null : payload;
        } catch (Exception e) {
            log.debug("[codex++] 行解析失败: {}", e.getMessage());
            return null;
        }
    }
}
