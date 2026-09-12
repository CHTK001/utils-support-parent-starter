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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LM Studio usage parser.
 *
 * <p>LM Studio persists OpenAI-compatible server responses as
 * pretty-printed log files under {@code ~/.lmstudio/server-logs/}
 * (all platforms). Each completed Chat Completions / Responses API call
 * writes a structured log line that contains a top-level
 * {@code "usage"} object with scalar token counters:</p>
 *
 * <pre>{@code
 * "usage": {
 *   "prompt_tokens": 1200,
 *   "completion_tokens": 84,
 *   "total_tokens": 1284,
 *   "prompt_tokens_details": { "cached_tokens": 512 },
 *   "completion_tokens_details": { "reasoning_tokens": 20 }
 * }
 * }</pre>
 *
 * <p>Token semantics follow {@code normalizeLocalStudioTokens}:
 * {@code prompt_tokens} is the <b>full</b> prompt (cache-inclusive), so
 * non-cached input = total − completion − cacheRead − cacheWrite;
 * {@code output} excludes reasoning.</p>
 *
 * <p>Each record is deduped by its response {@code id}
 * ({@code chatcmpl-…} / {@code cmpl-…} / {@code resp_…}); when the id is
 * absent the parser falls back to a fingerprint of
 * (file, model, timestamp, totals).</p>
 *
 * <p>All records carry {@code estimated = false} when a usage block is
 * present (the tokens are real, provided by the model server).</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("lmstudio")
public class LmStudioUsageParser extends BaseUsageParser {

    private static final Path LMSTUDIO_HOME = resolveLmstudioHome();

    private static final String PROVIDER_LMSTUDIO = "lmstudio";

    /** 匹配 usage 块起点：行首/对象成员位置的 {@code "usage" : {...}}。 */
    private static final Pattern USAGE_MARKER = Pattern.compile(
            "\"usage\"\\s*:\\s*\\{", Pattern.MULTILINE);

    /** 匹配响应 id 前缀。 */
    private static final Pattern RESPONSE_ID = Pattern.compile(
            "\"id\"\\s*:\\s*\"(chatcmpl-[^\"\\s]+|cmpl-[^\"\\s]+|resp_[^\"\\s]+)\"");

    /** 匹配 model 字段（取 usage 之前最近的 model 字符串）。 */
    private static final Pattern MODEL_FIELD = Pattern.compile(
            "\"model\"\\s*:\\s*\"([^\"]+)\"");

