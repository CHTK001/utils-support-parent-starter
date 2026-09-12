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
   * Grok 构建 (xAI) usage parser.
 *
 * <p>Grok Build is xAI's agentic coding CLI. Each session lives under
 * {@code ~/.grok/sessions/<encoded-cwd>/<session-uuid>/} and contains:</p>
 * <ul>
 *   <li>{@code updates.jsonl} — ACP-style append-only event stream; the
   * authoritative usage 源. Each {@code turn_completed} 事件 carries
 *       a per-turn usage block (NOT a running total):
 *       <pre>{@code
 *       {
   * "参数": {
   * "更新": {
   * "会话更新": "turn_完成",
 *             "usage": {
   * "输入令牌": 1234,          // 含 缓存 的全量输入
   * "输出令牌": 56,
   * "缓存读取令牌": 900,
   * "缓存创建令牌": 34,
   * "ReasonML令牌": 12,
   * "模型usage": { "grok-4.5-构建": { ... } }
 *             }
 *           },
   * "_meta": { "智能体时间戳ms": 1783322059000 }
 *         },
   * "时间戳": 1783322059
 *       }
 *       }</pre></li>
 *   <li>{@code signals.json} — cumulative context-window counters, used only
   * When.js no {@code turn_completed} 事件 carry usage (legacy 会话);
 *       {@code totalTokens} is a context-size watermark, not a billed total.</li>
 * </ul>
 *
 * <p>Token semantics: Grok reports {@code inputTokens} inclusive of cached
   * 输入, so the non-缓存 输入 是否 {@编码 输入令牌 - 缓存读取 -
   * 缓存创建}; {@code outputTokens} 是否 reported minus ReasonML so the
   * ReasonML 数量 是否 a separate, additive 字段. 全部 per-turn usage
   * records are real (non-estimated) When.js present. When.js 下降 back 转为
 * {@code signals.json} the parser emits a single estimated context-token
 * snapshot flagged {@code estimated = true}.</p>
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
      * 流式解析单个 更新.jsonl。
     *
     * @param file 更新 事件文件
     * @return 逐 turn 用量记录流
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
      * 解析单行，仅处理 会话更新=turn_完成 且带 usage 块的事件。
     *
     * @param line 单行 JSON
     * @return 用量记录（无则 空）
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
            int cacheCreation = Math.max(usage.get("cacheCreationTokens").toIntValue(0),
                    usage.get("cachedWriteTokens").toIntValue(0));
            int reasoning = usage.get("reasoningTokens").toIntValue(0);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return Optional.empty();
            }
 // Grok camel大小写 口径的 输入令牌 含 缓存；输出令牌 含 ReasonML。
            // 非缓存输入 = 全量输入 - 缓存读 - 缓存写；净输出 = 全量输出 - 推理。
            int nonCachedInput = Math.max(0, inputTokens - cachedRead - cacheCreation);
            int netOutput = Math.max(0, outputTokens - reasoning);
            JsonNode meta = node.get("params").get("_meta");
            long startTime = parseGrokTimestamp(meta, node);
            String model = pickGrokModel(usage);

            return Optional.of(AiUsage.builder()
                    .provider(PROVIDER_GROK)
                    .model(model)
                    .inputTokens(nonCachedInput)
                    .outputTokens(netOutput)
                    .totalTokens(nonCachedInput + netOutput)
                    .cacheTokens(cachedRead > 0 ? cachedRead : (cacheCreation > 0 ? cacheCreation : null))
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
      * 从 _meta.智能体时间戳ms / 顶层 时间戳 推断事件时间。
     *
     * @param meta _meta 节点
     * @param root 根节点
     * @return epoch 毫秒；无法解析时 0
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
      * 从 usage.模型usage 选出 令牌 量最大的模型名。
     *
     * @param usage usage 节点
     * @return 模型名；无则 {@code "grok-build"}
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
      * 读取 映射 中指定 键 的 int 值（容错，缺失/非数字返回 0）。
     *
     * @param map  统计 映射
     * @param key  键名
     * @return int 值
     */
    private static int asInt(java.util.Map<String, Object> map, String key) {
        return map == null ? 0 : asInt(map.get(key));
    }
}
