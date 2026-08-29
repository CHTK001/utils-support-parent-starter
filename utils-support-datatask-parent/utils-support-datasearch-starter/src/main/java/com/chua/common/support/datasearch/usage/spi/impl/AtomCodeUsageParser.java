package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AtomCode usage parser - parses token usage from local session turn transcripts.
 *
 * <p>Data source is {@code ~/.atomcode/sessions/<session-dir>/<session-id>.jsonl}.
 * Each line records one turn of a session; every turn carries a top-level
 * {@code usage} block with real per-turn token counts:</p>
 *
 * <pre>{@code
 * {
 *   "v": 1,
 *   "ts": 1787964681603,
 *   "iso": "2026-08-29T00:51:21.603+00:00",
 *   "session_id": "a5947423-8deb-412c-9246-4683628d72c7",
 *   "turn_id": 1,
 *   "undone": false,
 *   "user": "...",
 *   "assistant": "...",
 *   "reasoning": "...",
 *   "tools": [...],
 *   "usage": { "prompt": 46135, "completion": 9507, "cached": 45824 }
 * }
 * }</pre>
 *
 * <p>Unlike Command Code transcripts, AtomCode turn lines carry no
 * {@code model}/{@code provider}/{@code costUsd} fields; only token counts are
 * extracted, so no cost estimation is performed here.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("atomcode")
public class AtomCodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(AtomCodeUsageParser.class);

    /** Session transcripts root: ~/.atomcode/sessions */
    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".atomcode", "sessions");

    private static final String PROVIDER_ATOMCODE = "atomcode";

    @Override
    public String name() {
        return "atomcode";
    }

    /**
     * 流式解析全部 session 转录：逐文件、逐行惰性拉取，内存占用与单条记录相关而与总量无关。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            return Flux.empty();
        }
        try {
            List<Path> files;
            try (var stream = Files.walk(SESSIONS_DIR)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
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
     */
    private Flux<AiUsage> streamJsonlFile(Path file) {
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(this::parseLineSafe)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    /**
     * 安全解析单行，失败返回 empty。
     */
    private Optional<AiUsage> parseLineSafe(String line) {
        try {
            return parseNode(Json.parse(line));
        } catch (Exception e) {
            log.debug("[atomcode] line parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将一条转录行转换为 AiUsage 记录。
     *
     * <p>仅接受带顶层 {@code usage} 且含有效 token 数的 turn 记录。</p>
     */
    private Optional<AiUsage> parseNode(JsonNode node) {
        JsonNode usage = node.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        int inputTokens = usage.get("prompt").toIntValue(-1);
        int outputTokens = usage.get("completion").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        int cached = usage.get("cached").toIntValue(0);
        long startTime = node.get("ts").toLongValue(0L);
        String sessionId = node.get("session_id").toStringValue("unknown");
        int turnId = node.get("turn_id").toIntValue(-1);

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_ATOMCODE)
                .requestId(sessionId + "-" + turnId)
                .startTime(startTime > 0 ? startTime : null);
        if (inputTokens > 0) {
            builder.inputTokens(inputTokens);
        }
        if (outputTokens > 0) {
            builder.outputTokens(outputTokens);
        }
        builder.totalTokens(Math.max(inputTokens, 0) + Math.max(outputTokens, 0));
        if (cached > 0) {
            builder.cacheTokens(cached);
        }
        return Optional.of(builder.build());
    }
}
