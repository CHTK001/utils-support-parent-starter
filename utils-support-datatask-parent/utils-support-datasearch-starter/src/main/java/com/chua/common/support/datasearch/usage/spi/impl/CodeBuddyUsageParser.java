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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CodeBuddy usage parser.
 *
 * <p>CodeBuddy stores chat history in {@code %LOCALAPPDATA%\CodeBuddyExtension\Data\},
 * but the history format does not include token usage information (input/output tokens, cost).
 * Usage is managed server-side by the CodeBuddy platform.</p>
 *
 * <p>To get CodeBuddy usage: check the CodeBuddy web dashboard or contact
 * CodeBuddy support for usage reports.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codebuddy")
public class CodeBuddyUsageParser extends BaseUsageParser {

    private static final Path CODEBUDDY_DATA = Path.of(
            System.getProperty("user.home"), "AppData", "Local",
            "CodeBuddyExtension", "Data");

    @Override
    public String name() {
        return "codebuddy";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(CODEBUDDY_DATA)) {
            log.debug("[codebuddy] CodeBuddy not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(CODEBUDDY_DATA)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[codebuddy] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[codebuddy] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[codebuddy] No JSONL files found in CodeBuddy data directory");
        }
        return List.of();
    }

    private void parseJsonlFile(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = Json.parse(line);
                    if ("assistant".equals(node.get("type").toStringValue())) {
                        JsonNode msg = node.get("message");
                        if ("assistant".equals(msg.get("role").toStringValue())) {
                            JsonNode usage = msg.get("usage");
                            if (!usage.isMissingValue()) {
                                int inputTokens = usage.get("input_tokens").toIntValue(-1);
                                int outputTokens = usage.get("output_tokens").toIntValue(-1);
                                if (inputTokens > 0 || outputTokens > 0) {
                                    log.debug("[codebuddy] Found usage in CodeBuddy JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[codebuddy] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
