package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tencent CodeBuddy Code usage parser.
 *
 * <p>CodeBuddy Code (npm {@code @tencent-ai/codebuddy-code}) persists each
 * session as a Claude-Code-style transcript under
 * {@code ~/.codebuddy/projects/<project>/<sessionId>.jsonl}. Completed
 * assistant messages carry real per-request usage:</p>
 *
 * <pre>{@code
 * {
 *   "type": "message",
 *   "role": "assistant",
 *   "timestamp": 1787546545090,
 *   "sessionId": "...",
 *   "message": {
 *     "usage": {
 *       "input_tokens": 24964,
 *       "output_tokens": 17,
 *       "total_tokens": 24981,
 *       "cache_read_input_tokens": 24928
 *     }
 *   },
 *   "providerData": {
 *     "model": "hy3",
 *     "rawUsage": {
 *       "prompt_cache_hit_tokens": 24928,
 *       "completion_thinking_tokens": 14,
 *       "credit": 0, ...
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>The CN-edition twin directory {@code ~/.codebuddycn} is scanned as well.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codebuddy")
public class CodeBuddyUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR_INTL = Path.of(
            System.getProperty("user.home"), ".codebuddy", "projects");

    private static final Path PROJECTS_DIR_CN = Path.of(
            System.getProperty("user.home"), ".codebuddycn", "projects");

    private static final String PROVIDER_CODEBUDDY = "codebuddy";

    /**
     * Returns the SPI name for CodeBuddy.
     *
     * @return {@code "codebuddy"}
     */
    /**
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @Override
    public String name() {
        return "codebuddy";
    }

    /**
     * Parses all CodeBuddy session transcripts and extracts token usage.
     *
     * @return list of AiUsage records, one per completed assistant response
     */
    @Override protected List<AiUsage> parseAll() {
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        for (Path projectsDir : new Path[] {PROJECTS_DIR_INTL, PROJECTS_DIR_CN}) {
            if (!Files.isDirectory(projectsDir)) {
                log.debug("[codebuddy] projects dir not found: {}", projectsDir);
                continue;
            }
            try (var stream = Files.walk(projectsDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .forEach(file -> {
                            fileCount.incrementAndGet();
                            try {
                                parseJsonlFile(file, result);
                            } catch (IOException e) {
                                log.debug("[codebuddy] read failed {}: {}",
                                        file.getFileName(), e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("[codebuddy] walk failed {}: {}", projectsDir, e.getMessage());
            }
        }
        log.info("[codebuddy] scanned {} session files, parsed {} records",
                fileCount.get(), result.size());
        return result;
    }

    /**
     * Reads one transcript file line by line, extracting assistant usage.
     *
     * @param file   path to the session JSONL file
     * @param result accumulator list for parsed records
     * @throws IOException if the file cannot be read
     */
    private void parseJsonlFile(Path file, List<AiUsage> result) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    parseNode(Json.parse(line)).ifPresent(result::add);
                } catch (Exception e) {
                    log.debug("[codebuddy] parse failed {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Converts one transcript JSON line into an AiUsage record when it is a
     * completed assistant response carrying usage data.
     *
     * @param node parsed JSON of a single transcript line
     * @return the parsed record, or empty when no usage is present
     */
    private java.util.Optional<AiUsage> parseNode(JsonNode node) {
        if (!"message".equals(node.get("type").toStringValue())
                || !"assistant".equals(node.get("role").toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode usage = node.get("message").get("usage");
        if (usage.isMissingValue()) {
            return java.util.Optional.empty();
        }
        int inputTokens = usage.get("input_tokens").toIntValue(-1);
        int outputTokens = usage.get("output_tokens").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return java.util.Optional.empty();
        }
        long startTime = node.get("timestamp").toLongValue(0L);
        JsonNode providerData = node.get("providerData");
        double credit = 0.0d;
        if (!providerData.isMissingValue() && !providerData.get("rawUsage").isMissingValue()) {
            credit = providerData.get("rawUsage").get("credit").toDoubleValue(0.0d);
        }

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_CODEBUDDY)
                .model(firstNonBlank(providerData.get("model").toStringValue(), "unknown"))
                .requestId(firstNonBlank(node.get("id").toStringValue(),
                        node.get("sessionId").toStringValue()))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(readCacheTokens(usage))
                .reasoningTokens(readReasoningTokens(providerData))
                .startTime(startTime > 0 ? startTime : null)
                .finishReason(node.get("status").toStringValue());
        if (credit > 0) {
            builder.totalCost(BigDecimal.valueOf(credit)).currency("CREDITS");
        }
        return java.util.Optional.of(builder.build());
    }

    /**
     * Reads cached-token count from the transcript usage block.
     *
     * @param usage the message.usage block
     * @return cached tokens, or null when absent or zero
     */
    private Integer readCacheTokens(JsonNode usage) {
        int cached = usage.get("cache_read_input_tokens").toIntValue(0);
        return cached > 0 ? cached : null;
    }

    /**
     * Reads reasoning-token count from the raw provider usage metadata.
     *
     * @param providerData the line-level providerData block
     * @return reasoning tokens, or null when absent or zero
     */
    private Integer readReasoningTokens(JsonNode providerData) {
        if (providerData.isMissingValue()) {
            return null;
        }
        JsonNode rawUsage = providerData.get("rawUsage");
        if (rawUsage.isMissingValue()) {
            return null;
        }
        JsonNode details = rawUsage.get("completion_tokens_details");
        if (details.isMissingValue()) {
            return null;
        }
        int reasoning = details.get("reasoning_tokens").toIntValue(0);
        return reasoning > 0 ? reasoning : null;
    }
}
