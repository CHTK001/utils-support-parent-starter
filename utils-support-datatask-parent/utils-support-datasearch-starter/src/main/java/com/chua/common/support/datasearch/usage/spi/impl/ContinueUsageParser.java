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
   * 继续 usage parser.
 *
 * <p>Continue CLI stores one session JSON per run under
 * {@code ~/.continue/sessions/<uuid>.json}, containing real token usage
   * reported by the upstream 提供者:</p>
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
 * }</pre>WriteTokens": 0
 *     }
 *   },
   * "历史": [...]
 * }
 * }</pre>
 *
 * <p>Session start times come from the {@code sessions.json} index file,
   * which 映射 会话id 转为 a 创建 时间戳 入 轮次 milliseconds.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("continue")
public class ContinueUsageParser extends BaseUsageParser {

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".continue", "sessions");

    private static final String INDEX_FILE = "sessions.json"; // 索引文件

    /**
      * 返回 the SPI 名称 for 继续.
     *
     * @return {@code "continue"}
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
        return "continue";
    }

    /**
      * 解析 全部 继续 会话 文件 和 extracts 令牌 usage.
     *
     * @return list 的 aiusage records, one per 会话 with usage 数据
     */
    @Override protected List<AiUsage> parseAll() {
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
      * 加载 the 会话id 转为 创建-时间 mapping 从 the 索引 文件.
     *
     * @return map 的 会话标识 转为 轮次 milliseconds
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
      * 解析 a 单个 继续 会话 文件 into an AIusage record.
     *
     * @param file 路径 转为 the 会话 JSON 文件
     * @param dateIndex 会话标识 转为 创建 时间 mapping
     * @return the 解析 aiusage, 或 空 if the 文件 是否包含 no usage 数据
     * @param history 历史
     /**
      * 解析会话。
      * @param file 文件
      * @param dateIndex 日期索引
      * @return 解析会话的结果
      */
      * @param history 历史
     /**
      * 解析会话。
      * @param file 文件
      * @param dateIndex 日期索引
      * @return 解析会话的结果
      */
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
