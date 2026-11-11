// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * SCNet 客户端（计算服务）用量解析器。
 *
 * <p>SCNet 客户端的 Agent 运行时把每个会话持久化为扁平 JSONL：
 * {@code ~/.sclaw/agent/sessions/<ISO8601>_<sessionId>.jsonl}（会话目录由
 * {@code ~/.sclaw/agent/sessions} 汇总，目录不存在即视为未安装客户端）。</p>
 *
 * <p>会话行是带 {@code type} 的事件信封，实测出现的类型为 {@code session}、{@code session_info}、
 * {@code model_change}、{@code thinking_level_change}、{@code message}、{@code custom}；用量只挂在
 * {@code type=message} 事件的 {@code message} 上，且只出现在 {@code role=assistant} 的消息里。</p>
 *
 * <p>用量信封取客户端自身写入 assistant 消息的结构，字段口径与相同内核的 OpenClaw 一致：
 * {@code usage.{input,output,cacheRead,cacheWrite,totalTokens,cost.{input,output,cacheRead,
 * cacheWrite,total}}}，并带 {@code role}/{@code model}/{@code stopReason}/{@code timestamp}。
 * 四段 Token 互斥，段和即 {@code totalTokens}（本机 2 个会话 29 条带用量消息全部满足
 * {@code total == input + output + cacheRead + cacheWrite}）；按本仓库约定，{@code inputTokens}
 * 必须含缓存命中，因此产出前把 {@code cacheRead} 与 {@code cacheWrite} 并回输入，命中量单独落在
 * {@code cacheTokens}。</p>
 *
 * <p>另有一层容错：若会话改写成轨迹事件形态（{@code type=model.completed}，助手消息收在
 * {@code data.messagesSnapshot[]}、事件级 {@code data.usage} 为本轮累计值），本解析器只认快照里的
 * 消息、忽略累计值——同一累计值会在本轮每个事件上重复写出，直接取用会放大数倍。当前客户端版本
 * 未落这种事件，保留该分支只为跨版本兼容。</p>
 *
 * <p>去重键优先取上游响应号（{@code responseId}/{@code id}），缺失时退到消息时间戳，
 * 再缺失才用文件内下标，避免同一调用在相邻事件里被计两次。</p>
 *
 * <p>会话转录不记录请求耗时，故 {@code durationMillis} 留空；{@code startTime} 取消息时间戳。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("scnet")
public class ScnetUsageParser extends BaseUsageParser {

    /**
     * 用量来源标识
     */
    private static final String PROVIDER_SCNET = "scnet";

    /**
     * 模型名缺失时的占位标签
     */
    private static final String UNKNOWN_MODEL = "unknown";

    /**
     * 默认会话目录：{@code ~/.sclaw/agent/sessions}
     */
    private static final Path DEFAULT_SESSION_ROOT = Path.of(
            System.getProperty("user.home"), ".sclaw", "agent", "sessions");

    /**
     * 返回 SCNet 的 SPI 名称。
     *
     * @return {@code "scnet"}
     */
    @Override
    public String name() {
        return PROVIDER_SCNET;
    }

    /**
     * 会话目录，子类可覆写以便测试或支持重定向后的数据目录。
     *
     * @return 会话目录
     */
    protected Path sessionRoot() {
        return DEFAULT_SESSION_ROOT;
    }

