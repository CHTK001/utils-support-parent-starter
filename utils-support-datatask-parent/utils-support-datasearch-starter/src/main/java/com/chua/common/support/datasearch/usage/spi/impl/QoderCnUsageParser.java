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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Qoder CN（国内版）用量解析器。
 *
 * <p>新版本 CN 应用（com.qodercn.app.stable）将会话存为
 * {@code ~/.qoder-cn/projects/<project>/<sessionId>.jsonl}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("qoder-cn")
public class QoderCnUsageParser extends BaseUsageParser {

    private static final String PROVIDER_QODER_CN = "qoder-cn";
    private static final String CURRENCY_CREDITS = "CREDITS";

    private static final Path PROJECTS_DIR = resolveProjectsDir();

    private static Path resolveProjectsDir() {
        String override = System.getenv("QODER_CN_PROJECTS_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".qoder-cn", "projects");
    }

    @Override
    public String name() {
        return PROVIDER_QODER_CN;
    }

    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() ->
                        reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @Override
    protected List<AiUsage> parseAll() {
        if (!Files.isDirectory(PROJECTS_DIR)) {
            log.debug("[qoder-cn] projects dir not found: {} (Qoder CN CLI not installed)", PROJECTS_DIR);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(PROJECTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file, result);
                        } catch (IOException e) {
                            log.debug("[qoder-cn] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[qoder-cn] walk failed: {}", e.getMessage(), e);
        }
        log.info("[qoder-cn] scanned {} session files, parsed {} records",
                fileCount.get(), result.size());
        return result;
    }

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
                    log.debug("[qoder-cn] parse failed {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }

    private Optional<AiUsage> parseNode(JsonNode node) {
        if (!"assistant".equals(node.get("type").toStringValue())) {
            return Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message.isMissingValue()) {
            return Optional.empty();
        }
        JsonNode usage = message.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        double credits = usage.get("credits").toDoubleValue(0.0d);
        if (credits <= 0) {
            credits = usage.get("original_credits").toDoubleValue(0.0d);
        }
        int inputTokens = usage.get("input_tokens").toIntValue(0);
        int outputTokens = usage.get("output_tokens").toIntValue(0);
        if (credits <= 0 && inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        long startTime = parseInstantToMillis(node.get("timestamp").toStringValue());
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_QODER_CN)
                .model(firstNonBlank(message.get("model").toStringValue(), "qoder-agent"))
                .requestId(message.get("id").toStringValue())
                .currency(CURRENCY_CREDITS)
                .startTime(startTime > 0 ? startTime : null)
                .finishReason(message.get("stop_reason").toStringValue());
        if (credits > 0) {
            builder.totalCost(BigDecimal.valueOf(credits));
        }
        if (inputTokens > 0 || outputTokens > 0) {
            builder.inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens);
        }
        return Optional.of(builder.build());
    }
}