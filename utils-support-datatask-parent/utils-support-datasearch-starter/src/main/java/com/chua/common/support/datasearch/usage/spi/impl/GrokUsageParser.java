package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grok Build (xAI) usage parser.
 *
 * <p>Grok Build persists per-session append-only updates under
 * {@code ~/.grok/sessions/<encoded-cwd>/<session-id>/updates.jsonl}.
 * Each {@code turn_completed} update carries a real per-turn usage envelope:</p>
 *
 * <pre>{@code
 * {
 *   "params": {
 *     "update": {
 *       "sessionUpdate": "turn_completed",
 *       "usage": {
 *         "inputTokens": 4200,        // whole prompt, cache read/write INCLUDED
 *         "outputTokens": 300,       // includes reasoning
 *         "cachedReadTokens": 3900,
 *         "cacheCreationTokens": 120,
 *         "reasoningTokens": 90,
 *         "totalCostUsd": 0.0042
 *       },
 *       "model": "grok-4-fast"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>In Grok's camelCase shape {@code inputTokens} is inclusive of cache
 * read/write and {@code outputTokens} is inclusive of reasoning — this parser
 * splits both into mutually exclusive columns so downstream aggregation does
 * not double-count. Cost may arrive as {@code totalCostUsd} or as integer
 * ticks ({@code totalCostUsdTicks}, where 10_000_000_000 ticks = 1 USD).</p>
 *
 * <p>When an updates.jsonl has no turn usage, the sibling {@code signals.json}
 * carries a cumulative {@code totalTokens} watermark; a single estimated record
 * is emitted as fallback.</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("grok")
public class GrokUsageParser extends BaseUsageParser {

    /** USD ticks per US dollar (Grok's costUsdTicks unit). */
    private static final long USD_TICKS_PER_USD = 10_000_000_000L;

    private static final String PROVIDER_GROK = "grok";

    private static final Path GROK_HOME;

    static {
        String env = System.getenv("TOKENTRACKER_GROK_HOME");
        if (env == null || env.isBlank()) {
            env = System.getenv("GROK_HOME");
        }
        GROK_HOME = (env != null && !env.isBlank())
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".grok");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "grok"}
     */
    @Override
    public String name() {
        return PROVIDER_GROK;
    }

    /**
     * 流式解析全部会话更新文件中的回合用量事件。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listUpdateFiles();
        if (files.isEmpty()) {
            log.debug("[grok] no updates.jsonl under {}", GROK_HOME.resolve("sessions"));
            return Flux.empty();
        }
        log.info("[grok] streaming {} update files", files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::streamUpdatesFile, 4);
    }

    /**
     * 枚举 {@code ~/.grok/sessions/**/updates.jsonl} 文件。
     *
     * @return 更新文件列表
     */
    private List<Path> listUpdateFiles() {
        Path sessionsRoot = GROK_HOME.resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        try (var stream = Files.walk(sessionsRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("updates.jsonl"))
                    .toList();
        } catch (IOException e) {
            log.warn("[grok] walk {} failed: {}", sessionsRoot, e.getMessage());
            return List.of();
        }
    }

    /**
     * 流式解析单个更新文件（惰性逐行）。
     *
     * @param file 更新事件文件
     * @return 逐 turn 用量记录流
     */
    private Flux<AiUsage> streamUpdatesFile(Path file) {
        String sessionId = file.getParent().getFileName().toString();
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(line -> parseLineSafe(line, sessionId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .onErrorResume(e -> {
                    log.debug("[grok] read {} failed: {}", file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    private Optional<AiUsage> parseLineSafe(String line, String sessionId) {
        try {
            return parseLine(line, sessionId);
        } catch (Exception e) {
            log.debug("[grok] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析一条 updates.jsonl 行：仅接受 {@code params.update.sessionUpdate=turn_completed}
     * 且携带非空 usage 的回合事件。
     *
     * @param line JSONL 行
     * @param sessionId 会话 id
     * @return 用量记录
     */
    private Optional<AiUsage> parseLine(String line, String sessionId) {
        JsonNode node = Json.parse(line);
        JsonNode update = node.get("params").get("update");
        if (update.isMissingValue()) {
            update = node.get("update");
        }
        if (update.isMissingValue()
                || !"turn_completed".equals(update.get("sessionUpdate").toStringValue())) {
            return Optional.empty();
        }
        JsonNode usage = update.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        // Grok camelCase：inputTokens 含缓存、outputTokens 含 reasoning —— 拆分后存储
        boolean camelShape = !usage.get("inputTokens").isMissingValue();
        long rawInput = usage.get("inputTokens").toLongValue(
                usage.get("input_tokens").toLongValue(0L));
        long cachedRead = usage.get("cachedReadTokens").toLongValue(
                usage.get("cacheReadInputTokens").toLongValue(
                        usage.get("cache_read_input_tokens").toLongValue(0L)));
        long cacheWrite = usage.get("cacheCreationTokens").toLongValue(
                usage.get("cachedWriteTokens").toLongValue(
                        usage.get("cacheWriteInputTokens").toLongValue(
                                usage.get("cache_creation_input_tokens").toLongValue(0L))));
        long rawOutput = usage.get("outputTokens").toLongValue(
                usage.get("output_tokens").toLongValue(0L));
        long reasoning = usage.get("reasoningTokens").toLongValue(
                usage.get("reasoning_output_tokens").toLongValue(0L));
        long totalReported = usage.get("totalTokens").toLongValue(
                usage.get("total_tokens").toLongValue(0L));

        long inputTokens = camelShape
                ? Math.max(0L, rawInput - cachedRead - cacheWrite)
                : rawInput;
        long outputTokens = Math.max(0L, rawOutput - reasoning);
        long totalTokens = totalReported > 0
                ? totalReported
                : inputTokens + cachedRead + cacheWrite + outputTokens + reasoning;
        if (totalTokens <= 0) {
            return Optional.empty();
        }

        BigDecimal costUsd = grokCostUsd(usage);
        String model = firstNonBlank(
                update.get("model").toStringValue(),
                pickGrokModel(usage),
                PROVIDER_GROK + "-unknown");

        return Optional.of(AiUsage.builder()
                .provider(PROVIDER_GROK)
                .model(model)
                .requestId(firstNonBlank(node.get("promptId").toStringValue(), sessionId))
                .inputTokens(intOf(inputTokens))
                .outputTokens(intOf(outputTokens))
                .totalTokens(intOf(totalTokens))
                .cacheTokens(intOf(Math.max(cachedRead, cacheWrite)))
                .reasoningTokens(reasoning > 0 ? intOf(reasoning) : null)
                .totalCost(costUsd)
                .currency("USD")
                .startTime(timestampMillis(node))
                .build());
    }

    /**
     * 提取 Grok 成本：支持 USD 直接值与 ticks 整数（10^10 ticks = 1 USD）。
     *
     * @param usage usage 节点
     * @return USD 金额；缺失或负数时返回 null
     */
    private BigDecimal grokCostUsd(JsonNode usage) {
        long ticks = usage.get("costUsdTicks").toLongValue(
                usage.get("totalCostUsdTicks").toLongValue(
                        usage.get("cost_usd_ticks").toLongValue(
                                usage.get("total_cost_usd_ticks").toLongValue(0L))));
        if (ticks > 0) {
            return BigDecimal.valueOf(ticks).divide(BigDecimal.valueOf(USD_TICKS_PER_USD));
        }
        double usd = usage.get("totalCostUsd").toDoubleValue(
                usage.get("costUsd").toDoubleValue(
                        usage.get("total_cost_usd").toDoubleValue(
                                usage.get("cost_usd").toDoubleValue(0.0d))));
        return usd > 0 ? BigDecimal.valueOf(usd) : null;
    }

    /**
     * 行内时间戳（兼容 ISO 与 epoch 秒/毫秒）。
     *
     * @param node 行节点
     * @return epoch 毫秒；无法解析时返回 0
     */
    private long timestampMillis(JsonNode node) {
        JsonNode meta = node.get("params").get("_meta");
        long agentMs = meta.get("agentTimestampMs").toLongValue(0L);
        if (agentMs > 0) {
            return agentMs;
        }
        long ts = node.get("timestamp").toLongValue(0L);
        if (ts > 0) {
            return ts < 10_000_000_000L ? ts * 1000L : ts;
        }
        return parseInstantToMillis(node.get("timestamp").toStringValue());
    }

    /**
     * 从 usage.modelUsage 选出令牌量最大的模型名。
     *
     * @param usage usage 节点
     * @return 模型名；无则空串
     */
    private String pickGrokModel(JsonNode usage) {
        JsonNode modelUsage = usage.get("modelUsage");
        if (modelUsage.isMissingValue() || !modelUsage.isObject()) {
            return "";
        }
        String best = "";
        long bestTokens = -1L;
        for (Map.Entry<String, Object> entry : modelUsage.toJsonObject().toMap().entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> stats)) {
                continue;
            }
            long tokens = asInt(stats.get("totalTokens"))
                    + asInt(stats.get("inputTokens"))
                    + asInt(stats.get("outputTokens"));
            if (tokens >= bestTokens) {
                bestTokens = tokens;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static int intOf(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
