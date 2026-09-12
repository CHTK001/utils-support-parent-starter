package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * GitHub Copilot CLI usage parser.
 *
 * <p>GitHub Copilot CLI persists per-session event transcripts under
 * {@code ~/.copilot/session-state/<session-id>/events.jsonl}. The
 * {@code session.shutdown} event carries an authoritative per-model usage
 * snapshot ({@code modelMetrics}) plus session cost in nano-AIU
 * (1 nano-AIU = 1e-10 USD):</p>
 *
 * <pre>{@code
 * {
 *   "type": "session.shutdown",
 *   "data": {
 *     "totalNanoAiu": 386345000,
 *     "totalApiDurationMs": 30285,
 *     "modelMetrics": {
 *       "gpt-5-mini": {
 *         "usage": { "inputTokens": 19849, "outputTokens": 113,
 *                    "cacheReadTokens": 5888, "cacheWriteTokens": 0,
 *                    "reasoningTokens": 64 },
 *         "totalNanoAiu": 386345000
 *       }
 *     }
 *   },
 *   "timestamp": "2026-08-24T05:30:35.800Z"
 * }
 * }</pre>
 *
 * <p>Token semantics: {@code inputTokens} is cache-inclusive (the raw
 * {@code input} field of {@code tokenDetails} excludes cache while
 * {@code usage.inputTokens} does not), so the reported input is kept as-is
 * and cache read/write/reasoning are surfaced separately.</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("copilot-cli")
public class CopilotCliUsageParser extends BaseUsageParser {

    private static final Path SESSION_STATE_DIR = Path.of(
            System.getProperty("user.home"), ".copilot", "session-state");

    private static final String PROVIDER_COPILOT_CLI = "copilot-cli";

    /** nano-AIU → USD: 10_000_000_000 ticks per dollar. */
    private static final BigDecimal NANO_AIU_PER_USD =
            BigDecimal.valueOf(10_000_000_000L);

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "copilot-cli"}
     */
    @Override
    public String name() {
        return PROVIDER_COPILOT_CLI;
    }

    /**
     * 响应式流式入口：惰性扫描各会话的 events.jsonl，仅提取
     * {@code session.shutdown} 事件的逐模型用量。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listEventFiles();
        if (files.isEmpty()) {
            log.debug("[copilot-cli] no session events under {}", SESSION_STATE_DIR);
            return Flux.empty();
        }
        log.info("[copilot-cli] scanning {} session event files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamEventFile, 4)
                .onErrorResume(e -> {
                    log.debug("[copilot-cli] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 枚举 {@code ~/.copilot/session-state/&#42;/events.jsonl}。
     *
     * @return 事件文件列表
     */
    private List<Path> listEventFiles() {
        if (!Files.isDirectory(SESSION_STATE_DIR)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(SESSION_STATE_DIR)) {
            stream.filter(Files::isDirectory)
                    .map(d -> d.resolve("events.jsonl"))
                    .filter(Files::isRegularFile)
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[copilot-cli] list session-state failed: {}", e.getMessage());
        }
        return files;
    }

    /**
     * 解析单个 events.jsonl，仅处理携带 modelMetrics 的 session.shutdown 事件。
     *
     * @param file 事件文件
     * @return 用量记录流
     */
    private Flux<AiUsage> streamEventFile(Path file) {
        return streamLines(file)
                .map(line -> parseLineSafe(line, file))
                .filter(r -> !r.isEmpty())
                .flatMapIterable(r -> r);
    }

    /**
     * 解析单行；session.shutdown 事件产出 per-model 用量记录。
     *
     * @param line 单行 JSON
     * @param file 来源事件文件（作为 requestId 前缀）
     * @return 用量记录列表（多模型会话逐模型各一条）
     */
    private List<AiUsage> parseLineSafe(String line, Path file) {
        if (line.isBlank()) {
            return List.of();
        }
        try {
            JsonNode obj = Json.parse(line);
            if (!"session.shutdown".equals(obj.get("type").toStringValue())) {
                return List.of();
            }
            JsonNode data = obj.get("data");
            if (data.isMissingValue()) {
                return List.of();
            }
            JsonNode modelMetrics = data.get("modelMetrics");
            if (modelMetrics.isMissingValue() || !modelMetrics.isObject()) {
                return List.of();
            }
            long totalNanoAiu = data.get("totalNanoAiu").toLongValue(0L);
            long totalApiDurationMs = data.get("totalApiDurationMs").toLongValue(0L);
            long startTime = parseInstantToMillis(obj.get("timestamp").toStringValue());

            List<AiUsage> result = new ArrayList<>();
            Map<String, Object> metricMap = modelMetrics.toJsonObject().toMap();
            for (Map.Entry<String, Object> entry : metricMap.entrySet()) {
                String modelId = entry.getKey();
                if (!(entry.getValue() instanceof Map<?, ?> metricsMap)) {
                    continue;
                }
                Map<String, Object> metrics = new java.util.HashMap<>();
                metricsMap.forEach((k, v) -> {
                    if (k instanceof String s) {
                        metrics.put(s, v);
                    }
                });
                AiUsage record = toAiUsage(modelId, metrics,
                        totalNanoAiu, totalApiDurationMs, startTime,
                        file.getFileName().toString());
                if (record != null) {
                    result.add(record);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[copilot-cli] line parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将单模型指标转换为 AiUsage；无用量时返回 null。
     *
     * @param modelId 模型名
     * @param metrics modelMetrics 条目（已转为扁平 Map）
     * @param totalNanoAiu 会话级 nano-AIU（模型级缺失时兜底）
     * @param totalApiDurationMs 会话级 API 耗时
     * @param startTime 事件时间戳（毫秒）
     * @param requestId 会话级请求 id
     * @return 用量记录或 null
     */
    private AiUsage toAiUsage(String modelId, Map<String, Object> metrics,
                              long totalNanoAiu, long totalApiDurationMs,
                              long startTime, String requestId) {
        Object usageObj = metrics.get("usage");
        int inputTokens = 0;
        int outputTokens = 0;
        int cacheRead = 0;
        int cacheWrite = 0;
        int reasoning = 0;
        if (usageObj instanceof Map<?, ?> usage) {
            inputTokens = asInt(usage.get("inputTokens"));
            outputTokens = asInt(usage.get("outputTokens"));
            cacheRead = asInt(usage.get("cacheReadTokens"));
            cacheWrite = asInt(usage.get("cacheWriteTokens"));
            reasoning = asInt(usage.get("reasoningTokens"));
        }
        if (inputTokens <= 0 && outputTokens <= 0 && cacheRead <= 0) {
            return null;
        }

        long modelNanoAiu = asLong(metrics.get("totalNanoAiu"));
        if (modelNanoAiu <= 0) {
            modelNanoAiu = totalNanoAiu;
        }
        BigDecimal costUsd = modelNanoAiu > 0
                ? BigDecimal.valueOf(modelNanoAiu)
                        .divide(NANO_AIU_PER_USD, 10, java.math.RoundingMode.HALF_UP)
                : null;

        int cacheTokens = Math.max(cacheRead, cacheWrite);

        return AiUsage.builder()
                .provider(PROVIDER_COPILOT_CLI)
                .model(modelId)
                .requestId(requestId + ":" + modelId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheTokens > 0 ? cacheTokens : null)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .totalCost(costUsd)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(totalApiDurationMs > 0 ? totalApiDurationMs : null)
                .build();
    }
}
