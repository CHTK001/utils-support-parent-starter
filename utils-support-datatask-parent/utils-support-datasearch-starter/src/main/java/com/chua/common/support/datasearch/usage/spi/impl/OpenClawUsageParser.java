package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * OpenClaw usage parser.
 *
 * <p>OpenClaw records each agent run as a trajectory JSONL under
 * {@code ~/.openclaw/agents/<agent>/sessions/<id>.trajectory.jsonl}.
 * 每个 {@code model.completed} 轨迹事件除了写出本轮累计用量，还带上一份
 * {@code messagesSnapshot}，收录本次会话已经产生的全部模型响应，因此一次
 * API 调用对应快照里的一条 assistant 消息：</p>
 *
 * <pre>{@code
 * {
 *   "type": "model.completed",
 *   "ts": "2026-07-04T07:16:40.531Z",
 *   "sessionId": "7284d6f1-...",
 *   "modelId": "deepseek-v4-flash",
 *   "data": {
 *     "usage": { "input": 47302, "output": 714, "cacheRead": 56832, "total": 104848 },
 *     "messagesSnapshot": [
 *       {
 *         "role": "assistant",
 *         "model": "agnes-2.0-flash",
 *         "stopReason": "toolUse",
 *         "timestamp": 1784858335197,
 *         "responseId": "5cc3c436-d935-4afe-8ba5-5887a61ea6ef",
 *         "usage": {
 *           "input": 18009, "output": 65, "cacheRead": 6400, "cacheWrite": 0,
 *           "reasoningTokens": 0, "totalTokens": 24474,
 *           "cost": { "input": 0, "output": 0, "total": 0 }
 *         }
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>事件上的 {@code data.usage} 是本轮（run）内所有调用的累计值，且同一个累计值会在
 * 本轮每个事件上重复写出，直接按事件取用会把 Token 数放大数倍，因此本解析器只认快照里
 * 的 assistant 消息：一条消息一条记录，按 {@code responseId} 跨事件去重。</p>
 *
 * <p>转录不给出请求耗时：同一次交互里工具结果与紧随其后的 assistant 消息时间戳仅相差
 * 数十毫秒，无法解释为网络往返，故 {@code durationMillis} 保持为空。</p>
 *
 * <p>快照里的 {@code input}/{@code cacheRead}/{@code cacheWrite}/{@code output} 是互斥段，
 * 四段之和即 {@code totalTokens}（如 {@code 18009 + 6400 + 0 + 65 = 24474}）。由于本仓库
 * 约定 {@code inputTokens} 含缓存命中，产出前把两段缓存并回输入，命中量单独落在
 * {@code cacheTokens}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("openclaw")
public class OpenClawUsageParser extends BaseUsageParser {

    private static final Path OPENCLAW_DIR = Path.of(
            System.getProperty("user.home"), ".openclaw");

    private static final String PROVIDER_OPENCLAW = "openclaw";

    /**
     * 返回 OpenClaw 的 SPI 名称。
     *
     * @return {@code "openclaw"}
     */
    @Override
    public String name() {
        return "openclaw";
    }

    /**
     * 从所有转录文件中以流式方式输出按单次调用粒度的用量记录。
     *
     * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTrajectoryFiles();
        if (files.isEmpty()) {
            log.debug("[openclaw] no trajectory files under {}", OPENCLAW_DIR);
            return Flux.empty();
        }
        log.info("[openclaw] streaming from {} trajectory files", files.size());
        return Flux.fromIterable(files)
                .concatMap(this::streamCalls);
    }

    /**
     * 列出全部轨迹文件。
     *
     * @return 轨迹文件列表；目录缺失时为空列表
     */
    private List<Path> listTrajectoryFiles() {
        Path agentsDir = OPENCLAW_DIR.resolve("agents");
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var agents = Files.list(agentsDir)) {
            for (Path agent : (Iterable<Path>) agents::iterator) {
                Path sessions = agent.resolve("sessions");
                if (!Files.isDirectory(sessions)) {
                    continue;
                }
                try (var stream = Files.list(sessions)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".trajectory.jsonl"))
                            .forEach(files::add);
                }
            }
        } catch (IOException e) {
            log.warn("[openclaw] scan failed: {}", e.getMessage(), e);
        }
        return files;
    }

    /**
     * 单个转录文件的调用流：先把快照里的同源调用去重收敛，再逐条下发。
     *
     * @param file 转录文件
     * @return 用量记录流
     */
    private Flux<AiUsage> streamCalls(Path file) {
        return Flux.defer(() -> Flux.fromIterable(collectCalls(file)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 汇总一个文件内全部单次调用的用量，后到的快照覆盖先到的记录。
     *
     * @param file 转录文件
     * @return 去重后的用量记录列表
     */
    private List<AiUsage> collectCalls(Path file) {
        Map<String, AiUsage> calls = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> absorbCalls(line, calls));
        } catch (IOException | UncheckedIOException e) {
            log.debug("[openclaw] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return new ArrayList<>(calls.values());
    }

    /**
     * 从一行轨迹事件里取出快照中的 assistant 调用用量。
     *
     * @param line  单行 JSON
     * @param calls 以调用标识为键的累积结果
     */
    private void absorbCalls(String line, Map<String, AiUsage> calls) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonNode node = Json.parse(line);
            if (!"model.completed".equals(node.get("type").toStringValue())) {
                return;
            }
            JsonNode data = readContainer(node);
            if (data.get("usage").isMissingValue()) {
                return;
            }
            JsonNode snapshot = data.get("messagesSnapshot");
            if (!snapshot.isArray()) {
                return;
            }
            String eventModel = node.get("modelId").toStringValue();
            int size = snapshot.size();
            for (int i = 0; i < size; i++) {
                JsonNode message = snapshot.get(i);
                if (!"assistant".equals(message.get("role").toStringValue())) {
                    continue;
                }
                AiUsage usage = toCall(message, eventModel);
                if (usage != null) {
                    calls.put(callKey(message, i), usage);
                }
            }
        } catch (Exception e) {
            log.debug("[openclaw] parse failed: {}", e.getMessage());
        }
    }

    /**
     * 定位 model.completed 事件的承载节点。
     *
     * <p>OpenClaw 把用量与结束标志放在 {@code data} 下；较早的 schema 可能把它们
     * 放在行顶层，因此两个位置都要检查，且后续字段一律从返回的同一节点读取。</p>
     *
     * @param node 已解析的转录行
     * @return 承载 {@code usage} 的节点；不存在时返回行本身
     */
    private JsonNode readContainer(JsonNode node) {
        JsonNode data = node.get("data");
        return data.isMissingValue() || data.get("usage").isMissingValue() ? node : data;
    }

    /**
     * 单次调用的去重键，优先用上游响应号。
     *
     * @param message 快照里的 assistant 消息
     * @param index   消息在快照中的下标，兜底用
     * @return 去重键
     */
    private static String callKey(JsonNode message, int index) {
        String responseId = message.get("responseId").toStringValue();
        if (responseId != null && !responseId.isBlank()) {
            return responseId;
        }
        long timestamp = message.get("timestamp").toLongValue(0L);
        return timestamp > 0 ? Long.toString(timestamp) : "index-" + index;
    }

    /**
     * 把快照里的一条 assistant 消息转为单次调用的用量记录。
     *
     * @param message     快照消息
     * @param fallbackModel 事件级模型名，消息未带模型时使用
     * @return 用量记录；调用无令牌时返回 null
     */
    private AiUsage toCall(JsonNode message, String fallbackModel) {
        JsonNode usage = message.get("usage");
        if (usage.isMissingValue()) {
            return null;
        }
        int inputTokens = usage.get("input").toIntValue(0);
        int outputTokens = usage.get("output").toIntValue(0);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return null;
        }
        // openclaw 把 input / cacheRead / cacheWrite / output 拆成互斥段，段和即 totalTokens，
        // 因此输入要先把缓存两段加回，命中量单独上报。
        int cacheRead = Math.max(0, usage.get("cacheRead").toIntValue(0));
        int promptTokens = Math.max(0, inputTokens) + cacheRead
                + Math.max(0, usage.get("cacheWrite").toIntValue(0));
        long timestamp = message.get("timestamp").toLongValue(0L);
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_OPENCLAW)
                .model(firstNonBlank(message.get("model").toStringValue(),
                        firstNonBlank(fallbackModel, "unknown")))
                .requestId(message.get("responseId").toStringValue())
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(outputTokens)
                .totalTokens(promptTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead) : null)
                .reasoningTokens(firstPositive(usage.get("reasoningTokens").toIntValue(0)))
                .currency("USD")
                .startTime(timestamp > 0 ? timestamp : null)
                .finishReason(message.get("stopReason").toStringValue());
        BigDecimal cost = BigDecimal.valueOf(
                usage.get("cost").get("total").toDoubleValue(0.0d));
        if (cost.signum() > 0) {
            builder.totalCost(cost);
        }
        return builder.build();
    }
}
