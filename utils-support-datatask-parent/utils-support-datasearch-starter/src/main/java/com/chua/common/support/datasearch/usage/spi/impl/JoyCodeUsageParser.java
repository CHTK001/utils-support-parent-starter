package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JoyCode usage parser.
 *
 * <p>JoyCode is a Chinese AI coding IDE (by JD.com). It uses an OpenAI-compatible
 * API (e.g. JoyAI-Code-1.5, DeepSeek-V4-Pro). Token usage is tracked server-side
 * by the JoyCode platform — no per-request tokens are stored locally.</p>
 *
 * <p>This parser extracts available token data from JoyCode's local log files:
 * <ul>
 *   <li>{@code [NonMessageTokens]} — system prompt + tool definition token counts</li>
 *   <li>{@code [SystemPromptBaseBreakdown]} — per-segment token breakdown</li>
 *   <li>{@code [OpenAI Inner]} — model selection events</li>
 * </ul>
 * These represent context-window usage at session start, not per-request usage.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("joycode")
public class JoyCodeUsageParser extends BaseUsageParser {

    private static final Path JOYCODE_LOG_DIR = Path.of(
            System.getProperty("user.home"), "AppData", "Roaming", "JoyCode", "logs");

    private static final Pattern NON_MSG_TOKENS = Pattern.compile(
            "\\[NonMessageTokens\\].*?total=(\\d+),.*?systemPrompt=(\\d+).*?tools=(\\d+)");

    private static final Pattern MODEL_EVENT = Pattern.compile(
            "\\[OpenAI\\s+Inner\\].*?\"actualChatApiModel\":\"([^\"]+)\"");

    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");

    /**
     * Returns the SPI name for JoyCode.
     *
     * @return {@code "joycode"}
     */
    @Override
    public String name() {
        return "joycode";
    }

    /**
     * Parses all JoyCode log files and extracts available token data.
     *
     * @return list of AiUsage records extracted from JoyCode logs
     */
    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(JOYCODE_LOG_DIR)) {
            log.debug("[joycode] log dir not found: {}", JOYCODE_LOG_DIR);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(JOYCODE_LOG_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("JoyCode.log"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseLogFile(file, result);
                        } catch (IOException e) {
                            log.debug("[joycode] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[joycode] walk failed: {}", e.getMessage(), e);
        }
        log.info("[joycode] scanned {} log files, extracted {} records",
                fileCount.get(), result.size());
        return result;
    }

    /**
     * Reads a single JoyCode log file line by line and parses each line.
     *
     * @param file path to the log file
     * @param result accumulator list to append parsed records
     * @throws IOException if the file cannot be read
     */
    private void parseLogFile(Path file, List<AiUsage> result) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, result);
            }
        }
    }

    /**
     * Parses a single log line looking for token usage markers.
     *
     * <p>Two marker patterns are recognized:
     * <ul>
     *   <li>{@code [NonMessageTokens]} — extracts total/systemPrompt/tools token counts</li>
     *   <li>{@code [OpenAI Inner]} — extracts model selection events</li>
     * </ul>
     *
     * @param line the log line to parse
     * @param result accumulator list to append parsed records
     */
    private void parseLine(String line, List<AiUsage> result) {
        Matcher nonMsgMatcher = NON_MSG_TOKENS.matcher(line);
        if (nonMsgMatcher.find()) {
            try {
                int totalTokens = Integer.parseInt(nonMsgMatcher.group(1));
                int systemPromptTokens = Integer.parseInt(nonMsgMatcher.group(2));
                int toolTokens = Integer.parseInt(nonMsgMatcher.group(3));
                String dateStr = extractDate(line);
                long startTime = parseDateToMillis(dateStr);
                if (totalTokens > 0 && startTime > 0) {
                    result.add(AiUsage.builder()
                            .provider("joycode-platform")
                            .model("context-window")
                            .totalTokens(totalTokens)
                            .inputTokens(systemPromptTokens + toolTokens)
                            .startTime(startTime)
                            .finishReason("system-prompt-session")
                            .build());
                }
            } catch (NumberFormatException e) {
                log.debug("[joycode] parse NonMessageTokens failed: {}", e.getMessage());
            }
            return;
        }
        Matcher modelMatcher = MODEL_EVENT.matcher(line);
        if (modelMatcher.find()) {
            try {
                String model = modelMatcher.group(1);
                String dateStr = extractDate(line);
                long startTime = parseDateToMillis(dateStr);
                if (startTime > 0) {
                    result.add(AiUsage.builder()
                            .provider("joycode-platform")
                            .model(model)
                            .startTime(startTime)
                            .finishReason("model-selection")
                            .build());
                }
            } catch (Exception e) {
                log.debug("[joycode] parse model event failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Extracts the date portion (yyyy-MM-dd) from a log line.
     *
     * @param line the log line
     * @return the date string, or empty string if not found
     */
    private String extractDate(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /**
     * Converts a yyyy-MM-dd date string to epoch milliseconds.
     *
     * @param dateStr date string in yyyy-MM-dd format
     * @return epoch milliseconds, or 0L if parsing fails
     */
    private long parseDateToMillis(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return 0L;
        }
        try {
            return java.time.LocalDate.parse(dateStr)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }
}
