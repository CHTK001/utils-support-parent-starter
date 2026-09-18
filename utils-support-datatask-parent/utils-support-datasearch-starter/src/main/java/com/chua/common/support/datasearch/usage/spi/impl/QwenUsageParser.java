package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Qwen Code usage parser.
 *
 * <p>Qwen Code (Alibaba's Gemini-CLI fork) persists one usage statistics line
 * per session at {@code ~/.qwen/usage_record.jsonl}, with per-model token
 * breakdowns — unlike upstream Gemini CLI which stores nothing:</p>
 *
 * <pre>{@code
 * {
 *   "sessionId": "...",
 *   "timestamp": 1787702513366,
 *   "project": "D:\\ch\\project",
 *   "durationMs": 69510,
 *   "models": {
 *     "gemini-3.6-flash": {
 *       "requests": 4, "inputTokens": 31871, "outputTokens": 23,
 *       "cachedTokens": 0, "thoughtsTokens": 352, "totalTokens": 32246
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>每条会话行会按其中的模型逐条展开为 AiUsage 记录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qwen")
public class QwenUsageParser extends BaseUsageParser {

    private static final Path USAGE_FILE = Path.of(
            System.getProperty("user.home"), ".qwen", "usage_record.jsonl");

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "qwen"}
    */
    public String name() {
        return "qwen";
    }

    /**
    * 流式解析全部会话用量记录，按模型展开。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(USAGE_FILE)) {
            log.debug("[qwen] usage record not found: {} (Qwen Code not installed)", USAGE_FILE);
            return Flux.empty();
        }
        return Flux.using(
                        () -> Files.newBufferedReader(USAGE_FILE),
                        reader -> Flux.fromStream(reader.lines())
                                .map(this::parseLineSafe)
                                .flatMapIterable(l -> l),
                        reader -> {
                            try {
                                reader.close();
                            } catch (Exception ignored) {
                                // 忽略关闭异常
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("[qwen] read failed: {}", e.getMessage(), e);
                    return Flux.empty();
                });
    }

    /**
     * 解析LineSafe。
     *
     * @param line 方法入参 line
     * @return 结果列表，无数据时为空列表
     */
    private List<AiUsage> parseLineSafe(String line) {
        try {
            return parseLine(line);
        } catch (Exception e) {
            log.debug("[qwen] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析Line。
     *
     * @param line 方法入参 line
     * @return 结果列表，无数据时为空列表
     */
    private List<AiUsage> parseLine(String line) {
        if (line.isBlank()) {
            return List.of();
        }
        JsonNode node = Json.parse(line);
        JsonNode models = node.get("models");
        if (models.isMissingValue() || !models.isObject()) {
            return List.of();
        }
        long timestamp = node.get("timestamp").toLongValue(0L);
        long durationMs = node.get("durationMs").toLongValue(0L);
        String sessionId = node.get("sessionId").toStringValue();

        Map<String, Object> modelStats = models.toJsonObject().toMap();
        List<AiUsage> result = new ArrayList<>(modelStats.size());
        for (Map.Entry<String, Object> entry : modelStats.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> stats) {
                AiUsage usage = toAiUsage(entry.getKey(), stats, sessionId, timestamp, durationMs);
                if (usage != null) {
                    result.add(usage);
                }
            }
        }
        return result;
    }

    private AiUsage toAiUsage(String model, Map<?, ?> stats,
            String sessionId, long timestamp, long durationMs) {
        int inputTokens = asInt(stats.get("inputTokens"));
        int outputTokens = asInt(stats.get("outputTokens"));
        int cached = asInt(stats.get("cachedTokens"));
        int thoughts = asInt(stats.get("thoughtsTokens"));
        int requests = asInt(stats.get("requests"));

        if (inputTokens <= 0 && outputTokens <= 0) {
            return null;
        }

        return AiUsage.builder()
                .provider("qwen")
                .model(model)
                .requestId(sessionId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(asInt(stats.get("totalTokens")) > 0
                        ? asInt(stats.get("totalTokens")) : inputTokens + outputTokens)
                .cacheTokens(cached > 0 ? cached : null)
                .reasoningTokens(thoughts > 0 ? thoughts : null)
                .startTime(timestamp > 0 ? timestamp : null)
                .durationMillis(durationMs > 0 ? durationMs : null)
                .finishReason(requests + "-requests")
                .build();
    }
}