    /** 匹配时间戳行：{@code [2026-07-04 12:34:56][...]}。 */
    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\][\\s\\S]*$",
            Pattern.MULTILINE);

    private static Path resolveLmstudioHome() {
        String override = System.getenv("LMSTUDIO_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".lmstudio");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "lmstudio"}
     */
    @Override
    public String name() {
        return PROVIDER_LMSTUDIO;
    }

    /**
     * 流式解析全部 LM Studio server-log 文件中的 usage 事件。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        Path logDir = LMSTUDIO_HOME.resolve("server-logs");
        if (!Files.isDirectory(logDir)) {
            log.debug("[lmstudio] server-logs dir not found: {} (LM Studio not installed)", logDir);
            return Flux.empty();
        }
        List<Path> files;
        try (var walk = Files.walk(logDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                    .toList();
        } catch (Exception e) {
            log.warn("[lmstudio] walk failed: {}", e.getMessage(), e);
            return Flux.empty();
        }
        if (files.isEmpty()) {
            log.debug("[lmstudio] no .log files under {}", logDir);
            return Flux.empty();
        }
        log.info("[lmstudio] scanning {} log files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamLogFile, 2)
                .onErrorResume(e -> {
                    log.debug("[lmstudio] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 流式解析单个日志文件：每个 usage 块发出一条记录。
     *
     * @param file 日志文件
     * @return 用量记录流
     */
    private Flux<AiUsage> streamLogFile(Path file) {
        return Flux.defer(() -> {
            List<AiUsage> records = new ArrayList<>();
            try (var reader = Files.newBufferedReader(file)) {
                StringBuilder buffer = new StringBuilder();
                java.io.BufferedReader br = (java.io.BufferedReader) reader;
                String line;
                while ((line = br.readLine()) != null) {
                    buffer.append(line).append('\n');
                    if (buffer.length() > 8 * 1024 * 1024) {
                        // 窗口保护：超长文件分片，防止 OOM
                        extractUsages(buffer, file, records);
                        // 保留最后 1MB 作为下一次滑窗起点（usage 块不跨窗）
                        String tail = buffer.substring(buffer.length() - 1024 * 1024);
                        buffer = new StringBuilder(tail);
                    }
                }
                extractUsages(buffer, file, records);
            } catch (Exception e) {
                log.debug("[lmstudio] read failed {}: {}", file, e.getMessage());
            }
            return Flux.fromIterable(records);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从窗口文本中提取所有 usage 块对应的记录。
     *
     * @param window   窗口文本
     * @param file     来源文件（用于 fingerprint 去重）
     * @param records  结果累积列表
     */
    private void extractUsages(StringBuilder window, Path file, List<AiUsage> records) {
        String text = window.toString();
        Matcher usageMatcher = USAGE_MARKER.matcher(text);
        while (usageMatcher.find()) {
            int start = usageMatcher.start();
            int braceOpen = text.indexOf('{', usageMatcher.start());
            int end = matchingBrace(text, braceOpen);
            if (end < 0) {
                continue;
            }
            String usageBlock = text.substring(braceOpen, end + 1);
            JsonNode usage;
            try {
                usage = Json.parse(usageBlock);
            } catch (Exception e) {
                continue;
            }
            int prompt = toInt(usage, "prompt_tokens", "input_tokens");
            int completion = toInt(usage, "completion_tokens", "output_tokens");
            if (prompt + completion <= 0) {
                continue;
            }
            int total = Math.max(toInt(usage, "total_tokens"), prompt + completion);

            JsonNode promptDetails = firstPresent(usage,
                    "prompt_tokens_details", "input_tokens_details", "inputTokensDetails");
            JsonNode outputDetails = firstPresent(usage,
                    "completion_tokens_details", "output_tokens_details",
                    "completionTokensDetails", "outputTokensDetails");
            int cacheRead = clamp(prompt, Math.max(
                    toInt(promptDetails, "cached_tokens", "cache_read_tokens"),
                    toInt(usage, "cached_tokens")));
            int cacheWrite = clamp(Math.max(0, prompt - cacheRead), Math.max(
                    toInt(promptDetails, "cache_creation_input_tokens", "cache_write_tokens"),
                    toInt(usage, "cache_creation_input_tokens")));
            int reasoning = clamp(completion, Math.max(
                    toInt(outputDetails, "reasoning_tokens"),
                    toInt(usage, "reasoning_tokens")));

            int input = Math.max(0, total - completion - cacheRead - cacheWrite);
            int output = Math.max(0, completion - reasoning);

            // 取 usage 块之前最近的响应 id / model / timestamp
            String prefix = text.substring(Math.max(0, start - 16384), start);
            String id = lastMatch(RESPONSE_ID, prefix);
            String model = lastMatch(MODEL_FIELD, prefix);
            Long startTime = parseLatestTimestamp(prefix, file);

            String fingerprint = id != null
                    ? "lmstudio:" + id
                    : "lmstudio:" + java.util.Objects.hash(
                            file.getFileName(), model, startTime, total);

            AiUsage.AiUsageBuilder builder = AiUsage.builder()
                    .provider(PROVIDER_LMSTUDIO)
                    .model(model != null ? model : "lmstudio-local")
                    .requestId(fingerprint)
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(input + output + cacheRead + cacheWrite)
                    .cacheTokens(cacheRead > 0 ? cacheRead : (cacheWrite > 0 ? cacheWrite : null))
                    .reasoningTokens(reasoning > 0 ? reasoning : null)
                    .currency("USD")
                    .estimated(false)
                    .startTime(startTime != null && startTime > 0 ? startTime : null);
            records.add(builder.build());
        }
    }

    /**
     * 取 JSON 对象内平衡括号匹配的结束位置。
     *
     * @param text  完整文本
     * @param open  起始 {@code { } 下标
     * @return 配对 {@code } } 下标；未闭合时 -1
     */
    private int matchingBrace(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"': inString = true; break;
                case '{': depth++; break;
                case '}':
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                    break;
                default: break;
            }
        }
        return -1;
    }

    /**
     * 取前缀文本中最后一次匹配到的分组 1。
     *
     * @param pattern 匹配模式
     * @param prefix  前缀文本
     * @return 最后一次匹配；无则 null
     */
    private String lastMatch(Pattern pattern, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        Matcher matcher = pattern.matcher(prefix);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    /**
     * 取前缀中最近的时间戳行。
     *
     * @param prefix 前缀文本
     * @param file   来源文件（时间戳缺失时用其 mtime）
     * @return epoch 毫秒；无法解析时 0
     */
    private Long parseLatestTimestamp(String prefix, Path file) {
        Matcher matcher = TIMESTAMP_LINE.matcher(prefix);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        if (found == null) {
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (Exception e) {
                return 0L;
            }
        }
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                    found, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    /**
     * 取 JsonNode 中第一个存在的 key 的 int 值。
     *
     * @param node 节点
     * @param keys 候选键
     * @return int 值；全缺 0
     */
    private int toInt(JsonNode node, String... keys) {
        if (node == null || node.isMissingValue()) {
            return 0;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isMissingValue()) {
                return value.toIntValue(0);
            }
        }
        return 0;
    }

    /**
     * 取 JsonNode 中第一个存在的 key 对应的子节点。
     *
     * @param node 节点
     * @param keys 候选键
     * @return 子节点；全缺返回缺失值节点
     */
    private JsonNode firstPresent(JsonNode node, String... keys) {
        if (node == null || node.isMissingValue()) {
            return JsonNode.valueOf(null);
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isMissingValue()) {
                return value;
            }
        }
        return JsonNode.valueOf(null);
    }

    /**
     * clamp 到 [0, max]。
     *
     * @param max  上界
     * @param value 值
     * @return 截断后的值
     */
    private int clamp(int max, int value) {
        return Math.max(0, Math.min(max, value));
    }
}
