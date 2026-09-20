package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.datasearch.usage.spi.QoderModelCatalog;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Qoder CLI usage parser.
 *
 * <p>Qoder is Alibaba's agentic coding CLI. Authenticated runs persist
 * Claude-编码-style 会话 transcripts under
 * {@code ~/.qoder/projects/<project>/<sessionId>.jsonl}. Each
 * {@code type=assistant} line carries an API usage block:</p>
 *
 * <pre>{@code
 * {
 *   "type": "assistant",
 *   "timestamp": "2026-08-24T04:10:54.011Z",
 *   "sessionId": "...",
 *   "message": {
 *     "id": "chatcmpl-...",
 *     "model": "lite",
 *     "stop_reason": "end_turn",
 *     "usage": {
 *       "input_tokens": 0,
 *       "output_tokens": 0,
 *       "credits": 0.03285,
 *       "original_credits": 0.03285,
 *       "request_id": "74712cff-e1e3-45a8-9f1b-b29e5bcd773e"
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Qoder bills by platform <b>credits</b>, not raw tokens — its proxy reports
 * 全部 令牌 数量 as zero. This parser therefore 映射 {@code credits} 转为
 * {@code totalCost} with currency {@code "CREDITS"} and leaves token fields
 * unset rather than emitting misleading zeros.</p>
 *
 * <p>转录本身不带耗时，但 {@code usage.request_id} 与运行日志
 * {@code ~/.qoder/logs/sessions/<project>/<sessionId>/segments/*.jsonl} 里的
 * {@code model.request.started} / {@code model.response.completed} 事件严格一一对应
 * （本机 37682 条用量全部带 request_id），因此按该 标识 关联出请求开始时间与单次耗时。
 * 转录行的 {@code timestamp} 实测比完成事件晚约 40ms，即它是响应<b>完成</b>时刻，
 * 只用作无法关联到日志时的兜底开始时间。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qoder")
public class QoderUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".qoder", "projects");

    /**
     * CLI 运行日志目录：逐请求的开始/完成事件在这里，转录本身不带耗时
     */
    private static final Path LOG_SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".qoder", "logs", "sessions");

    /**
     * 运行日志中的请求开始事件类型
     */
    private static final String EVENT_REQUEST_STARTED = "model.request.started";

    /**
     * 运行日志中的请求完成事件类型
     */
    private static final String EVENT_RESPONSE_COMPLETED = "model.response.completed";

    private static final String PROVIDER_QODER = "qoder"; // 提供者qoder
    private static final String CURRENCY_CREDITS = "CREDITS"; // 货币抵免

    /**
     * 返回 the SPI 名称 for Qoder.
     *
     * @return {@code "qoder"}
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
        return "qoder";
    }

    /**
     * 解析 全部 Qoder 会话 transcripts 和 extracts 账单 抵免.
     *
     * @return list 的 aiusage records, one per assistant 响应
     */
    @Override protected List<AiUsage> parseAll() {
        if (!Files.isDirectory(PROJECTS_DIR)) {
            log.debug("[qoder] projects dir not found: {} (Qoder CLI not installed)", PROJECTS_DIR);
            return List.of();
        }
        Map<String, long[]> timings = loadRequestTimings();
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(PROJECTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file, result, timings);
                        } catch (IOException e) {
                            log.debug("[qoder] read failed {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[qoder] walk failed: {}", e.getMessage(), e);
        }
        log.info("[qoder] scanned {} session files, parsed {} records",
                fileCount.get(), result.size());
        return result;
    }

    /**
     * 从 CLI 运行日志建立 请求号 -&gt; {开始毫秒, 完成毫秒} 索引。
     *
     * <p>转录里只有账单没有时钟，而日志里的 {@code model.request.started} 与
     * {@code model.response.completed} 按 {@code request_id} 成对出现，是唯一的耗时来源。
     * 同一 标识 若出现多对事件，取最早的开始与最晚的完成。</p>
     *
     * @return 请求号到时间线的映射；日志目录缺失时为空映射
     */
    private Map<String, long[]> loadRequestTimings() {
        Map<String, long[]> timings = new HashMap<>();
        if (!Files.isDirectory(LOG_SESSIONS_DIR)) {
            log.info("[qoder] 运行日志目录不存在, 耗时字段无法补齐: {}", LOG_SESSIONS_DIR);
            return timings;
        }
        try (var stream = Files.walk(LOG_SESSIONS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> readTimingFile(file, timings));
        } catch (IOException e) {
            log.warn("[qoder] timing walk failed: {}", e.getMessage(), e);
        }
        log.info("[qoder] 载入 {} 个请求的运行时间线", timings.size());
        return timings;
    }

    /**
     * 读取单个运行日志片段，把起止时刻并入索引。
     *
     * @param file    日志片段文件
     * @param timings 请求号时间线索引（就地更新）
     */
    private void readTimingFile(Path file, Map<String, long[]> timings) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean started = line.contains(EVENT_REQUEST_STARTED);
                if (!started && !line.contains(EVENT_RESPONSE_COMPLETED)) {
                    continue;
                }
                appendTiming(Json.parse(line), timings, started);
            }
        } catch (Exception e) {
            log.debug("[qoder] timing read failed {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * 并入一条时间线事件。
     *
     * @param node    事件 JSON
     * @param timings 请求号时间线索引（就地更新）
     * @param started true 表示请求开始事件，否则为完成事件
     */
    private static void appendTiming(JsonNode node, Map<String, long[]> timings, boolean started) {
        String requestId = node.get("request_id").toStringValue();
        long millis = parseInstantToMillis(node.get("ts").toStringValue());
        if (requestId == null || requestId.isBlank() || millis <= 0) {
            return;
        }
        long[] span = timings.computeIfAbsent(requestId, key -> new long[2]);
        if (started && (span[0] == 0 || millis < span[0])) {
            span[0] = millis;
        }
        if (!started && millis > span[1]) {
            span[1] = millis;
        }
    }

    /**
     * 读取 one transcript 文件 线 by 线, extracting assistant usage.
     *
     * @param file    路径 转为 the 会话 JSONL 文件
     * @param result  accumulator 列表 for 解析 records
     * @param timings 请求号时间线索引
     * @throws IOException if the 文件 cannot be 读取
     */
    private void parseJsonlFile(Path file, List<AiUsage> result, Map<String, long[]> timings) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    parseNode(Json.parse(line), timings).ifPresent(result::add);
                } catch (Exception e) {
                    log.debug("[qoder] parse failed {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 将单条转录 JSON 行转换为 AiUsage 记录；仅处理带真实 token 的 assistant 响应。
     *
     * @param node    解析后的 JSON 行
     * @param timings 请求号时间线索引
     * @return 用量记录；非目标行时 empty
     */
    private java.util.Optional<AiUsage> parseNode(JsonNode node, Map<String, long[]> timings) {
        if (!"assistant".equals(node.get("type").toStringValue())) {
            return java.util.Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message.isMissingValue()) {
            return java.util.Optional.empty();
        }
        JsonNode usage = message.get("usage");
        if (usage.isMissingValue()) {
            return java.util.Optional.empty();
        }
        double credits = usage.get("credits").toDoubleValue(0.0d);
        int inputTokens = usage.get("input_tokens").toIntValue(0);
        int outputTokens = usage.get("output_tokens").toIntValue(0);
        if (credits <= 0 && inputTokens <= 0 && outputTokens <= 0) {
            return java.util.Optional.empty();
        }
        long[] span = timings.get(usage.get("request_id").toStringValue());
        long transcriptMillis = parseInstantToMillis(node.get("timestamp").toStringValue());
        long startTime = span != null && span[0] > 0 ? span[0] : transcriptMillis;
        Long duration = span == null || span[0] <= 0 || span[1] <= span[0]
                ? null : span[1] - span[0];
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_QODER)
                .model(QoderModelCatalog.resolve(
                        firstNonBlank(message.get("model").toStringValue(), "unknown")))
                .requestId(message.get("id").toStringValue())
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(duration)
                .finishReason(message.get("stop_reason").toStringValue());
        if (credits > 0) {
            builder.totalCost(BigDecimal.valueOf(credits))
                    .currency(CURRENCY_CREDITS);
        }
        if (inputTokens > 0 || outputTokens > 0) {
            builder.inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .cacheTokens(readCacheTokens(usage));
        }
        return java.util.Optional.of(builder.build());
    }

    /**
     * 读取缓存Tokens。
     *
     * @param usage 方法入参 usage
     * @return Integer 对象
     */
    private Integer readCacheTokens(JsonNode usage) {
        int cacheRead = usage.get("cache_read_input_tokens").toIntValue(0);
        int cacheWrite = usage.get("cache_creation_input_tokens").toIntValue(0);
        return cacheRead > 0 ? Integer.valueOf(cacheRead)
                : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null;
    }
}
