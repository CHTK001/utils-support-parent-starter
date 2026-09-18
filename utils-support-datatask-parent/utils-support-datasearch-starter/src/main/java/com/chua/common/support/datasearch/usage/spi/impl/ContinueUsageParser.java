package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Continue usage parser.
 *
 * <p>Continue CLI stores one session JSON per run under
 * {@code ~/.continue/sessions/<uuid>.json}, containing real token usage
 * reported by the upstream provider:</p>
 *
 * <pre>{@code
 * {
 *   "sessionId": "82848f56-...",
 *   "usage": {
 *     "totalCost": 0.001199,
 *     "promptTokens": 1197,
 *     "completionTokens": 1,
 *     "promptTokensDetails": {
 *       "cachedTokens": 0,
 *       "cacheWriteTokens": 0
 *     }
 *   },
 *   "history": [...]
 * }
 * }</pre>
 *
 * <p>会话起始时间取自 {@code sessions.json} 索引文件，
 * 该文件把 sessionId 映射为以 epoch 毫秒表示的创建时间戳。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("continue")
public class ContinueUsageParser extends BaseUsageParser {

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".continue", "sessions");

    private static final String INDEX_FILE = "sessions.json";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "continue"}
    */
    @Override
    public String name() {
        return "continue";
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
    * 解析全部 Continue 会话文件并提取令牌用量。
    *
    * @return 用量记录列表，每个含 usage 数据的会话一条
    */
    @Override
    protected List<AiUsage> parseAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            log.debug("[continue] sessions dir not found: {}", SESSIONS_DIR);
            return List.of();
        }
        Map<String, Long> dateIndex = loadDateIndex();
        List<AiUsage> result = new ArrayList<>();
        try (var stream = Files.list(SESSIONS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !INDEX_FILE.equals(p.getFileName().toString()))
                    .forEach(file -> parseSession(file, dateIndex).ifPresent(result::add));
        } catch (IOException e) {
            log.warn("[continue] list failed: {}", e.getMessage(), e);
        }
        log.info("[continue] parsed {} session records", result.size());
        return result;
    }

    /**
    * 从索引文件加载 sessionId 到创建时间（epoch 毫秒）映射。
    *
    * @return sessionId 到创建时间的映射；文件缺失时返回空表
    */
    private Map<String, Long> loadDateIndex() {
        Map<String, Long> index = new HashMap<>();
        Path indexFile = SESSIONS_DIR.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return index;
        }
        try {
            JsonNode array = Json.parse(Files.readString(indexFile));
            int count = array.size();
            for (int i = 0; i < count; i++) {
                JsonNode entry = array.get(i);
                String sessionId = entry.get("sessionId").toStringValue();
                long created = entry.get("dateCreated").toLongValue(0L);
                if (!sessionId.isBlank() && created > 0) {
                    index.put(sessionId, created);
                }
            }
        } catch (Exception e) {
            log.debug("[continue] index parse failed: {}", e.getMessage());
        }
        return index;
    }

    /**
    * 解析单个 Continue 会话文件为一条 AiUsage 记录。
    *
    * @param file      会话 JSON 文件路径
    * @param dateIndex sessionId 到创建时间（epoch 毫秒）映射
    * @return 解析结果；无 usage 数据时为空
    */
    private java.util.Optional<AiUsage> parseSession(Path file, Map<String, Long> dateIndex) {
        try {
            JsonNode node = Json.parse(Files.readString(file));
            JsonNode usage = node.get("usage");
            if (usage.isMissingValue()) {
                return java.util.Optional.empty();
            }
            int inputTokens = usage.get("promptTokens").toIntValue(-1);
            int outputTokens = usage.get("completionTokens").toIntValue(-1);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return java.util.Optional.empty();
            }
            int cached = usage.get("promptTokensDetails").get("cachedTokens").toIntValue(0);
            double cost = usage.get("totalCost").toDoubleValue(0.0d);
            String sessionId = node.get("sessionId").toStringValue();
            String model = firstModel(node.get("history"));
            return java.util.Optional.of(AiUsage.builder()
                    .provider("continue")
                    .model(model)
                    .requestId(sessionId)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .cacheTokens(cached > 0 ? cached : null)
                    .totalCost(cost > 0 ? java.math.BigDecimal.valueOf(cost) : null)
                    .currency("USD")
                    .startTime(dateIndex.getOrDefault(sessionId, 0L) > 0
                            ? dateIndex.get(sessionId) : null)
                    .build());
        } catch (Exception e) {
            log.debug("[continue] parse failed {}: {}", file.getFileName(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
    * 从会话 history 中提取首条 assistant 消息的模型名。
    *
    * @param history history 数组节点
    * @return 模型名；无时返回 null
    */
    private String firstModel(JsonNode history) {
        if (history.isMissingValue() || !history.isArray()) {
            return null;
        }
        int count = history.size();
        for (int i = 0; i < count; i++) {
            JsonNode message = history.get(i).get("message");
            if (message.isMissingValue()) {
                continue;
            }
            JsonNode msgUsage = message.get("usage");
            if (msgUsage.isMissingValue()) {
                continue;
            }
            String model = msgUsage.get("model").toStringValue();
            if (!model.isBlank()) {
                return model;
            }
        }
        return null;
    }
}
