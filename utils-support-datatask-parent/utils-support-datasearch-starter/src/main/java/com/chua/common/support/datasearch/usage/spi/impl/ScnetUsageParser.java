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
import com.chua.common.support.utils.StringUtils;

/**
 * SCNet 客户端（计算服务）用量解析器。
 *
 * <p>SCNet 客户端的 Agent 运行时把每个会话持久化为扁平 JSONL，且落在两处并列目录：</p>
 * <ul>
 *   <li>{@code ~/.sclaw/agent/sessions/<ISO8601>_<sessionId>.jsonl} —— 全局（非项目）会话；</li>
 *   <li>{@code ~/.sclaw/agent/projects/<projectId>/sessions/<ISO8601>_<sessionId>.jsonl} ——
 *       按项目归档的会话，是主要数据源。</li>
 * </ul>
 *
 * <p>两处都要扫：本机实测（2026-11-18）全局目录 2 个文件 400 余条带用量消息，项目目录 21 个文件
 * 1.1 万余条，只扫全局目录会丢掉 96% 以上的用量（两处 responseId 无交集，不存在跨目录重复计数）。</p>
 *
 * <p>会话行是带 {@code type} 的事件信封，实测出现的类型为 {@code session}、{@code session_info}、
 * {@code model_change}、{@code thinking_level_change}、{@code message}、{@code custom}、
 * {@code compaction}。用量挂在两处：{@code type=message} 事件的 {@code message.usage}（且只出现在
 * {@code role=assistant} 的消息里），以及 {@code type=compaction} 事件的顶层 {@code usage}。</p>
 *
 * <p>{@code compaction} 是上下文压缩（摘要）调用，输入整段上下文，本机（2026-11-18）81 条、输入中位数
 * 约 14 万 Token；同文件内没有任何一次 {@code message} 调用的用量段与它相同，属于独立计费调用，必须计入，
 * 否则长会话被压缩后的大量消耗会漏计。它没有 {@code message} 包壳，也无顶层 {@code model}/
 * {@code modelId}，模型取 {@code details.budget.model}（本机均记录为 {@code SCNet-Max}）。</p>
 *
 * <p>用量信封取客户端自身写入 assistant 消息的结构，字段口径与相同内核的 OpenClaw 一致：
 * {@code usage.{input,output,cacheRead,cacheWrite,reasoning,totalTokens,cost.{input,output,cacheRead,
 * cacheWrite,total}}}，并带 {@code role}/{@code model}/{@code provider}/{@code stopReason}/
 * {@code timestamp}/{@code durationMs}。四段 Token 互斥，段和即 {@code totalTokens}（本机 1.1 万余条
 * 带用量消息全部满足 {@code total == input + output + cacheRead + cacheWrite}）；按本仓库约定，
 * {@code inputTokens} 必须含缓存命中，因此产出前把 {@code cacheRead} 与 {@code cacheWrite} 并回输入，
 * 命中量单独落在 {@code cacheTokens}。</p>
 *
 * <p>{@code output} 是含推理的合计（{@code reasoning} 不计入 {@code totalTokens}，且本机项目会话里
 * {@code reasoning/output} 最大 0.98、无一越界），而仓库口径要求
 * {@code outputTokens} 不含推理、{@code reasoningTokens} 单列（{@code UsageFieldCompleter} 会按
 * 输出价 + 推理价分别计价），故产出前把推理量净出输出。若某版本改回互斥口径
 * （{@code reasoning > output}），则原样保留输出，避免净出成 0。</p>
 *
 * <p>本机 {@code cost} 四个分段全为 0（按 SCNet 平台额度计费，客户端不落金额），因此不产出费用
 * （{@code totalCost} 留空，币种保持 {@code AiUsage} 的默认 {@code USD}），交给定价补全链路按需推算。</p>
 *
 * <p>另有一层容错：若会话改写成轨迹事件形态（{@code type=model.completed}，助手消息收在
 * {@code data.messagesSnapshot[]}、事件级 {@code data.usage} 为本轮累计值），本解析器只认快照里的
 * 消息、忽略累计值——同一累计值会在本轮每个事件上重复写出，直接取用会放大数倍。当前客户端版本
 * 未落这种事件，保留该分支只为跨版本兼容。</p>
 *
 * <p>去重键优先取上游响应号（{@code responseId}/{@code id}），缺失时退到消息时间戳，
 * 再缺失才用文件内下标，避免同一调用在相邻事件里被计两次。</p>
 *
 * <p>{@code message.timestamp} 是请求发起时刻（本机实测：发起时刻 + {@code durationMs} 落在
 * 行级 {@code timestamp} 上），故 {@code startTime} 直接取它、{@code durationMillis} 取
 * {@code durationMs}，二者相加即为本次调用区间。</p>
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
     * 默认构造：解析当前用户主目录。
     */
    public ScnetUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public ScnetUsageParser(String userHomes) {
        super(userHomes);
    }

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
     * @param home 解析根目录
     * @return 会话目录
     */
    protected Path sessionRoot(Path home) {
        return home.resolve(".sclaw").resolve("agent").resolve("sessions");
    }

    /**
     * 项目会话根目录，子类可覆写以便测试或支持重定向后的数据目录。
     *
     * @param home 解析根目录
     * @return 项目会话根目录
     */
    protected Path projectRoot(Path home) {
        return home.resolve(".sclaw").resolve("agent").resolve("projects");
    }

    /**
     * 全部会话根目录：全局会话与项目会话并列，缺一即丢量。
     *
     * @param home 解析根目录
     * @return 会话根目录列表
     */
    protected List<Path> sessionRoots(Path home) {
        return List.of(sessionRoot(home), projectRoot(home));
    }

    /**
     * 流式输出全部会话文件中的单次调用用量。
     *
     * @return 用量记录流；会话目录缺失时为空流
     */
    @Override
    protected Flux<AiUsage> streamHome(Path home) {
        List<Path> files = listSessionFiles(home);
        if (files.isEmpty()) {
            log.debug("[scnet] no session files under {}", sessionRoots(home));
            return Flux.empty();
        }
        log.info("[scnet] streaming from {} session files", files.size());
        return Flux.fromIterable(files)
                .concatMap(this::streamSession);
    }

    /**
     * 列举会话文件（含会话目录下的子目录，客户端升级后布局可能变化）。
     *
     * @param home 解析根目录
     * @return 会话文件列表；目录缺失时为空列表
     */
    protected List<Path> listSessionFiles(Path home) {
        List<Path> files = Wraps.list();
        for (Path root : sessionRoots(home)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .forEach(files::add);
            } catch (IOException e) {
                log.warn("[scnet] scan {} failed: {}", root, e.getMessage(), e);
            }
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
        if (StringUtils.isNotBlank(role) && !"assistant".equals(role)) {
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
        int reasoning = Math.max(0, firstNonZero(usage, "reasoning", "reasoningTokens", "reasoning_tokens"));
        if (input <= 0 && output <= 0 && cacheRead <= 0) {
            return null;
        }
        // 客户端把 input / cacheRead / cacheWrite / output 拆成互斥段，段和即 totalTokens；
        // 仓库口径要求 inputTokens 含缓存命中，故先并回再产出，命中量单独上报。
        int promptTokens = Math.max(0, input) + cacheRead + cacheWrite;
        // output 是含推理的合计，仓库口径要求输出不含推理、推理单列，否则会被按输出价 + 推理价计两遍；
        // 若某版本改回互斥口径（推理超过输出），原样保留输出。
        int outputTokens = reasoning > 0 && reasoning <= output
                ? output - reasoning : Math.max(0, output);
        long durationMs = message.get("durationMs").toLongValue(0L);
        JsonNode cost = usage.get("cost");
        BigDecimal totalCost = BigDecimal.valueOf(firstNonZeroDouble(cost, "total", "totalCost", "costUSD"));
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_SCNET)
                .model(resolveModel(message, context))
                .requestId(firstNonBlank(message.get("responseId").toStringValue(),
                        message.get("id").toStringValue()))
                .inputTokens(promptTokens)
                .outputTokens(outputTokens)
                .totalTokens(promptTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead) : null)
                .reasoningTokens(firstPositive(reasoning))
                .startTime(longOrNull(message.get("timestamp").toLongValue(0L)))
                .durationMillis(durationMs > 0L ? Long.valueOf(durationMs) : null)
                .finishReason(message.get("stopReason").toStringValue());
        if (totalCost.signum() > 0) {
            builder.totalCost(totalCost).currency("USD");
        }
        return builder.build();
    }

    /**
     * 按优先级确定模型名：消息自身字段优先，压缩事件退到 {@code details.budget.model}。
     *
     * @param message 消息节点
     * @param context 承载事件（可能相等）
     * @return 模型名；全部缺失时返回 {@link #UNKNOWN_MODEL}
     */
    private static String resolveModel(JsonNode message, JsonNode context) {
        String model = message.get("model").toStringValue();
        if (StringUtils.isBlank(model)) {
            model = context.get("modelId").toStringValue();
        }
        if (StringUtils.isBlank(model)) {
            model = context.get("model").toStringValue();
        }
        if (StringUtils.isBlank(model)) {
            model = context.get("details").get("budget").get("model").toStringValue();
        }
        return firstNonBlank(model, UNKNOWN_MODEL);
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
        if (StringUtils.isNotBlank(responseId)) {
            return responseId;
        }
        String id = message.get("id").toStringValue();
        if (StringUtils.isNotBlank(id)) {
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
