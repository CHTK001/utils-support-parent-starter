package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cline usage parser.
 *
 * <p>Cline CLI stores one session JSON per run under
 * {@code ~/.cline/data/sessions/<id>/<id>.json}, containing real per-session
 * token usage reported by the upstream provider:</p>
 *
 * <pre>{@code
 * {
 *   "provider": "gemini",
 *   "model": "gemini-3.6-flash",
 *   "started_at": "2026-08-24T02:21:53.998Z",
 *   "ended_at": "2026-08-24T02:21:59.295Z",
 *   "metadata": {
 *     "usage": {
 *       "inputTokens": 4864,
 *       "outputTokens": 116,
 *       "cacheReadTokens": 0,
 *       "cacheWriteTokens": 0,
 *       "totalCost": 0.004083
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Companion {@code *.messages.json} files are skipped.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cline")
public class ClineUsageParser extends BaseUsageParser {

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".cline", "data", "sessions");

    /**
    * Returns the SPI name for Cline.
    *
    * @return {@code "cline"}
    */
    public String name() {
        return "cline";
    }

    /**
    * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
    */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
    * Parses all Cline CLI session files and extracts token usage.
    *
    * @return list of AiUsage records, one per completed session
    */
    @Override protected List<AiUsage> parseAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            log.debug("[cline] sessions dir not found: {}", SESSIONS_DIR);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        try (var stream = Files.walk(SESSIONS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".messages.json"))
                    .forEach(file -> parseSession(file).ifPresent(result::add));
        } catch (IOException e) {
            log.warn("[cline] walk failed: {}", e.getMessage(), e);
        }
        log.info("[cline] parsed {} session records", result.size());
        return result;
    }

    /**
    * Parses a single Cline session file into an AiUsage record.
    *
    * @param file path to the session JSON file
    * @return the parsed AiUsage, or empty if the file has no usage data
    */
    private java.util.Optional<AiUsage> parseSession(Path file) {
        try {
            JsonNode node = Json.parse(Files.readString(file));
            JsonNode usage = node.get("metadata").get("usage");
            if (usage.isMissingValue()) {
                return java.util.Optional.empty();
            }
            int inputTokens = usage.get("inputTokens").toIntValue(-1);
            int outputTokens = usage.get("outputTokens").toIntValue(-1);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return java.util.Optional.empty();
            }
            int cacheRead = usage.get("cacheReadTokens").toIntValue(0);
            int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
            double cost = usage.get("totalCost").toDoubleValue(0.0d);
            long startTime = parseInstantToMillis(node.get("started_at").toStringValue());
            long endTime = parseInstantToMillis(node.get("ended_at").toStringValue());
            return java.util.Optional.of(AiUsage.builder()
                    .provider(firstNonBlank(node.get("provider").toStringValue(), "cline"))
                    .model(node.get("model").toStringValue())
                    .requestId(node.get("session_id").toStringValue())
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .cacheTokens(cacheRead > 0 ? cacheRead : null)
                    .totalCost(cost > 0 ? java.math.BigDecimal.valueOf(cost) : null)
                    .currency("USD")
                    .startTime(startTime > 0 ? startTime : null)
                    .durationMillis(startTime > 0 && endTime > startTime ? endTime - startTime : null)
                    .finishReason(node.get("status").toStringValue())
                    .build());
        } catch (Exception e) {
            log.debug("[cline] parse failed {}: {}", file.getFileName(), e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
