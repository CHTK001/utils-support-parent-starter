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
 * Roo Code usage parser.
 *
 * <p>Roo Code delegates to external LLM providers (Claude, GPT, etc.).
 * Token usage is tracked by the upstream provider, not stored locally.</p>
 *
 * <p>To get Roo Code usage: check the billing dashboard of your configured
 * LLM provider (Anthropic, OpenAI, etc.).</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("roocode")
public class RooCodeUsageParser extends BaseUsageParser {

    private static final Path ROO_DIR = Path.of(System.getProperty("user.home"), ".roo");

    @Override
    public String name() {
        return "roocode";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(ROO_DIR)) {
            log.debug("[roocode] Roo Code not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(ROO_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[roocode] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[roocode] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[roocode] No JSONL files found in ~/.roo/");
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
                                    log.debug("[roocode] Found usage in Roo JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[roocode] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
