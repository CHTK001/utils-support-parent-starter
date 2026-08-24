package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JoyCode usage parser.
 *
 * <p>JoyCode is a Chinese AI coding IDE (by JD.com) using an OpenAI-compatible
 * API (e.g. JoyAI-Code-1.5, DeepSeek-V4-Pro). Real per-request usage is tracked
 * server-side by the JoyCode platform — no billed tokens are stored locally.</p>
 *
 * <p>The only local token data lives in editor logs under
 * {@code %USERPROFILE%\AppData\Roaming\JoyCode\logs}: {@code [NonMessageTokens]}
 * lines carry a client-side estimate of system prompt + tool definition tokens,
 * computed before each request. These are a lower bound on real input usage —
 * every record is flagged {@code estimated = true} so downstream aggregation
 * can exclude them from billing totals.</p>
 *
 * <p>{@code [OpenAI Inner]} model-selection events contain no token data and
 * are intentionally ignored.</p>
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

    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");

    private static final String PROVIDER_JOYCODE = "joycode-platform";
    private static final String MODEL_CONTEXT_ESTIMATE = "context-estimate";
    private static final String FINISH_CONTEXT_ESTIMATE = "context-window-estimate";

    /**
     * Returns the SPI name for JoyCode.
     *
     * @return {@code "joycode"}
     */
    @Override
    /**
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    public String name() {
        return "joycode";
    }

    /**
     * Parses all JoyCode log files and extracts context-size estimates.
     *
     * @return list of estimated AiUsage records (estimated = true)
     */
    private List<AiUsage> parseAll() {
        if (!Files.isDirectory(JOYCODE_LOG_DIR)) {
            log.debug("[joycode] log dir not found: {}", JOYCODE_LOG_DIR);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(JOYCODE_LOG_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("JoyCode.log"))
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
        log.info("[joycode] scanned {} log files, extracted {} estimate records",
                fileCount.get(), result.size());
        return result;
    }

    /**
     * Reads one JoyCode log file line by line, appending estimates.
     *
     * @param file   path to the log file
     * @param result accumulator list for parsed records
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
     * Extracts a context-size estimate from one log line if present.
     *
     * @param line   the log line to inspect
     * @param result accumulator list for parsed records
     */
    private void parseLine(String line, List<AiUsage> result) {
        Matcher nonMsgMatcher = NON_MSG_TOKENS.matcher(line);
        if (!nonMsgMatcher.find()) {
            return;
        }
        try {
            int totalTokens = Integer.parseInt(nonMsgMatcher.group(1));
            int systemPromptTokens = Integer.parseInt(nonMsgMatcher.group(2));
            int toolTokens = Integer.parseInt(nonMsgMatcher.group(3));
            long startTime = extractDayStart(line);
            if (totalTokens > 0 && startTime > 0) {
                result.add(AiUsage.builder()
                        .provider(PROVIDER_JOYCODE)
                        .model(MODEL_CONTEXT_ESTIMATE)
                        .totalTokens(totalTokens)
                        .inputTokens(systemPromptTokens + toolTokens)
                        .startTime(startTime)
                        .estimated(true)
                        .finishReason(FINISH_CONTEXT_ESTIMATE)
                        .build());
            }
        } catch (NumberFormatException e) {
            log.debug("[joycode] parse NonMessageTokens failed: {}", e.getMessage());
        }
    }

    /**
     * Extracts the leading yyyy-MM-dd timestamp of a log line.
     *
     * @param line the log line
     * @return epoch millis at day start, or 0L when absent or malformed
     */
    private long extractDayStart(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        if (m.find()) {
            return parseDayStartToMillis(m.group(1));
        }
        return 0L;
    }
}
