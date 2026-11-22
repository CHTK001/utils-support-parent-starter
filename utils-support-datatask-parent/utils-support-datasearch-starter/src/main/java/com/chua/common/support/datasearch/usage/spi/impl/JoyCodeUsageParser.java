package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JoyCode 用量解析器。
 *
 * <p>JoyCode 是京东（JD.com）推出的国产 AI 编程 IDE，使用 OpenAI 兼容 API
 * （例如 joyAI-code-1.5、deepseek-V4-Pro）。真实的单次请求用量由 JoyCode
 * 平台在服务端统计，本地不存储任何计费 token。</p>
 *
 * <p>本地唯一的 token 数据位于编辑器日志目录
 * {@code %USERPROFILE%\AppData\Roaming\JoyCode\logs}：{@code [NonMessageTokens]}
 * 行记录的是客户端侧估算出的系统提示词 + 工具定义 token，在每次请求之前算出。
 * 这些数值只是真实输入用量的下界——每条记录都被标记为
 * {@code estimated = true}，便于下游聚合时把它们从账单总量中排除。</p>
 *
 * <p>{@code [OpenAI Inner]} 的模型选择事件不含 token 数据，按设计予以忽略。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("joycode")
public class JoyCodeUsageParser extends BaseUsageParser {

    /**
     * 默认构造：解析当前用户主目录。
     */
    public JoyCodeUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public JoyCodeUsageParser(String userHomes) {
        super(userHomes);
    }

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
    @Override
    protected reactor.core.publisher.Flux<AiUsage> streamHome(Path home) {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.fromIterable(parseHome(home)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @Override
    public String name() {
        return "joycode";
    }

    /**
     * 解析 全部 joy编码 日志 文件 和 extracts 上下文-大小 estimates.
     *
     * @param home 解析根目录
     * @return list 的 estimated aiusage records (estimated = true)
     */
    @Override protected List<AiUsage> parseHome(Path home) {
        Path joycodeLogDir = home.resolve("AppData").resolve("Roaming")
                .resolve("JoyCode").resolve("logs");
        if (!Files.isDirectory(joycodeLogDir)) {
            log.debug("[joycode] log dir not found: {}", joycodeLogDir);
            return List.of();
        }
        List<AiUsage> result = Wraps.list();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(joycodeLogDir)) {
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
