package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reasonix 用量解析器。
 *
 * <p>数据源为 {@code ~/.reasonix/projects} 与 {@code ~/.reasonix/sessions}
 * 下的 {@code *.jsonl.telemetry.json}（累计快照）及其同名 {@code .meta} 伴生文件。
 * 令牌口径对齐 TokenTracker {@code normalizeReasonixTotals}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("reasonix")
public class ReasonixUsageParser extends BaseUsageParser {

    private static final String PROVIDER_REASONIX = "reasonix";
    private static final String DEFAULT_MODEL = "reasonix-unknown";

    private final Path reasonixHome;

    /** 默认构造器。 */
    public ReasonixUsageParser() {
        String home = System.getenv("REASONIX_STATE_HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("TOKENTRACKER_REASONIX_HOME");
        }
        this.reasonixHome = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".reasonix");
    }

    @Override
    public String name() {
        return PROVIDER_REASONIX;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTelemetryFiles();
        if (files.isEmpty()) {
            log.debug("[reasonix] no telemetry files under {}", reasonixHome);
            return Flux.empty();
        }
        log.info("[reasonix] scanning {} telemetry files", files.size());
        return Flux.fromIterable(files)
                .map(this::parseSnapshot)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.debug("[reasonix] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private AiUsage parseSnapshot(Path file) {
        try {
            JsonNode telemetry = Json.parse(Files.readString(file));
            JsonNode usage = telemetry.get("usage");
            if (usage.isMissingValue()) {
                return null;
            }
            int prompt = usage.get("promptTokens").toIntValue(0);
            int reasoning = usage.get("reasoningTokens").toIntValue(0);
            int completion = usage.get("completionTokens").toIntValue(0);
            int cacheMiss = usage.get("cacheMissTokens").toIntValue(0);
            int cacheHit = usage.get("cacheHitTokens").toIntValue(0);
            int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
            if (prompt <= 0 && completion <= 0 && reasoning <= 0) {
                return null;
            }
            int total = prompt + completion;
            int input = Math.max(0, prompt - cacheHit - cacheWrite);
            int output = Math.max(0, completion - reasoning);
            long startTime = resolveTimestamp(file);
            String model = resolveModel(file);
            Integer cacheTokens = cacheHit > 0 ? Integer.valueOf(cacheHit) : null;
            Integer reasoningTokens = reasoning > 0 ? Integer.valueOf(reasoning) : null;
            return AiUsage.builder()
                    .provider(PROVIDER_REASONIX)
                    .model(model)
                    .requestId(file.getFileName().toString())
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total)
                    .reasoningTokens(reasoningTokens)
                    .cacheTokens(cacheTokens)
                    .currency("USD")
                    .estimated(true)
                    .startTime(startTime > 0 ? startTime : null)
                    .build();
        } catch (Exception e) {
            log.debug("[reasonix] parse failed {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private long resolveTimestamp(Path file) {
        Path meta = Path.of(file.toString().replace(".telemetry.json", "") + ".meta");
        try {
            JsonNode node = Json.parse(Files.readString(meta));
            for (String key : new String[]{"updated_at", "created_at"}) {
                long ms = parseInstantToMillis(node.get(key).toStringValue());
                if (ms > 0) {
                    return ms;
                }
            }
        } catch (Exception e) {
            log.debug("[reasonix] meta read failed {}: {}", meta, e.getMessage());
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String resolveModel(Path file) {
        Path meta = Path.of(file.toString().replace(".telemetry.json", "") + ".meta");
        try {
            JsonNode node = Json.parse(Files.readString(meta));
            String model = node.get("model").toStringValue();
            if (model != null && !model.isBlank()) {
                int slash = model.lastIndexOf('/');
                return slash >= 0 ? model.substring(slash + 1) : model;
            }
        } catch (Exception e) {
            log.debug("[reasonix] meta model read failed {}: {}", meta, e.getMessage());
        }
        return DEFAULT_MODEL;
    }

    private List<Path> listTelemetryFiles() {
        List<Path> files = new ArrayList<>();
        for (String sub : new String[]{"projects", "sessions"}) {
            Path root = reasonixHome.resolve(sub);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".telemetry.json"))
                        .forEach(files::add);
            } catch (IOException e) {
                log.warn("[reasonix] walk failed: {}", e.getMessage());
            }
        }
        return files.stream().sorted().toList();
    }
}