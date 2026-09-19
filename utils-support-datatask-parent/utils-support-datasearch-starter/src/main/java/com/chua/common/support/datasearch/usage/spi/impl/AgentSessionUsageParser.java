package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Craft Agents 与 Prime Agent 用量解析器共享基类。
 *
 * <p>Craft Agents（Craft Agents 桌面端）将每个会话持久化为
 * {@code ~/.craft-agent/sessions/<session-id>/session.jsonl}；
 * Prime Agent（PrimeIntellect）使用相同的元数据信封，
 * 但存放于扁平目录 {@code ~/.prime/agent/sessions/<session-id>.jsonl}。
 * 两者均通过 per-session 头携带真实模型，并在工作区侧保留用量信封：</p>
 *
 * <pre>{@code
 * { "type": "usage", "model": "claude-sonnet-4-5",
 *   "usage": { "inputTokens": 1200, "outputTokens": 800,
 *              "cacheReadInputTokens": 400, "costUSD": 0.02 },
 *   "timestamp": 1730000000000 }
 * </pre>
 *
 * <p>本基类抽象出「会话根目录布局 + 信封解析」，子类只需指定 home 根与
 * 会话文件枚举策略（扁平 vs 嵌套），模型名缺失时由子类决定是否
 * 从会话头提取。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public abstract class AgentSessionUsageParser extends BaseUsageParser {

    /**
     * 会话根目录（{@code <home>/sessions}）。子类负责提供。
     *
     * @return 会话根目录
     */
    protected abstract Path sessionRoot();

    /**
     * 会话转录文件扩展名。
     *
     * @return 扩展名（默认 {@code .jsonl}）
     */
    protected String transcriptExtension() {
        return ".jsonl";
    }

    /**
     * 响应式流式入口：惰性扫描会话根并逐行解析用量信封。
     *
     * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTranscripts();
        if (files.isEmpty()) {
            log.debug("[{}] no transcripts under {}", name(), sessionRoot());
            return Flux.empty();
        }
        log.info("[{}] streaming {} session files", name(), files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .concatMap(this::streamTranscript, 4);
    }

    /**
     * 流Transcript。
     *
     * @param file 文件，不允许为 null
     * @return Flux 对象
     */
    private Flux<AiUsage> streamTranscript(Path file) {
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(this::parseLineSafe)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    /**
     * 枚举会话转录文件；支持嵌套子目录与扁平文件两种布局。
     *
     * @return 转录文件列表
     */
    private List<Path> listTranscripts() {
        Path root = sessionRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(transcriptExtension()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[{}] walk {} failed: {}", name(), root, e.getMessage());
        }
        return files;
    }

    /**
     * 解析LineSafe。
     *
     * @param line 方法入参 line
     * @return 可选结果，不存在时为 Optional.empty()
     */
    private Optional<AiUsage> parseLineSafe(String line) {
        try {
            return parseLine(line);
        } catch (Exception e) {
            log.debug("[{}] parse failed: {}", name(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析一行用量信封。子类可覆写以定制信封 schema。
     *
     * @param line JSONL 行
     * @return 用量记录或 empty
     */
    protected Optional<AiUsage> parseLine(String line) {
        JsonNode node = Json.parse(line);
        JsonNode usage = node.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        int inputTokens = usage.get("inputTokens").toIntValue(-1);
        int outputTokens = usage.get("outputTokens").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        int cacheRead = usage.get("cacheReadInputTokens").toIntValue(0);
        int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
        double costUsd = usage.get("costUSD").toDoubleValue(0.0d);
        long startTime = node.get("timestamp").toLongValue(0L);
        String sessionId = node.get("sessionId").toStringValue();

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(name())
                .model(firstNonBlank(node.get("model").toStringValue(), defaultModelLabel()))
                .requestId(firstNonBlank(sessionId, "unknown"))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .startTime(startTime > 0 ? startTime : null);
        if (costUsd > 0) {
            builder.totalCost(BigDecimal.valueOf(costUsd)).currency("USD");
        }
        return Optional.of(builder.build());
    }

    /**
     * 默认模型标签（信封缺失模型字段时使用）。
     *
     * @return 默认模型标签
     */
    protected String defaultModelLabel() {
        return name() + "-unknown";
    }
}
