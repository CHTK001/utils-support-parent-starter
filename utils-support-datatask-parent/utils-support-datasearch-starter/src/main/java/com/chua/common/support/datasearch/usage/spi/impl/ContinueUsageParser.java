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
 * Continue usage parser.
 *
 * <p>Continue is an open-source AI coding assistant supporting multiple LLM backends.
 * Token usage depends on the selected backend and is tracked server-side.
 * No local usage data is available in {@code ~/.continue/}.</p>
 *
 * <p>To get Continue usage: check the billing dashboard of your configured
 * LLM provider (OpenAI, Anthropic, Ollama, etc.).</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("continue")
public class ContinueUsageParser extends BaseUsageParser {

    private static final Path CONTINUE_DIR = Path.of(System.getProperty("user.home"), ".continue");

    @Override
    public String name() {
        return "continue";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(CONTINUE_DIR)) {
            log.debug("[continue] Continue not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(CONTINUE_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[continue] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[continue] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[continue] No JSONL files found in ~/.continue/");
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
                                    log.debug("[continue] Found usage in Continue JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[continue] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
