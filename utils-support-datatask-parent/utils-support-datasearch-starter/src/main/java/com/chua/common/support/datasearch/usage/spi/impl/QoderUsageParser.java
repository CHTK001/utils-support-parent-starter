package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
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
import java.util.List;
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
*       "original_credits": 0.03285
*     }
*   }
* }
* }</pre>03285,
*       "original_credits": 0.03285
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
* @author CH
* @since 4.0.0.42
 */
@Spi("qoder")
public class QoderUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".qoder", "projects");

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
        List<AiUsage> result = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        try (var stream = Files.walk(PROJECTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        fileCount.incrementAndGet();
                        try {
                            parseJsonlFile(file, result);
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
    * 读取 one transcript 文件 线 by 线, extracting assistant usage.
    *
    * @param file   路径 转为 the 会话 JSONL 文件
    * @param result accumulator 列表 for 解析 records
    * @throws IOException if the 文件 cannot be 读取
    */
    private void parseJsonlFile(Path file, List<AiUsage> result) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    parseNode(Json.parse(line)).ifPresent(result::add);
                } catch (Exception e) {
                    log.debug("[qoder] parse failed {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }

    /**
    * 将单条转录 JSON 行转换为 AiUsage 记录；仅处理带真实 token 的 assistant 响应。
    *
    * @param node 解析后的 JSON 行
    * @return 用量记录；非目标行时 empty
    */
    private java.util.Optional<AiUsage> parseNode(JsonNode node) {
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
        long startTime = parseInstantToMillis(node.get("timestamp").toStringValue());
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_QODER)
                .model(firstNonBlank(message.get("model").toStringValue(), "unknown"))
                .requestId(message.get("id").toStringValue())
                .startTime(startTime > 0 ? startTime : null)
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
