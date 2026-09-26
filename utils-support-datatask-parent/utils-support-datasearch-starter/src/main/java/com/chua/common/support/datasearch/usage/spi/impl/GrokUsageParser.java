package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grok Build（xAI）用量解析器。
 *
 * <p>Grok Build 是 xAI 的智能体编码 CLI。每个会话位于
 * {@code ~/.grok/sessions/<编码后的工作目录>/<会话 UUID>/}，
 * 其中的 {@code updates.jsonl} 是 ACP 风格的仅追加事件流，也是本解析器
 * 唯一的用量来源。文件按 {@code (encoded-cwd, session-uuid)} 目录逐个流式读取。</p>
 *
 * <p>每条 {@code sessionUpdate = "turn_completed"} 事件携带<b>单轮</b>用量块
 * （不是累计值），结构如下：</p>
 * <pre>{@code
 * {
 *   "params": {
 *     "update": {
 *       "sessionUpdate": "turn_completed",
 *       "usage": {
 *         "inputTokens": 1234,          // 含缓存命中与缓存写入的全量输入
 *         "outputTokens": 56,           // 含推理部分
 *         "cachedReadTokens": 900,      // 兼容字段 cacheReadInputTokens
 *         "reasoningTokens": 12,
 *         "modelUsage": { "grok-4.5-build": { ... } }
 *       }
 *     },
 *     "_meta": { "agentTimestampMs": 1783322059000 }
 *   },
 *   "timestamp": 1783322059
 * }
 * }</pre>
 *
 * <p>令牌口径：Grok 按 camel 口径上报，{@code inputTokens} 已包含缓存命中与缓存写入，
 * {@code outputTokens} 已包含推理令牌。因此：</p>
 * <ul>
 *   <li>输入原样出数，不再二次扣减缓存，避免重复扣减；</li>
 *   <li>缓存命中量被夹取到 {@code inputTokens} 以内，随 {@code cacheTokens} 出数；</li>
 *   <li>推理量被夹取到 {@code outputTokens} 以内后从输出中扣除，
 *       使推理令牌不按输出单价重复计价，故 {@code outputTokens} 出的是净输出。</li>
 * </ul>
 *
 * <p>事件时间优先取 {@code params._meta.agentTimestampMs}，缺失时回退顶层
 * {@code timestamp}（自动区分秒级与毫秒级）。所有记录均为真实上报值，
 * {@code estimated} 恒为 {@code false}。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("grok")
public class GrokUsageParser extends BaseUsageParser {

    private static final Path GROC_HOME = resolveHome(); // grocHome

    private static final String PROVIDER_GROK = "grok"; // 提供者grok

    private static final String CURRENCY_USD = "USD"; // 货币usd

