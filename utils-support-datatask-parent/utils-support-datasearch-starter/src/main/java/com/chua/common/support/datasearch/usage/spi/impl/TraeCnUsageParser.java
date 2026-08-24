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
 * Trae CN (ByteDance AI coding assistant, Chinese version) usage parser.
 *
 * <p>Trae CN stores usage data on ByteDance domestic server side.
 * No local token usage files are available in {@code ~/.trae-cn/}.</p>
 *
 * <p>To get Trae CN usage: check the Trae CN web dashboard
 * or contact ByteDance support for usage reports.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("trae-cn")
public class TraeCnUsageParser extends BaseUsageParser {

    private static final Path TRAE_CN_DIR = Path.of(System.getProperty("user.home"), ".trae-cn");

    @Override
    public String name() {
        return "trae-cn";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(TRAE_CN_DIR)) {
            log.debug("[trae-cn] Trae CN not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(TRAE_CN_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[trae-cn] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[trae-cn] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[trae-cn] No JSONL files found in ~/.trae-cn/");
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
                                    log.debug("[trae-cn] Found usage in Trae-CN JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[trae-cn] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