    /**
     * 流式输出全部会话文件中的单次调用用量。
     *
     * @return 用量记录流；会话目录缺失时为空流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[scnet] no session files under {}", sessionRoot());
            return Flux.empty();
        }
        log.info("[scnet] streaming from {} session files", files.size());
        return Flux.fromIterable(files)
                .concatMap(this::streamSession);
    }

    /**
     * 列举会话文件（含会话目录下的子目录，客户端升级后布局可能变化）。
     *
     * @return 会话文件列表；目录缺失时为空列表
     */
    protected List<Path> listSessionFiles() {
        Path root = sessionRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = Wraps.list();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[scnet] scan {} failed: {}", root, e.getMessage(), e);
        }
        return files;
    }

    /**
     * 单个会话文件的用量流。
     *
     * @param file 会话文件
     * @return 用量记录流
     */
    protected Flux<AiUsage> streamSession(Path file) {
        return Flux.defer(() -> Flux.fromIterable(collectCalls(file)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 汇总一个会话文件内的全部单次调用，按去重键收敛。
     *
     * @param file 会话文件
     * @return 去重后的用量记录列表
     */
    protected List<AiUsage> collectCalls(Path file) {
        Map<String, AiUsage> calls = Wraps.linkedMap();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> absorbLine(line, calls, file.getFileName().toString()));
        } catch (IOException | UncheckedIOException e) {
            log.debug("[scnet] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return Wraps.list(calls.values());
    }

    /**
     * 从一行会话记录中取出单次调用的用量。
     *
     * @param line  单行 JSON
     * @param calls 以调用标识为键的累积结果
     * @param file  文件名，用作兜底去重键前缀
     */
    private void absorbLine(String line, Map<String, AiUsage> calls, String file) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonNode node = Json.parse(line);
            JsonNode data = node.get("data");
            JsonNode snapshot = data.isMissingValue() ? data : data.get("messagesSnapshot");
            if (snapshot.isArray()) {
                absorbSnapshot(node, snapshot, calls);
                return;
            }
            absorbMessage(node, node, calls, file);
        } catch (Exception e) {
            log.debug("[scnet] parse failed: {}", e.getMessage());
        }
    }

    /**
     * 吸收轨迹事件快照里的助手消息。
     *
     * @param event    轨迹事件行
     * @param snapshot 消息快照数组
     * @param calls    累积结果
     */
    private void absorbSnapshot(JsonNode event, JsonNode snapshot, Map<String, AiUsage> calls) {
        String eventModel = event.get("modelId").toStringValue();
        int size = snapshot.size();
        for (int i = 0; i < size; i++) {
            JsonNode message = snapshot.get(i);
            absorbMessage(message, event, calls, eventModel + "#" + i);
        }
    }

    /**
     * 把一条候选记录转成用量记录并归档；非助手消息、无用量、全零用量都会被跳过。
     *
     * @param candidate 候选记录（消息本身，或包裹它的行/事件）
     * @param context   提供模型名兜底的上下文节点
     * @param calls     累积结果
     * @param fallback  兜底去重键
     */
    private void absorbMessage(JsonNode candidate, JsonNode context, Map<String, AiUsage> calls,
                               String fallback) {
        JsonNode message = resolveMessage(candidate);
        String role = message.get("role").toStringValue();
        if (role != null && !role.isBlank() && !"assistant".equals(role)) {
            return;
        }
        JsonNode usage = resolveUsage(candidate, message);
        if (usage.isMissingValue()) {
            return;
        }
        AiUsage converted = toUsage(usage, message, context);
        if (converted == null) {
            return;
        }
        calls.put(callKey(message, fallback), converted);
    }

    /**
     * 定位真正的消息节点：兼容 {@code {"message": {...}}} 与扁平消息两种形态。
     *
     * @param candidate 候选记录
     * @return 消息节点；不存在时返回候选本身
     */
    private JsonNode resolveMessage(JsonNode candidate) {
        JsonNode message = candidate.get("message");
        return message.isMissingValue() ? candidate : message;
    }

    /**
     * 定位用量节点，兼容消息内、行顶层、{@code data} 下三种承载位置。
     *
     * @param candidate 候选记录
     * @param message   消息节点
     * @return 用量节点；都不存在时返回 missing 节点
     */
    private JsonNode resolveUsage(JsonNode candidate, JsonNode message) {
        JsonNode usage = message.get("usage");
        if (!usage.isMissingValue()) {
            return usage;
        }
        usage = candidate.get("usage");
        if (!usage.isMissingValue()) {
            return usage;
        }
        JsonNode data = candidate.get("data");
        return data.isMissingValue() ? data : data.get("usage");
    }

    /**
     * 把一条消息的用量转成标准记录。
     *
     * @param usage   用量节点
     * @param message 消息节点
     * @param context 模型名兜底上下文
     * @return 用量记录；无有效 Token 时返回 null
     */
    private AiUsage toUsage(JsonNode usage, JsonNode message, JsonNode context) {
        int input = firstNonZero(usage, "input", "inputTokens", "promptTokens", "prompt_tokens");
        int output = firstNonZero(usage, "output", "outputTokens", "completionTokens", "completion_tokens");
        int cacheRead = Math.max(0, firstNonZero(usage, "cacheRead", "cache_read_input_tokens",
                "cacheReadInputTokens", "cached_tokens"));
        int cacheWrite = Math.max(0, firstNonZero(usage, "cacheWrite", "cache_write_tokens",
                "cacheWriteInputTokens"));
        if (input <= 0 && output <= 0 && cacheRead <= 0) {
            return null;
        }
        // 客户端把 input / cacheRead / cacheWrite / output 拆成互斥段，段和即 totalTokens；
        // 仓库口径要求 inputTokens 含缓存命中，故先并回再产出，命中量单独上报。
        int promptTokens = Math.max(0, input) + cacheRead + cacheWrite;
        int outputTokens = Math.max(0, output);
        JsonNode cost = usage.get("cost");
        BigDecimal totalCost = BigDecimal.valueOf(firstNonZeroDouble(cost, "total", "totalCost", "costUSD"));
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_SCNET)
                .model(firstNonBlank(message.get("model").toStringValue(),
                        firstNonBlank(context.get("modelId").toStringValue(),
                                firstNonBlank(context.get("model").toStringValue(), UNKNOWN_MODEL))))
                .requestId(firstNonBlank(message.get("responseId").toStringValue(),
                        message.get("id").toStringValue()))
                .inputTokens(promptTokens)
                .outputTokens(outputTokens)
                .totalTokens(promptTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead) : null)
                .reasoningTokens(firstPositive(firstNonZero(usage, "reasoningTokens", "reasoning",
                        "reasoning_tokens")))
                .currency("USD")
                .startTime(longOrNull(message.get("timestamp").toLongValue(0L)))
                .finishReason(message.get("stopReason").toStringValue());
        if (totalCost.signum() > 0) {
            builder.totalCost(totalCost);
        }
        return builder.build();
    }

    /**
     * 按优先级读取第一个非零整数字段。
     *
     * @param node   容器节点
     * @param fields 候选字段名，按优先级排列
     * @return 第一个非零值；全部缺失或为零时返回 0
     */
    private static int firstNonZero(JsonNode node, String... fields) {
        for (String field : fields) {
            int value = node.get(field).toIntValue(0);
            if (value != 0) {
                return value;
            }
        }
        return 0;
    }

    /**
     * 按优先级读取第一个非零浮点字段。
     *
     * @param node   容器节点
     * @param fields 候选字段名，按优先级排列
     * @return 第一个非零值；全部缺失或为零时返回 0
     */
    private static double firstNonZeroDouble(JsonNode node, String... fields) {
        for (String field : fields) {
            double value = node.get(field).toDoubleValue(0.0d);
            if (value != 0.0d) {
                return value;
            }
        }
        return 0.0d;
    }

    /**
     * 调用去重键：响应号优先，其次时间戳，最后才是兜底下标。
     *
     * @param message  消息节点
     * @param fallback 兜底键
     * @return 去重键
     */
    private static String callKey(JsonNode message, String fallback) {
        String responseId = message.get("responseId").toStringValue();
        if (responseId != null && !responseId.isBlank()) {
            return responseId;
        }
        String id = message.get("id").toStringValue();
        if (id != null && !id.isBlank()) {
            return id;
        }
        long timestamp = message.get("timestamp").toLongValue(0L);
        return timestamp > 0 ? Long.toString(timestamp) : fallback;
    }

    /**
     * 正数时间戳转 Long。
     *
     * @param value 原始值
     * @return 大于 0 时返回包装值，否则返回 null
     */
    private static Long longOrNull(long value) {
        return value > 0L ? Long.valueOf(value) : null;
    }
}
