package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Codex-fork 本地用量解析器抽象基类。
 *
 * <p>Codex 及其 fork（acode、every-code）在 {@code <home>/sessions/**} 下写
 * rollout JSONL，每行包含 {@code payload.type=token_count}（或
 * {@code payload.msg.type=token_count}）事件，其 {@code info} 携带累计用量：</p>
 *
 * <pre>{@code
 * {
 *   "type": "response_item",
 *   "payload": {
 *     "type": "token_count",
 *     "info": {
 *       "last_token_usage": {...},
 *       "total_token_usage": {
 *         "input_tokens": 1200, "cached_input_tokens": 300,
 *         "cache_creation_input_tokens": 0, "output_tokens": 45
 *       }
 *     },
 *     "model": "gpt-5"
 *   },
 *   "timestamp": 1787546545
 * }
 * }</pre>
 *
 * <p>本基类以 {@code total_token_usage} 全量为口径（非增量差分），令牌口径对齐
 * TokenTracker {@code normalizeUsage}：{@code input_tokens} 含缓存，需拆出
 * 非缓存输入。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public abstract class AbstractCodexForkRolloutUsageParser extends BaseUsageParser {

    /**
     * 会话根目录（如 ~/.acode/sessions）。
     * @return 路径 对象
     */
    protected abstract Path sessionsRoot();

    /**
     * 提供者名称（如 acode / every-code）。
     * @return 结果字符串
     */
    protected abstract String providerName();

    /**
     * 默认模型名。
     * @return 结果字符串
     */
    protected abstract String defaultModel();

    /**
     * 返回 SPI 名称。
     *
     * @return 提供者名称
     */
    @Override
    public String name() {
        return providerName();
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listRolloutFiles();
        if (files.isEmpty()) {
            log.debug("[{}] no rollout jsonl under {}", providerName(), sessionsRoot());
            return Flux.empty();
        }
        log.info("[{}] scanning {} rollout files", providerName(), files.size());
        return Flux.fromIterable(files)
                .map(this::parseFile)
                .flatMapIterable(l -> l)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.debug("[{}] read failed: {}", providerName(), e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 解析单个 rollout 文件为用量记录。
     *
     * @param file 文件
     * @return 用量记录
     */
    private List<AiUsage> parseFile(Path file) {
        List<AiUsage> result = new ArrayList<>();
        String fileModel = null;
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.map(String::trim).toList()) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode node = Json.parse(line);
                    JsonNode payload = node.get("payload");
                    if (payload.isMissingValue()) {
                        continue;
                    }
                    // 收集行级模型（事件 payload 上的 model）
                    String model = payload.get("model").toStringValue();
                    if (model != null && !model.isBlank() && fileModel == null) {
                        fileModel = model;
                    }
                    // token_count 事件可能在 payload.type 或 payload.msg.type
                    JsonNode info = tokenInfo(payload);
                    if (info == null || info.isMissingValue()) {
                        continue;
                    }
                    AiUsage usage = toUsage(node, info, firstNonBlank(fileModel, defaultModel()));
                    if (usage != null) {
                        result.add(usage);
                    }
                } catch (Exception e) {
                    log.debug("[{}] line parse failed: {}", providerName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[{}] read failed {}: {}", providerName(), file.getFileName(), e.getMessage());
        }
        return result;
    }

    /**
     * 从 payload 中提取 token_count 的 info 节点。
     *
     * @param payload payload 节点
     * @return info 节点；不存在返回 null
     */
    private JsonNode tokenInfo(JsonNode payload) {
        JsonNode type = payload.get("type");
        if (!type.isMissingValue() && "token_count".equals(type.toStringValue())) {
            return payload.get("info");
        }
        JsonNode msg = payload.get("msg");
        if (!msg.isMissingValue() && "token_count".equals(msg.get("type").toStringValue())) {
            return msg.get("info");
        }
        return null;
    }

    /**
     * 将 token_count 事件转为用量记录（以 total_token_usage 全量为口径）。
     *
     * @param node        行节点
     * @param info        info 节点
     * @param fallbackModel 默认模型
     * @return 用量记录；全零或无时间戳返回 null
     */
    private AiUsage toUsage(JsonNode node, JsonNode info, String fallbackModel) {
        JsonNode total = info.get("total_token_usage");
        if (total == null || total.isMissingValue()) {
            total = info;
        }
        int input = total.get("input_tokens").toIntValue(0);
        int cached = total.get("cached_input_tokens").toIntValue(0);
        int cacheCreation = total.get("cache_creation_input_tokens").toIntValue(0);
        if (cacheCreation <= 0) {
            cacheCreation = total.get("cache_write_input_tokens").toIntValue(0);
        }
        int output = total.get("output_tokens").toIntValue(0);
        int reasoning = total.get("reasoning_output_tokens").toIntValue(0);
        int uncachedInput = Math.max(0, input - cached);
        int totalTokens = uncachedInput + cached + cacheCreation + output;
        if (totalTokens <= 0) {
            return null;
        }
        long timeSec = node.get("timestamp").toLongValue(0L);
        long timeMs = timeSec > 0 ? (timeSec > 1_000_000_000_000L ? timeSec : timeSec * 1000L) : 0L;
        if (timeMs <= 0) {
            return null;
        }
        Integer cacheTokens = cached > 0 ? Integer.valueOf(cached)
                : (cacheCreation > 0 ? Integer.valueOf(cacheCreation) : null);
        Integer reasoningTokens = reasoning > 0 ? Integer.valueOf(reasoning) : null;
        return AiUsage.builder()
                .provider(providerName())
                .model(fallbackModel)
                .requestId(node.get("event_id").toStringValue())
                .inputTokens(uncachedInput)
                .outputTokens(output)
                .totalTokens(totalTokens)
                .cacheTokens(cacheTokens)
                .reasoningTokens(reasoningTokens)
                .currency("USD")
                .estimated(false)
                .startTime(timeMs)
                .build();
    }

    /**
     * 枚举 sessions/archived_sessions 下全部 rollout JSONL。
     *
     * @return 文件列表
     */
    private List<Path> listRolloutFiles() {
        List<Path> files = new ArrayList<>();
        for (String sub : new String[]{"sessions", "archived_sessions"}) {
            Path root = sessionsRoot().getParent() == null ? sessionsRoot() : sessionsRoot();
            if (sub.startsWith("archived") && !Files.isDirectory(root.resolve(sub))) {
                continue;
            }
            Path dir = "sessions".equals(sub) ? root : root.resolve(sub);
            collect(dir, files);
        }
        return files.stream().sorted().toList();
    }

    /**
     * 收集。
     *
     * @param dir 目录，不允许为 null
     * @param files 方法入参 files
     */
    private void collect(Path dir, List<Path> files) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[{}] walk failed: {}", providerName(), e.getMessage());
        }
    }
}
