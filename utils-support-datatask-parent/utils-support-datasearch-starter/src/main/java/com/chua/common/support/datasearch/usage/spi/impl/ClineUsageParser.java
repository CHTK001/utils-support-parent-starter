package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cline 用量解析器。
 *
 * <p>Cline CLI 在 {@code ~/.cline/data/sessions/<id>/} 下同时写两份文件：</p>
 *
 * <ul>
 *   <li>{@code <id>.json} —— 会话级摘要，{@code metadata.usage} 只有一份聚合值；</li>
 *   <li>{@code <id>.messages.json} —— 逐条消息，其中每条 {@code role=assistant} 行都带
 *       本次请求的 {@code metrics} 与 {@code modelInfo}，是真正的按请求账单。</li>
 * </ul>
 *
 * <pre>{@code
 * {
 *   "messages": [
 *     { "role": "user", "ts": 1788920960810 },
 *     {
 *       "id": "msg_v8odd3pj",
 *       "role": "assistant",
 *       "ts": 1788920972799,
 *       "modelInfo": { "id": "deepseek/deepseek-v4-flash", "provider": "cline-pass" },
 *       "metrics": {
 *         "inputTokens": 5801, "outputTokens": 189,
 *         "cacheReadTokens": 256, "cacheWriteTokens": 0, "cost": 0.0041
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>本机实测 33 个 messages 文件共 1430 条带 {@code metrics} 的助手行、输入合计
 * 1.565 亿令牌，而会话摘要只有 13 份且其 {@code metadata.usage} 明显小于同目录逐请求之和
 * （例：摘要 502145 对逐请求 1281308），故一律以逐请求行为准；只有某个会话目录没有可用的
 * {@code *.messages.json} 时，才退回会话摘要出那一条，避免同一会话重复计账。
 * 另有 3 个目录只有 messages 文件、没有会话摘要，过去被整个漏掉。</p>
 *
 * <p>{@code cacheReadTokens/inputTokens} 中位数 0.984、p90 0.998，命中量本就含在
 * {@code inputTokens} 之内，按契约原样出数、命中量单列于 {@code cacheTokens} 供补全器折算；
 * {@code cost} 仅 4/1430 行有值，其余留给定价目录补。转录不记结束原因，
 * {@code finishReason} 留空；{@code ts} 是该条回复的落盘时刻，耗时由前一条用户行的
 * {@code ts} 折算（本机中位 11989ms），{@code startTime} 回退到该起点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cline")
public class ClineUsageParser extends BaseUsageParser {

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".cline", "data", "sessions");

    private static final String PROVIDER_CLINE = "cline";

    private static final String UNKNOWN_MODEL = "unknown";

    private static final String MESSAGES_SUFFIX = ".messages.json";

    /**
     * 返回 Cline 的 SPI 名称。
     *
     * @return {@code "cline"}
     */
    @Override
    public String name() {
        return "cline";
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
     * 解析全部 Cline 会话：逐请求文件优先，会话摘要仅在没有逐请求数据时兜底。
     *
     * @return AiUsage 记录列表，一条模型请求对应一条
     */
    @Override protected List<AiUsage> parseAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            log.debug("[cline] sessions dir not found: {}", SESSIONS_DIR);
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.walk(SESSIONS_DIR)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
        } catch (IOException e) {
            log.warn("[cline] walk failed: {}", e.getMessage(), e);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        Set<Path> detailed = new HashSet<>();
        for (Path file : files) {
            if (!isMessagesFile(file)) {
                continue;
            }
            int before = result.size();
            parseMessages(file, result);
            if (result.size() > before) {
                detailed.add(file.getParent());
            }
        }
        int perRequest = result.size();
        for (Path file : files) {
            if (isMessagesFile(file) || detailed.contains(file.getParent())) {
                continue;
            }
            parseSession(file).ifPresent(result::add);
        }
        log.info("[cline] parsed {} records（逐请求 {} 条，会话摘要兜底 {} 条）",
                result.size(), perRequest, result.size() - perRequest);
        return result;
    }

    /**
     * 该文件是否是逐请求消息文件。
     *
     * @param file 待判断文件
     * @return 是逐请求文件返回 true
     */
    private static boolean isMessagesFile(Path file) {
        return file.getFileName().toString().endsWith(MESSAGES_SUFFIX);
    }

    /**
     * 读取一个逐请求文件，把每条带 {@code metrics} 的助手行转成一条用量记录。
     *
     * @param file   逐请求消息文件
     * @param result 累加的用量记录列表
     */
    private void parseMessages(Path file, List<AiUsage> result) {
        JsonNode messages;
        try {
            messages = Json.parse(Files.readString(file)).get("messages");
        } catch (Exception e) {
            log.debug("[cline] read failed {}: {}", file.getFileName(), e.getMessage());
            return;
        }
        if (messages.isMissingValue() || !messages.isArray()) {
            return;
        }
        String sessionKey = file.getFileName().toString().replace(MESSAGES_SUFFIX, "");
        long lastUserMillis = 0L;
        int count = messages.size();
        for (int i = 0; i < count; i++) {
            JsonNode row = messages.get(i);
            long rowMillis = row.get("ts").toLongValue(0L);
            String role = row.get("role").toStringValue("");
            if (!"assistant".equals(role)) {
                if ("user".equals(role) && rowMillis > 0) {
                    lastUserMillis = rowMillis;
                }
                continue;
            }
            JsonNode metrics = row.get("metrics");
            if (metrics.isMissingValue()) {
                continue;
            }
            int inputTokens = metrics.get("inputTokens").toIntValue(-1);
            int outputTokens = metrics.get("outputTokens").toIntValue(-1);
            if (inputTokens <= 0 && outputTokens <= 0) {
                continue;
            }
            // 实测中位数比值 0.984：命中量含在 inputTokens 之内，原样出数并单列命中量。
            int cacheRead = Math.max(0, metrics.get("cacheReadTokens").toIntValue(0));
            double cost = metrics.get("cost").toDoubleValue(0.0d);
            Long duration = lastUserMillis > 0 && rowMillis > lastUserMillis
                    ? Long.valueOf(rowMillis - lastUserMillis) : null;
            long startTime = startTimeOf(rowMillis, duration);
            JsonNode modelInfo = row.get("modelInfo");
            result.add(AiUsage.builder()
                    .provider(firstNonBlank(modelInfo.get("provider").toStringValue(), PROVIDER_CLINE))
                    .model(firstNonBlank(modelInfo.get("id").toStringValue(), UNKNOWN_MODEL))
                    .requestId(firstNonBlank(row.get("id").toStringValue(), sessionKey + "-" + i))
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead) : null)
                    .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                    .currency(cost > 0 ? "USD" : null)
                    .startTime(startTime > 0 ? Long.valueOf(startTime) : null)
                    .durationMillis(duration)
                    .build());
        }
    }

    /**
     * 将单个 Cline 会话摘要文件解析为一条 AiUsage 记录（仅在无逐请求数据时使用）。
     *
     * @param file 会话 JSON 文件路径
     * @return 解析得到的 AiUsage；文件没有用量数据时返回空
     */
    private Optional<AiUsage> parseSession(Path file) {
        try {
            JsonNode node = Json.parse(Files.readString(file));
            JsonNode usage = node.get("metadata").get("usage");
            if (usage.isMissingValue()) {
                return Optional.empty();
            }
            int inputTokens = usage.get("inputTokens").toIntValue(-1);
            int outputTokens = usage.get("outputTokens").toIntValue(-1);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return Optional.empty();
            }
            int cacheRead = usage.get("cacheReadTokens").toIntValue(0);
            double cost = usage.get("totalCost").toDoubleValue(0.0d);
            long startTime = parseInstantToMillis(node.get("started_at").toStringValue());
            long endTime = parseInstantToMillis(node.get("ended_at").toStringValue());
            return Optional.of(AiUsage.builder()
                    .provider(firstNonBlank(node.get("provider").toStringValue(), PROVIDER_CLINE))
                    .model(node.get("model").toStringValue())
                    .requestId(node.get("session_id").toStringValue())
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .cacheTokens(cacheRead > 0 ? cacheRead : null)
                    .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                    .currency(cost > 0 ? "USD" : null)
                    .startTime(startTime > 0 ? startTime : null)
                    .durationMillis(startTime > 0 && endTime > startTime ? endTime - startTime : null)
                    .finishReason(node.get("status").toStringValue())
                    .build());
        } catch (Exception e) {
            log.debug("[cline] parse failed {}: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }
}
