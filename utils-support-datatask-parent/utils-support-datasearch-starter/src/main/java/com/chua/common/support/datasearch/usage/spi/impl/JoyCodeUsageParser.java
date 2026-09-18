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
