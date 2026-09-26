package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Trae CN（字节 AI  IDE）用量解析器 — 从事件缓存 JSONL 解析逐轮用量。
 *
 * <p>数据源为 {@code %USERPROFILE%\.trae-cn\hub_event_cache\TraeCode\**\*.jsonl}，
 * 每行一个事件且 {@code data} 是二次编码的 JSON 字符串。与用量相关的有三类：</p>
 * <ul>
 *   <li>{@code metadata} — 会话元信息，提供 {@code chat_start_time}（毫秒时间戳）、
 *       {@code session_id}、{@code message_id}</li>
 *   <li>{@code model_config} / {@code timing_events} — 提供本会话的模型名</li>
 *   <li>{@code token_usage} — 逐轮用量：{@code prompt_tokens} / {@code completion_tokens} /
 *       {@code reasoning_tokens} / {@code cache_read_input_tokens} /
 *       {@code cache_creation_input_tokens} / {@code total_tokens} / {@code max_tokens}</li>
 * </ul>
 *
 * <p>Token 口径：{@code prompt_tokens} 已含缓存命中部分，原样作为全量输入；
 * {@code cache_creation_input_tokens} 是写入量、不含在 {@code prompt_tokens} 之内，并入输入以免漏计；
 * {@code reasoning_tokens} 是 {@code completion_tokens} 的子集，净出后单列，
 * {@code totalTokens} 回算为 全量输入 + 净输出 —— 与 Codex 系解析器一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("trae-cn")
public class TraeCnUsageParser extends BaseUsageParser {

    /**
     * 事件缓存根目录
     */
    private static final Path CACHE_ROOT = Path.of(
            System.getProperty("user.home"), ".trae-cn", "hub_event_cache");

    /**
     * 用量事件名
     */
    private static final String EVENT_TOKEN_USAGE = "token_usage";

    /**
     * 元信息事件名
     */
    private static final String EVENT_METADATA = "metadata";

    /**
     * 模型配置事件名
     */
    private static final String EVENT_MODEL_CONFIG = "model_config";

    /**
     * 计时事件名（同样携带模型名）
     */
    private static final String EVENT_TIMING = "timing_events";

    /**
     * 默认模型名（事件缺失时兜底）
     */
    private static final String DEFAULT_MODEL = "trae-cn";

    @Override
    public String name() {
        return "trae-cn";
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listEventFiles();
        if (files.isEmpty()) {
            log.debug("[trae-cn] 事件缓存不存在: {}", CACHE_ROOT);
            return Flux.empty();
        }
        log.info("[trae-cn] scanning {} event files", files.size());
        return Flux.fromIterable(files)
                .map(this::parseFile)
                .flatMapIterable(list -> list)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.debug("[trae-cn] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 递归枚举事件缓存 JSONL。
     *
     * @return 文件列表
     */
    private List<Path> listEventFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(CACHE_ROOT)) {
            return files;
        }
        try (Stream<Path> stream = Files.walk(CACHE_ROOT)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[trae-cn] walk failed: {}", e.getMessage());
        }
        return files.stream().sorted().toList();
    }

    /**
     * 解析单个事件文件。
     *
     * @param file 文件
     * @return 用量记录列表
     */
    private List<AiUsage> parseFile(Path file) {
        List<AiUsage> result = new ArrayList<>();
        long startTime = 0L;
        String sessionId = null;
        String messageId = null;
        String model = null;
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.toList()) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode payload = readData(line);
                if (payload == null) {
                    continue;
                }
                String type = payload.get("__type").toStringValue();
                if (EVENT_METADATA.equals(type)) {
                    startTime = payload.get("chat_start_time").toLongValue(0L);
                    sessionId = payload.get("session_id").toStringValue();
                    messageId = payload.get("message_id").toStringValue();
                    model = firstNonBlank(model, payload.get("model_name").toStringValue());
                } else if (EVENT_MODEL_CONFIG.equals(type) || EVENT_TIMING.equals(type)) {
                    model = firstNonBlank(model,
                            firstNonBlank(payload.get("model_name").toStringValue(),
                                    payload.get("config_name").toStringValue()));
                } else if (EVENT_TOKEN_USAGE.equals(type)) {
                    // 请求号用「任务号#事件序号」：token_usage 本身无独立时间戳与会话级 message_id 恒定，
                    // 靠 seq 区分每一轮，保证增量同步按 requestId 去重时不重复、不漏
                    String requestId = firstNonBlank(
                            firstNonBlank(payload.get("__taskId").toStringValue(), sessionId) + "#"
                                    + payload.get("__seq").toStringValue(),
                            messageId);
                    AiUsage usage = toUsage(payload, model, requestId, startTime);
                    if (usage != null) {
                        result.add(usage);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[trae-cn] parse failed {}: {}", file.getFileName(), e.getMessage());
        }
        return result;
    }

    /**
     * 读取事件行的 data 节点（data 本身是二次编码的 JSON 字符串）。
     *
     * @param line 事件行
     * @return data 节点；解析失败时返回 空
     */
    private JsonNode readData(String line) {
        try {
            JsonNode event = Json.parse(line);
            String type = event.get("type").toStringValue();
            JsonNode raw = event.get("data");
            JsonNode data;
            if (raw.isString()) {
                data = Json.parse(raw.toStringValue());
            } else {
                data = raw;
            }
            // 把事件类型、任务号与序号回填到 data 节点，供 parseFile 统一分发与构造稳定请求号
            return data.put("__type", type)
                    .put("__taskId", event.get("task_id").toStringValue())
                    .put("__seq", event.get("seq").toStringValue());
        } catch (Exception e) {
            log.debug("[trae-cn] line parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 token_usage 事件转为用量记录。
     *
     * @param payload   用量节点
     * @param model     模型名
     * @param requestId 稳定请求号
     * @param startTime 会话起始时间（毫秒）
     * @return 用量记录；全零或缺时间返回 空
     */
    private AiUsage toUsage(JsonNode payload, String model, String requestId, long startTime) {
        int prompt = payload.get("prompt_tokens").toIntValue(0);
        int cachedRead = Math.max(0, payload.get("cache_read_input_tokens").toIntValue(0));
        int cacheWrite = Math.max(0, payload.get("cache_creation_input_tokens").toIntValue(0));
        int completion = payload.get("completion_tokens").toIntValue(0);
        int reasoning = Math.min(Math.max(0, payload.get("reasoning_tokens").toIntValue(0)),
                Math.max(0, completion));
        int output = Math.max(0, completion - reasoning);

        // prompt_tokens 已含缓存命中；cache_creation 是写入量、另并入输入
        int promptTokens = prompt + cacheWrite;
        int cacheTokens = Math.min(cachedRead, Math.max(0, promptTokens));
        int totalTokens = promptTokens + output;
        if (totalTokens <= 0 || startTime <= 0L) {
            return null;
        }
        Long ctx = payload.get("max_tokens").toLongValue(0L);
        return AiUsage.builder()
                .provider(name())
                .model(firstNonBlank(model, DEFAULT_MODEL))
                .requestId(requestId)
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(output)
                .totalTokens(totalTokens)
                .cacheTokens(cacheTokens > 0 ? Integer.valueOf(cacheTokens) : null)
                .reasoningTokens(reasoning > 0 ? Integer.valueOf(reasoning) : null)
                .currency("USD")
                .startTime(startTime)
                .build();
    }
}
