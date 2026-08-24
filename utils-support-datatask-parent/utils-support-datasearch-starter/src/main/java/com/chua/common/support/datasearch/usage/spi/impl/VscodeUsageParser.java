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
 * VSCode GitHub Copilot usage parser.
 *
 * <p>Copilot usage data is managed by GitHub server side.
 * Local {@code ~/.vscode-server/.../github.copilot-chat/} only stores
 * agent configuration, not token usage records.</p>
 *
 * <p>To get Copilot usage:
 * <ul>
 *   <li>Individual: https://github.com/settings/copilot</li>
 *   <li>Enterprise: GitHub Admin portal → Copilot settings</li>
 * </ul></p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vscode")
public class VscodeUsageParser extends BaseUsageParser {

    private static final Path COPILOT_DIR = Path.of(
            System.getProperty("user.home"), "AppData", "Roaming", "Code",
            "User", "globalStorage", "github.copilot-chat");

    @Override
    public String name() {
        return "vscode";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(COPILOT_DIR)) {
            log.debug("[vscode] VSCode Copilot not installed");
            return List.of();
        }
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(COPILOT_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file);
                        } catch (IOException e) {
                            log.debug("[vscode] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[vscode] walk failed: {}", e.getMessage(), e);
        }
        if (fileCount.get() == 0) {
            log.debug("[vscode] No JSONL files found in Copilot storage");
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
                                    log.debug("[vscode] Found usage in Copilot JSONL: in={}, out={}", inputTokens, outputTokens);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[vscode] parse failed: {}", e.getMessage());
                }
            }
        }
    }
}
