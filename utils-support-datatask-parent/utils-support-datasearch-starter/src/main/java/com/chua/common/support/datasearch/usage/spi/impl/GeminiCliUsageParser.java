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
 * Gemini CLI usage parser.
 *
 * <p>Gemini CLI stores chat sessions in {@code ~/.gemini/tmp/project/chats/*.jsonl}.
 * These files contain conversation history but NOT token usage metadata
 * (input_tokens, output_tokens are not persisted locally by Gemini CLI).</p>
 *
 * <p>To get Gemini usage: check Google Cloud Console at
 * https://console.cloud.google.com/billing or call the
 * Generative Language API usage endpoints with your API key.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("gemini-cli")
public class GeminiCliUsageParser extends BaseUsageParser {

    private static final Path GEMINI_TMP = Path.of(
            System.getProperty("user.home"), ".gemini", "tmp");

    @Override
    public String name() {
        return "gemini-cli";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(GEMINI_TMP)) {
            log.debug("[gemini-cli] Gemini CLI not installed or no temp data");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger usageFound = new AtomicInteger(0);
        try (var stream = Files.walk(GEMINI_TMP)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            usageFound.addAndGet(parseJsonlFile(file));
                        } catch (IOException e) {
                            log.debug("[gemini-cli] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[gemini-cli] walk failed: {}", e.getMessage(), e);
        }
        log.info("[gemini-cli] scanned {} JSONL files, found usage in {} files",
                fileCount.get(), usageFound.get());
        return List.of();
    }

    private int parseJsonlFile(Path file) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = Json.parse(line);
                    // Gemini stores usage in step_finish parts
                    String type = node.get("type").toStringValue();
                    JsonNode part = node.get("part");
                    if ("step_finish".equals(type) && !part.isMissingValue()) {
                        JsonNode tokens = part.get("tokens");
                        if (!tokens.isMissingValue()) {
                            int inputTokens = tokens.get("input").toIntValue(-1);
                            int outputTokens = tokens.get("output").toIntValue(-1);
                            if (inputTokens > 0 || outputTokens > 0) {
                                count++;
                                log.debug("[gemini-cli] Found usage in {}: in={}, out={}",
                                        file.getFileName(), inputTokens, outputTokens);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[gemini-cli] parse failed: {}", e.getMessage());
                }
            }
        }
        return count;
    }
}
