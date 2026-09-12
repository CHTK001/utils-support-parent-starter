package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LM Studio local inference usage parser.
 *
 * <p>LM Studio's embedded inference server writes request logs under
 * {@code ~/.lmstudio/server-logs/**/*.log} (override via {@code LM_STUDIO_HOME}).
 * Each request completion appends a JSONL line carrying an OpenAI-style
 * {@code usage} block:</p>
 *
 * <pre>{@code
 * {
 *   "model": "meta-llama/...",
 *   "usage": {
 *     "prompt_tokens": 512,
 *     "completion_tokens": 128,
 *     "total_tokens": 640
 *   },
 *   "timestamp": 1730000000000
 * }
 * </pre>
 *
 * <p>Local inference has no billed cost — records carry token counts only
 * (cost fields are surfaced when the log happens to carry them). Non-JSON
 * log lines are skipped.</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("lmstudio")
public class LmStudioUsageParser extends BaseUsageParser {

    private static final String PROVIDER_LMSTUDIO = "lmstudio";

    private static final Path LMSTUDIO_HOME = resolveHome();

    private static final Path SERVER_LOGS = LMSTUDIO_HOME.resolve("server-logs");

    /**
     * 解析 LM Studio home 目录（支持环境变量覆盖）。
     *
     * @return home 目录
     */
    private static Path resolveHome() {
        String env = System.getenv("TOKENTRACKER_LMSTUDIO_HOME");
        if (env == null || env.isBlank()) {
            env = System.getenv("LM_STUDIO_HOME");
        }
        return (env != null && !env.isBlank())
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".lmstudio");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "lmstudio"}
     */
    @Override
    public String name() {
        return PROVIDER_LMSTUDIO;
    }

    /**
     * 流式解析全部服务器日志中的推理用量记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listLogFiles();
        if (files.isEmpty()) {
            log.debug("[lmstudio] no server-logs under {}", SERVER_LOGS);
            return Flux.empty();
        }
        log.info("[lmstudio] scanning {} log files", files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(file -> Flux.fromIterable(parseFile(file)), 4);
    }

    /**
     * 递归枚举 {@code server-logs/**/*.log} 文件。
     *
     * @return 日志文件列表
     */
    private List<Path> listLogFiles() {
        if (!Files.isDirectory(SERVER_LOGS)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(SERVER_LOGS)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[lmstudio] walk {} failed: {}", SERVER_LOGS, e.getMessage());
        }
        return files;
    }

    /**
     * 解析单个日志文件：逐行查找携带 usage 块的 JSON 行。
     *
     * @param file 日志文件
     * @return 用量记录列表
     */
    private List<AiUsage> parseFile(Path file) {
        List<AiUsage> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.contains("\"usage\"")) {
                    continue;
                }
                parseLineSafe(line, result);
            }
        } catch (IOException e) {
            log.debug("[lmstudio] read {} failed: {}", file.getFileName(), e.getMessage());
        }
        return result;
    }

    private void parseLineSafe(String line, List<AiUsage> result) {
        try {
            addUsageRecord(Json.parse(line), result);
        } catch (Exception e) {
            log.debug("[lmstudio] line parse failed: {}", e.getMessage());
        }
    }

    /**
     * 从 JSON 行提取 usage 块并累加一条记录。
     *
     * @param node JSON 行
     * @param result 结果累加器
     */
    private void addUsageRecord(JsonNode node, List<AiUsage> result) {
        JsonNode usage = node.get("usage");
        if (usage.isMissingValue()) {
            return;
        }
        int prompt = usage.get("prompt_tokens").toIntValue(
                usage.get("promptTokens").toIntValue(
                        usage.get("input_tokens").toIntValue(
                                usage.get("inputTokens").toIntValue(-1))));
        int completion = usage.get("completion_tokens").toIntValue(
                usage.get("completionTokens").toIntValue(
                        usage.get("output_tokens").toIntValue(
                                usage.get("outputTokens").toIntValue(-1))));
        if (prompt <= 0 && completion <= 0) {
            return;
        }
        int total = usage.get("total_tokens").toIntValue(
                usage.get("totalTokens").toIntValue(Math.max(0, prompt) + Math.max(0, completion)));
        long startTime = node.get("timestamp").toLongValue(0L);
        if (startTime > 0 && startTime < 10_000_000_000L) {
            startTime = startTime * 1000L;
        }
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_LMSTUDIO)
                .model(firstNonBlank(node.get("model").toStringValue(), "lmstudio-local"))
                .inputTokens(Math.max(0, prompt))
                .outputTokens(Math.max(0, completion))
                .totalTokens(total > 0 ? total : Math.max(0, prompt) + Math.max(0, completion))
                .startTime(startTime > 0 ? startTime : null);
        double cost = usage.get("cost_usd").toDoubleValue(0.0d);
        if (cost > 0) {
            builder.totalCost(BigDecimal.valueOf(cost)).currency("USD");
        }
        result.add(builder.build());
    }
}
