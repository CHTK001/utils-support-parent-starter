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
 * Augment Code usage parser.
 *
 * <p>Augment stores token usage on the Augment AI server side.
 * No local usage data is available in {@code ~/.augment/}.</p>
 *
 * <p>To get Augment usage: check the Augment dashboard at
 * https://augment.dev/dashboard or contact Augment support.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("augment")
public class AugmentUsageParser extends BaseUsageParser {

    private static final Path AUGMENT_DIR = Path.of(System.getProperty("user.home"), ".augment");

    @Override
    public String name() {
        return "augment";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(AUGMENT_DIR)) {
            log.debug("[augment] Augment not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(AUGMENT_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[augment] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[augment] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[augment] No JSONL files found in ~/.augment/");
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
                                    log.debug("[augment] Found usage in Augment JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[augment] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
