package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude-code")
public class ClaudeCodeUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".claude", "projects");

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
        if (!Files.isDirectory(PROJECTS_DIR)) {
            log.debug("[claude-code] projects dir not found: {}", PROJECTS_DIR);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        int[] fileCount = {0};
        try (var stream = Files.walk(PROJECTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount[0]++;
                        try {
                            parseJsonlFile(file, result);
                        } catch (IOException e) {
                            log.debug("[claude-code] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[claude-code] walk failed: {}", e.getMessage(), e);
        }
        log.info("[claude-code] scanned {} files, parsed {} records", fileCount[0], result.size());
        return result;
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
                .map(Optional::get);
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
     * 逐行读取 JSONL 文件并追加解析结果（旧契约内部实现）。
     *
     * @param file   转录文件
     * @param result 累积结果列表
     * @throws IOException 文件读取失败
     */
    private void parseJsonlFile(Path file, List<AiUsage> result) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = Json.parse(line);
                    parseNode(node).ifPresent(result::add);
                } catch (Exception e) {
                    log.debug("[claude-code] parse failed {}: {}", file.getFileName(), e.getMessage());
                }
            }
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
