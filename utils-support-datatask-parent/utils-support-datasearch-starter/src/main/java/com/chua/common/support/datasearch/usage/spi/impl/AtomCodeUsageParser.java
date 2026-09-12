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
   * Atom编码 usage parser - 解析 令牌 usage 从 本地 会话 turn transcripts.
 *
 * <p>Data source is {@code ~/.atomcode/sessions/<session-dir>/<session-id>.jsonl}.
   * Each 线 records one turn 的 a 会话; every turn carries a top-级别
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
 * }</pre>824 }
 * }
 * }</pre>
 *
 * <p>Unlike Command Code transcripts, AtomCode turn lines carry no
 * {@code model}/{@code provider}/{@code costUsd} fields; only token counts are
   * extracted, so no cost estimation 是否 执行 here.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("atomcode")
public class AtomCodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(AtomCodeUsageParser.class); // 日志

    /**
      * Atom编码 Home 目录，支持 ATOMCODE_Home 环境变量覆盖。
     * 默认为 ~/.atomcode
     */
    private static final Path ATOMCODE_HOME;

    /** 会话 transcripts 根: $ATOMCODE_Home/会话 */
    private static final Path SESSIONS_DIR;

    private static final String PROVIDER_ATOMCODE = "atomcode"; // 提供者atomcode

    static {
        String envHome = System.getenv("ATOMCODE_HOME");
        if (envHome != null && !envHome.isBlank()) {
            ATOMCODE_HOME = Path.of(envHome);
        } else {
            ATOMCODE_HOME = Path.of(System.getProperty("user.home"), ".atomcode");
        }
        SESSIONS_DIR = ATOMCODE_HOME.resolve("sessions");
    }

    @Override
    public String name() {
        return "atomcode";
    }

    /**
      * 流式解析全部 会话 转录：逐文件、逐行惰性拉取，内存占用与单条记录相关而与总量无关。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            log.debug("[atomcode] sessions directory not found: {}", SESSIONS_DIR);
            return Flux.empty();
        }
        try {
            List<Path> files;
            try (var stream = Files.walk(SESSIONS_DIR)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .toList();
            }
            log.debug("[atomcode] found {} JSONL files in {}", files.size(), SESSIONS_DIR);
            return Flux.fromIterable(files)
                    .subscribeOn(Schedulers.boundedElastic())
                    .concatMap(this::streamJsonlFile);
        } catch (IOException e) {
            return Flux.error(new IllegalStateException("walk failed", e));
        }
    }

    /**
     * 单个 JSONL 文件的行流（惰性 + 背压）。
     * @param file 文件
     * @return 流jsonl文件的结果
     */
    private Flux<AiUsage> streamJsonlFile(Path file) {
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(this::parseLineSafe)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    /**
      * 安全解析单行，失败返回 空。
     * @param line 线
     * @return 解析线safe的结果
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
      * 将一条转录行转换为 AIusage 记录。
     *
     * <p>仅接受带顶层 {@code usage} 且含有效 token 数的 turn 记录。</p>
     * @param node 节点
     * @return 解析节点的结果
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
