package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Command Code usage parser - parses token usage and cost from local session transcripts.
 *
 * <p>Data source is {@code ~/.commandcode/projects/<project-slug>/<session-id>.jsonl}.
 * Each session is an append-only JSONL transcript; assistant messages carry a top-level
 * {@code usage} block and a top-level {@code model} ({@code provider/model}):</p>
 *
 * <pre>{@code
 * {
 *   "type": "message",
 *   "timestamp": "2026-08-28T23:37:33.866Z",
 *   "message": { "role": "assistant", ... },
 *   "usage": {
 *     "inputTokens": 21141,
 *     "outputTokens": 121,
 *     "cacheReadTokens": 7936,
 *     "cacheWriteTokens": 0,
 *     "costUsd": 0.004786432
 *   },
 *   "model": "deepseek/deepseek-v4-flash"
 * }
 * }</pre>
 *
 * <p>Sidecar files ({@code *.checkpoints.jsonl}, {@code *.prompts.jsonl}, ...) are
 * excluded; only {@code <session-id>.jsonl} transcripts are scanned.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("command-code")
public class CommandCodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(CommandCodeUsageParser.class);

    /** Session transcripts root: ~/.commandcode/projects */
    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".commandcode", "projects");

    private static final String PROVIDER_COMMAND_CODE = "command-code";
    private static final String UNKNOWN_MODEL = "unknown";

    @Override
    public String name() {
        return "command-code";
    }

    /**
     * 流式解析全部 session 转录：逐文件、逐行惰性拉取，内存占用与单条记录相关而与总量无关。
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
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .filter(CommandCodeUsageParser::isTranscript)
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
     * 仅扫描主转录文件，跳过 checkpoints/prompts 等 sidecar。
     */
    private static boolean isTranscript(Path file) {
        String name = file.getFileName().toString();
        return !name.contains(".checkpoints.") && !name.contains(".prompts.");
    }

    /**
     * 单个 JSONL 文件的行流（惰性 + 背压）。
     */
    private Flux<AiUsage> streamJsonlFile(Path file) {
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(this::parseLineSafe)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get);
    }

    /**
     * 安全解析单行，失败返回 empty。
     */
    private java.util.Optional<AiUsage> parseLineSafe(String line) {
        try {
            return parseNode(Json.parse(line));
        } catch (Exception e) {
            log.debug("[command-code] line parse failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 将一条转录行转换为 AiUsage 记录。
     *
     * <p>仅接受带顶层 {@code usage} 且含有效 token/费用的 assistant 消息行。</p>
     */
    private java.util.Optional<AiUsage> parseNode(JsonNode node) {
        JsonNode type = node.get("type");
        if (type.isMissingValue() || !"message".equals(type.toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message.isMissingValue() || !"assistant".equals(message.get("role").toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode usage = node.get("usage");
        if (usage.isMissingValue()) {
            return java.util.Optional.empty();
        }
        int inputTokens = usage.get("inputTokens").toIntValue(-1);
        int outputTokens = usage.get("outputTokens").toIntValue(-1);
        double costUsd = usage.get("costUsd").toDoubleValue(0.0d);
        if (inputTokens <= 0 && outputTokens <= 0 && costUsd <= 0) {
            return java.util.Optional.empty();
        }
        int cacheRead = usage.get("cacheReadTokens").toIntValue(0);
        int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
        long startTime = parseInstantToMillis(node.get("timestamp").toStringValue());

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_COMMAND_CODE)
                .model(firstNonBlank(node.get("model").toStringValue(), UNKNOWN_MODEL))
                .requestId(firstNonBlank(node.get("id").toStringValue(), null))
                .startTime(startTime > 0 ? startTime : null);
        if (inputTokens > 0 || outputTokens > 0) {
            builder.inputTokens(inputTokens > 0 ? inputTokens : null)
                    .outputTokens(outputTokens > 0 ? outputTokens : null)
                    .totalTokens(Math.max(inputTokens, 0) + Math.max(outputTokens, 0))
                    .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                            : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null);
        }
        if (costUsd > 0) {
            builder.totalCost(BigDecimal.valueOf(costUsd))
                    .currency("USD")
                    .estimated(true);
        }
        return java.util.Optional.of(builder.build());
    }
}