    /**
     * resolveHome。
     * @return resolveHome的结果
     */
    private static Path resolveHome() {
        String grokHome = System.getenv("GROK_HOME");
        if (grokHome != null && !grokHome.isBlank()) {
            return Path.of(grokHome);
        }
        return Path.of(System.getProperty("user.home"), ".grok");
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
     * 流式解析全部会话的 turn_completed 用量事件。
     *
     * <p>按文件惰性拉取：先枚举 sessions 目录，再逐文件逐行流式解析，
     * 内存占用与单条记录相关而非与总量相关。</p>
     */
    @Override
    public Flux<AiUsage> streamAll() {
        Path sessionsDir = GROC_HOME.resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) {
            log.debug("[grok] sessions dir not found: {} (Grok Build not installed)", sessionsDir);
            return Flux.empty();
        }
        List<Path> files;
        try (var stream = Files.walk(sessionsDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("updates.jsonl"))
                    .toList();
        } catch (Exception e) {
            log.warn("[grok] walk failed: {}", e.getMessage(), e);
            return Flux.empty();
        }
        if (files.isEmpty()) {
            log.debug("[grok] no updates.jsonl files under {}", sessionsDir);
            return Flux.empty();
        }
        log.info("[grok] streaming from {} session files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamUpdatesFile, 4)
                .onErrorResume(e -> {
                    log.debug("[grok] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 流式解析单个 {@code updates.jsonl}。
     *
     * @param file {@code updates.jsonl} 事件文件
     * @return 逐轮用量记录流
     */
    private Flux<AiUsage> streamUpdatesFile(Path file) {
        return streamLines(file)
                .flatMap(line -> Mono.fromCallable(() -> parseTurnLine(line))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(Mono::justOrEmpty),
                        16)
                .onErrorResume(e -> {
                    log.debug("[grok] read failed {}: {}", file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 解析单行，仅处理 {@code sessionUpdate = "turn_completed"} 且带 {@code usage} 块的事件。
     *
     * @param line 单行 JSON
     * @return 用量记录；不满足条件时为 {@link Optional#empty()}
     */
    private Optional<AiUsage> parseTurnLine(String line) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = Json.parse(line);
            JsonNode update = node.get("params").get("update");
            if (!"turn_completed".equals(update.get("sessionUpdate").toStringValue())) {
                return Optional.empty();
            }
            JsonNode usage = update.get("usage");
            if (usage.isMissingValue()) {
                return Optional.empty();
            }
            int inputTokens = usage.get("inputTokens").toIntValue(0);
            int outputTokens = usage.get("outputTokens").toIntValue(0);
            int cachedRead = Math.max(usage.get("cachedReadTokens").toIntValue(0),
                    usage.get("cacheReadInputTokens").toIntValue(0));
            int reasoning = usage.get("reasoningTokens").toIntValue(0);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return Optional.empty();
            }
            // Grok 的 camel 口径：inputTokens 含缓存命中与缓存写入，outputTokens 含推理部分。
            // 因此输入原样出数，推理量夹准到输出以内后净出，避免推理令牌按输出价重复计价。
            int cacheHit = Math.min(Math.max(0, cachedRead), Math.max(0, inputTokens));
            reasoning = Math.max(0, Math.min(reasoning, Math.max(0, outputTokens)));
            int netOutput = Math.max(0, outputTokens) - reasoning;
            JsonNode meta = node.get("params").get("_meta");
            long startTime = parseGrokTimestamp(meta, node);
            String model = pickGrokModel(usage);

            return Optional.of(AiUsage.builder()
                    .provider(PROVIDER_GROK)
                    .model(model)
                    .inputTokens(inputTokens > 0 ? Integer.valueOf(inputTokens) : null)
                    .outputTokens(netOutput)
                    .totalTokens(Math.max(0, inputTokens) + netOutput)
                    .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                    .reasoningTokens(reasoning > 0 ? reasoning : null)
                    .currency(CURRENCY_USD)
                    .estimated(false)
                    .startTime(startTime > 0 ? startTime : null)
                    .build());
        } catch (Exception e) {
            log.debug("[grok] line parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 {@code params._meta.agentTimestampMs} 或顶层 {@code timestamp} 推断事件时间。
     *
     * @param meta {@code _meta} 节点
     * @param root 根节点
     * @return epoch 毫秒；无法解析时返回 0
     */
    private long parseGrokTimestamp(JsonNode meta, JsonNode root) {
        if (!meta.isMissingValue()) {
            int agentMs = meta.get("agentTimestampMs").toIntValue(0);
            if (agentMs > 0) {
                return agentMs;
            }
        }
        long top = root.get("timestamp").toLongValue(0L);
        if (top <= 0) {
            return 0L;
        }
        // Grok 顶层 timestamp 可能是秒级（<= 1e12）或毫秒级
        return top > 1_000_000_000_000L ? top : top * 1000L;
    }

    /**
     * 从 {@code usage.modelUsage} 选出令牌量最大的模型名。
     *
     * @param usage {@code usage} 节点
     * @return 模型名；缺失时返回 {@code "grok-build"}
     */
    private String pickGrokModel(JsonNode usage) {
        JsonNode modelUsage = usage.get("modelUsage");
        if (!modelUsage.isMissingValue() && modelUsage.isObject()) {
            java.util.Map<String, Object> map = modelUsage.toJsonObject().toMap();
            String best = null;
            long bestTokens = -1L;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> stats) {
                    Map<String, Object> converted = new java.util.HashMap<>();
                    stats.forEach((k, v) -> {
                        if (k instanceof String s) {
                            converted.put(s, v);
                        }
                    });
                    long tokens = asInt(converted.get("totalTokens"))
                            + asInt(converted.get("inputTokens"))
                            + asInt(converted.get("outputTokens"));
                    if (tokens >= bestTokens) {
                        bestTokens = tokens;
                        best = entry.getKey();
                    }
                }
            }
            if (best != null && !best.isBlank()) {
                return best;
            }
        }
        return "grok-build";
    }

    /**
     * 读取映射中指定键的 int 值（容错：映射为 {@code null}、键缺失或非数字均返回 0）。
     *
     * @param map 统计映射
     * @param key 键名
     * @return int 值
     */
    private static int asInt(java.util.Map<String, Object> map, String key) {
        return map == null ? 0 : asInt(map.get(key));
    }
}
