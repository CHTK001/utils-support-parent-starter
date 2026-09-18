package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * WorkBuddy (Tencent) usage parser.
 *
 * <p>WorkBuddy Code (npm {@code @tencent-ai/workbuddy-code}) is the CN-edition
 * Claude-Code fork sibling of CodeBuddy. It persists each session as a
 * transcript under {@code ~/.workbuddy/projects/<project>/<sessionId>.jsonl}
 * with nested sub-agent transcripts under {@code sessions/<sid>/subagents/}.
 * Completed assistant turns carry real per-request usage via
 * {@code providerData.rawUsage}:</p>
 *
 * <pre>{@code
 * {
 *   "type": "message",
 *   "role": "assistant",
 *   "timestamp": 1787546545090,
 *   "sessionId": "...",
 *   "id": "...",
 *   "providerData": {
 *     "model": "...",
 *     "messageId": "resp-...",
 *     "rawUsage": {
 *       "prompt_tokens": 24964,
 *       "completion_tokens": 17,
 *       "prompt_tokens_details": { "cached_tokens": 24928 },
 *       "cache_read_input_tokens": 24928,
 *       "cache_creation_input_tokens": 0,
 *       "completion_tokens_details": { "reasoning_tokens": 14 }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Unlike CodeBuddy (whose {@code cache_creation} is usually 0), WorkBuddy
 * bills both cache-read <i>and</i> cache-write into {@code prompt_tokens}, so
 * the non-cached input must subtract <b>both</b> {@code cache_read} and
 * {@code cache_creation}; reasoning is carried inside
 * {@code completion_tokens} and split out separately.</p>
 *
 * <p>De-dup key is {@code providerData.messageId} (response-level, shared by
 * the assistant message and its function_call pair); falls back to
 * {@code id} then {@code sessionId:timestamp}. Records whose rawUsage is all
 * zero are skipped (request-start placeholder that is back-filled in place).</p>
 *
 * @author CH
 * @since 4.0.0.44
     */
@Spi("workbuddy")
public class WorkBuddyUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".workbuddy", "projects");

    private static final String PROVIDER_WORKBUDDY = "workbuddy";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "workbuddy"}
    */
    @Override
    public String name() {
        return PROVIDER_WORKBUDDY;
    }

    /**
    * 响应式流式入口：惰性扫描全部会话转录（含子代理目录）。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTranscriptFiles();
        if (files.isEmpty()) {
            log.debug("[workbuddy] no project transcripts under {}", PROJECTS_DIR);
            return Flux.empty();
        }
        log.info("[workbuddy] streaming from {} transcript files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamTranscriptFile, 4)
                .onErrorResume(e -> {
                    log.debug("[workbuddy] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
    * 枚举 {@code ~/.workbuddy/projects/**} 下的全部会话转录文件
    * （主会话与 {@code subagents/agent-*.jsonl} 子代理）。
    *
    * @return 转录文件列表
    */
    private List<Path> listTranscriptFiles() {
        if (!Files.isDirectory(PROJECTS_DIR)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(PROJECTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[workbuddy] walk failed: {}", e.getMessage(), e);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    /**
    * 流式解析单个转录文件，仅处理携带 rawUsage 的 assistant / function_call 记录。
    *
    * @param file 转录文件
    * @return 用量记录流
    */
    private Flux<AiUsage> streamTranscriptFile(Path file) {
        return streamLines(file)
                .map(this::parseLineSafe)
                .filter(Objects::nonNull)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
    * 解析单行；失败或非用量行返回 null。
    *
    * @param line 单行 JSON
    * @return 用量记录或 null
    */
    private AiUsage parseLineSafe(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            return parseNode(Json.parse(line));
        } catch (Exception e) {
            log.debug("[workbuddy] line parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
    * 将转录行转换为 AiUsage；仅处理携带 rawUsage 的记录。
    *
    * @param node 解析后的行
    * @return 用量记录；无 rawUsage 或全零时返回 null
    */
    private AiUsage parseNode(JsonNode node) {
        JsonNode providerData = node.get("providerData");
        if (providerData.isMissingValue()) {
            return null;
        }
        JsonNode rawUsage = providerData.get("rawUsage");
        if (rawUsage.isMissingValue()) {
            return null;
        }
        int promptTokens = rawUsage.get("prompt_tokens").toIntValue(0);
        int completionTokens = rawUsage.get("completion_tokens").toIntValue(0);
        if (promptTokens <= 0 && completionTokens <= 0) {
            return null;
        }
        JsonNode details = rawUsage.get("prompt_tokens_details");
        int cacheRead = Math.max(
                rawUsage.get("cache_read_input_tokens").toIntValue(0),
                details.get("cached_tokens").toIntValue(0));
        int cacheCreation = rawUsage.get("cache_creation_input_tokens").toIntValue(0);
        int reasoning = rawUsage.get("completion_tokens_details")
                .get("reasoning_tokens").toIntValue(0);

        int inputTokens = Math.max(0, promptTokens - cacheRead - cacheCreation);
        int outputTokens = Math.max(0, completionTokens - reasoning);
        int totalTokens = promptTokens + completionTokens;

        String model = firstNonBlank(
                providerData.get("model").toStringValue(),
                rawUsage.get("model").toStringValue(),
                "unknown");
        long startTime = node.get("timestamp").toLongValue(0L);
        String requestId = firstNonBlank(
                providerData.get("messageId").toStringValue(),
                node.get("id").toStringValue(),
                node.get("sessionId").toStringValue() + ":" + startTime);

        return AiUsage.builder()
                .provider(PROVIDER_WORKBUDDY)
                .model(model)
                .requestId(requestId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead
                        : (cacheCreation > 0 ? cacheCreation : null))
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .build();
    }

    /**
    * 返回第一个非空字符串。
    *
    * @param values 候选字符串
    * @return 第一个非空白值；全空时 ""
    */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
