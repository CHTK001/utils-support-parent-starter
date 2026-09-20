package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Claude Code 用量解析器——从本地 JSONL 会话文件解析 token 用量。
 *
 * <p>数据源为 {@code ~/.claude/projects} 目录，Claude
 * Code 会在其中为每个会话写入一份仅追加的 JSONL transcript。每条
 * {@code type=assistant} 记录都带有上游返回的 token 用量块：</p>
 *
 * <pre>{@code
 * {
 *   "type": "assistant",
 *   "timestamp": "2026-08-26T00:14:15.979Z",
 *   "message": {
 *     "role": "assistant",
 *     "model": "claude-sonnet-4-20250514",
 *     "usage": {
 *       "input_tokens": 1200,
 *       "output_tokens": 84,
 *       "cache_read_input_tokens": 512,
 *       "cache_creation_input_tokens": 0
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Only assistant records whose {@code usage} block has non-zero input or
 * output tokens are emitted; cache-only / zero-usage lines are skipped.
 * {@code <synthetic>} model names are normalized to {@code "unknown"}.</p>
 *
 * <p>一次 API 响应在转录中占多行：流式分块行（{@code stop_reason} 为空、输出令牌
 * 尚未结算，且 {@code input_tokens} 可能高于结算行）与最终行，最终行还会按响应里的
 * {@code tool_use} 内容块数量重复若干次。因此本解析器按 {@code message.id} 把相邻的
 * 同源行折叠为一条记录，Token 取带 {@code stop_reason} 的结算行；转录不含请求耗时，
 * {@code durationMillis} 由该请求首末分块的时间间隔推出，是模型耗时的下界。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude-code")
public class ClaudeCodeUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".claude", "projects");

    /**
     * 缺失 {@code message.id} 时使用的折叠键前缀
     */
    private static final String UNKEYED_PREFIX = "unkeyed-";

    /**
     * 无请求标识记录的折叠键序列，保证此类记录各自独占一窗
     */
    private static final AtomicLong UNKEYED_SEQUENCE = new AtomicLong();

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "claude-code"}
     */
    @Override
    public String name() {
        return "claude-code";
    }

    /**
     * 遗留实现（不再属于契约）：全量装载。请优先使用 {@link #streamAll()}。
     *
     * @return 原始用量记录列表
     */
    public List<AiUsage> parseAll() {
        return streamAll().collectList().block();
    }

    /**
     * 流式解析全部 JSONL 会话文件：逐文件、逐行惰性拉取，内存占用与单条记录
     * 相关而与总量无关。
     *
     * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.isDirectory(PROJECTS_DIR)) {
            return Flux.empty();
        }
        try {
            List<Path> files;
            try (var stream = Files.walk(PROJECTS_DIR)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".jsonl"))
                        .toList();
            }
            return Flux.fromIterable(files)
                    .subscribeOn(Schedulers.boundedElastic())
                    .concatMap(this::streamJsonlFile);
        } catch (IOException e) {
            return Flux.error(new IllegalStateException("walk failed", e));
        }
    }

    /**
     * 单个 JSONL 文件的行流（惰性 + 背压）。
     *
     * @param file 转录文件
     * @return 用量记录流
     */
    private Flux<AiUsage> streamJsonlFile(Path file) {
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(this::parseLineSafe)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .windowUntilChanged(ClaudeCodeUsageParser::groupKey)
                .concatMap(window -> window.reduce(ClaudeCodeUsageParser::merge));
    }

    /**
     * 分块行的折叠键；无请求标识的记录各自独占一窗。
     *
     * @param usage 用量记录
     * @return 折叠键
     */
    private static String groupKey(AiUsage usage) {
        String requestId = usage.getRequestId();
        return requestId == null || requestId.isBlank()
                ? UNKEYED_PREFIX + UNKEYED_SEQUENCE.getAndIncrement() : requestId;
    }

    /**
     * 折叠同一请求的两条分块记录：令牌取结算行，起止时间取首末分块。
     *
     * @param left  先到的记录
     * @param right 后到的记录
     * @return 折叠后的记录（就地改写载体）
     */
    private static AiUsage merge(AiUsage left, AiUsage right) {
        long leftAt = millis(left.getStartTime());
        long rightAt = millis(right.getStartTime());
        long earliest = leftAt == 0L ? rightAt
                : rightAt == 0L ? leftAt : Math.min(leftAt, rightAt);
        long latest = Math.max(leftAt, rightAt);
        AiUsage carrier = pickCarrier(left, right);
        carrier.setStartTime(earliest > 0L ? earliest : null);
        if (carrier.getDurationMillis() == null && latest > earliest) {
            carrier.setDurationMillis(latest - earliest);
        }
        return carrier;
    }

    /**
     * 选出代表整次请求的分块记录。
     *
     * <p>流式分块行的 {@code input_tokens} 可能高于结算行（尚未扣减缓存命中），
     * 按令牌数选型会留下输出未结算的那一份，故优先取带 {@code stop_reason} 的
     * 结算行；两侧同样结算时取令牌数更大的一份。</p>
     *
     * @param left  先到的记录
     * @param right 后到的记录
     * @return 载体记录
     */
    private static AiUsage pickCarrier(AiUsage left, AiUsage right) {
        boolean leftSettled = settled(left);
        boolean rightSettled = settled(right);
        if (leftSettled != rightSettled) {
            return leftSettled ? left : right;
        }
        return totalTokensOf(right) > totalTokensOf(left) ? right : left;
    }

    /**
     * 该分块行是否已结算（携带 {@code stop_reason}）。
     *
     * @param usage 用量记录
     * @return 已结算返回 true
     */
    private static boolean settled(AiUsage usage) {
        String finishReason = usage.getFinishReason();
        return finishReason != null && !finishReason.isBlank();
    }

    /**
     * 时间戳缺失时按 0 处理。
     *
     * @param value 时间戳
     * @return 毫秒时间戳
     */
    private static long millis(Long value) {
        return value == null || value < 0L ? 0L : value;
    }

    /**
     * 记录的总令牌数，用于在同样已结算的重复分块行之间取舍。
     *
     * @param usage 用量记录
     * @return 总令牌数
     */
    private static int totalTokensOf(AiUsage usage) {
        Integer total = usage.getTotalTokens();
        return total == null ? 0 : total;
    }

    /**
     * 安全解析单行，失败返回 empty（不中断流）。
     *
     * @param line 单行 JSON
     * @return 用量记录；非用量行或解析失败时 empty
     */
    private Optional<AiUsage> parseLineSafe(String line) {
        try {
            return parseNode(Json.parse(line));
        } catch (Exception e) {
            log.debug("[claude-code] line parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将单条 JSONL 记录解析为用量；仅处理带非零 usage 的 assistant 行。
     *
     * @param node 解析后的记录
     * @return 用量记录；非目标行时 empty
     */
    private Optional<AiUsage> parseNode(JsonNode node) {
        if (!"assistant".equals(node.get("type").toStringValue())) {
            return Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message.isMissingValue()) {
            return Optional.empty();
        }
        if (!"assistant".equals(message.get("role").toStringValue())) {
            return Optional.empty();
        }
        JsonNode usage = message.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        int inputTokens = usage.get("input_tokens").toIntValue(-1);
        int outputTokens = usage.get("output_tokens").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        int cacheRead = usage.get("cache_read_input_tokens").toIntValue(0);
        int cacheWrite = usage.get("cache_creation_input_tokens").toIntValue(0);
        long startTime = parseTimestamp(node.get("timestamp").toStringValue());
        return Optional.of(AiUsage.builder()
                .provider("anthropic")
                .model(normalizeModel(message.get("model").toStringValue()))
                .requestId(message.get("id").toStringValue())
                .finishReason(message.get("stop_reason").toStringValue())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .startTime(startTime > 0 ? startTime : null)
                .build());
    }

    /**
     * 解析 ISO-8601 时间戳为 epoch 毫秒。
     *
     * @param ts ISO-8601 时间字符串
     * @return epoch 毫秒；为空或非法时 0
     */
    private long parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) {
            return 0L;
        }
        try {
            return java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 归一化模型名：{@code <synthetic>} 占位归一为 {@code "unknown"}。
     *
     * @param model 原始模型名
     * @return 归一化结果
     */
    private String normalizeModel(String model) {
        return (model == null || model.isBlank() || "<synthetic>".equals(model))
                ? "unknown" : model;
    }
}
