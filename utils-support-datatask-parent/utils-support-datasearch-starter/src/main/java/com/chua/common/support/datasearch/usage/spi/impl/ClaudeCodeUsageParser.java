package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code usage parser - parses token usage from local JSONL session files.
 *
 * <p>Data source is the {@code ~/.claude/projects} directory.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude-code")
public class ClaudeCodeUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".claude", "projects");

    @Override
    public String name() {
        return "claude-code";
    }

    @Override
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

    private java.util.Optional<AiUsage> parseNode(JsonNode node) {
        if (!"assistant".equals(node.get("type").toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message.isMissingValue()) {
            return java.util.Optional.empty();
        }
        if (!"assistant".equals(message.get("role").toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode usage = message.get("usage");
        if (usage.isMissingValue()) {
            return java.util.Optional.empty();
        }
        int inputTokens = usage.get("input_tokens").toIntValue(-1);
        int outputTokens = usage.get("output_tokens").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return java.util.Optional.empty();
        }
        int cacheRead = usage.get("cache_read_input_tokens").toIntValue(0);
        int cacheWrite = usage.get("cache_creation_input_tokens").toIntValue(0);
        long startTime = parseTimestamp(node.get("timestamp").toStringValue());
        return java.util.Optional.of(AiUsage.builder()
                .provider("anthropic")
                .model(normalizeModel(message.get("model").toStringValue()))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead : (cacheWrite > 0 ? cacheWrite : null))
                .startTime(startTime > 0 ? startTime : null)
                .build());
    }

    private long parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return 0L;
        try { return java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli(); }
        catch (Exception e) { return 0L; }
    }

    private String normalizeModel(String model) {
        return (model == null || "<synthetic>".equals(model)) ? "unknown" : model;
    }
}
