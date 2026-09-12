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
   * joy编码 usage parser.
 *
 * <p>JoyCode is a Chinese AI coding IDE (by JD.com) using an OpenAI-compatible
   * API (e.g. joyAI-编码-1.5, deepseek-V4-Pro). Real per-请求 usage 是否 tracked
   * 服务端-side by the joy编码 platform — no 账单 令牌 are 存储 本地.</p>
 *
 * <p>The only local token data lives in editor logs under
 * {@code %USERPROFILE%\AppData\Roaming\JoyCode\logs}: {@code [NonMessageTokens]}
   * 线 carry a 客户端-side estimate 的 系统 提示符 + tool definition 令牌,
   * computed 之前 each 请求. These are a 降低 bound on real 输入 usage —
   * every record 是否 flagged {@code estimated = true} so downstream aggregation
   * 能否 exclude them 从 账单 totals.</p>
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

    private static final String PROVIDER_JOYCODE = "joycode-platform"; // 提供者joycode
    private static final String MODEL_CONTEXT_ESTIMATE = "context-estimate"; // 模型上下文estimate
    private static final String FINISH_CONTEXT_ESTIMATE = "context-window-estimate"; // 饰面上下文estimate

    /**
      * 返回 the SPI 名称 for joy编码.
     *
     * @return {@code "joycode"}
     */
    /**
      * 响应式流式入口：订阅时才执行装载，配合 限制rate/取 可控制内存水位。
     */
    @Override
    public reactor.core.publisher.Flux<AiUsage> streamAll() {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @Override
    public String name() {
        return "joycode";
    }

    /**
      * 解析 全部 joy编码 日志 文件 和 extracts 上下文-大小 estimates.
     *
     * @return list 的 estimated aiusage records (estimated = true)
     */
    @Override protected List<AiUsage> parseAll() {
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
      * 读取 one joy编码 日志 文件 线 by 线, 追加 estimates.
     *
     * @param file   路径 转为 the 日志 文件
     * @param result accumulator 列表 for 解析 records
     * @throws IOException if the 文件 cannot be 读取
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
      * Extracts a 上下文-大小 estimate 从 one 日志 线 if present.
     *
     * @param line   the 日志 线 转为 inspect
     * @param result accumulator 列表 for 解析 records
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
      * Extracts the 铅 yyyy-MM-dd 时间戳 的 a 日志 线.
     *
     * @param line the 日志 线
     * @return epoch millis at day 启动, 或 0L When.js.js absent 或 malformed
     */
    private long extractDayStart(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        if (m.find()) {
            return parseDayStartToMillis(m.group(1));
        }
        return 0L;
    }
}
