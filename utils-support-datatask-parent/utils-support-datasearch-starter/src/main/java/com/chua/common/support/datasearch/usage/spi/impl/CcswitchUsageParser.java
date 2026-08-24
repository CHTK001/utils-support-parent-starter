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
 * CC Switch usage parser.
 *
 * <p>CC Switch is a CLI tool for switching between Claude Code providers.
 * It delegates API calls to underlying providers (Anthropic, OpenAI, etc.).
 * Token usage is tracked by the provider, not stored locally by CC Switch.</p>
 *
 * <p>To get CC Switch usage: check the billing dashboard of your configured
 * underlying provider.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ccswitch")
public class CcswitchUsageParser extends BaseUsageParser {

    private static final Path CCSWITCH_DIR = Path.of(System.getProperty("user.home"), ".ccswitch");

    @Override
    public String name() {
        return "ccswitch";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(CCSWITCH_DIR)) {
            log.debug("[ccswitch] CC Switch not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(CCSWITCH_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[ccswitch] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[ccswitch] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[ccswitch] No JSONL files found in ~/.ccswitch/");
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
                                    log.debug("[ccswitch] Found usage in CC Switch JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[ccswitch] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
