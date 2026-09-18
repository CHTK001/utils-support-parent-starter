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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Kimi（Moonshot AI）CLI 用量解析器。
 *
 * <p>Kimi Code CLI 会在 {@code ~/.kimi/sessions/<workspace>/<sessionId>/wire.jsonl}
 * 下被动持久化一份按会话组织的通信日志（可用 {@code KIMI_HOME} 覆盖主目录）。
 * 每条 {@code StatusUpdate} 消息都带有真实的单次响应 token 计数：</p>
 *
 * <pre>{@code
 * {
 *   "message": {
 *     "type": "StatusUpdate",
 *     "payload": {
 *       "message_id": "msg_...",
 *       "token_usage": {
 *         "input_other": 120, "output": 45,
 *         "input_cache_read": 3000, "input_cache_creation": 0
 *       }
 *     }
 *   },
 *   "timestamp": 1787546545.09
 * }
 * }</pre>
 *
 * <p>{@code input_other} 表示未命中缓存的输入，缓存输入单独上报。
 * 记录按 {@code message_id} 去重（同一响应的后续 StatusUpdate 会重复相同的总量）。
 * 默认模型取自 {@code config.toml} 的 {@code default_model}；
 * 取不到时回退为 {@code kimi-for-coding}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kimi")
public class KimiUsageParser extends BaseUsageParser {

    private static final String PROVIDER_KIMI = "kimi";

    private static final String DEFAULT_MODEL = "kimi-for-coding";

    private static final Pattern DEFAULT_MODEL_LINE =
            Pattern.compile("^\\s*default_model\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    private final Path sessionsDir;
    private final Path configPath;

    /**
     * 构造方法，创建 KimiUsageParser 实例。
     */
    public KimiUsageParser() {
        String home = System.getenv("KIMI_HOME");
        Path kimiHome = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".kimi");
        this.sessionsDir = kimiHome.resolve("sessions");
        this.configPath = kimiHome.resolve("config.toml");
    }

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "kimi"}
    */
    @Override
    public String name() {
        return PROVIDER_KIMI;
    }

    /**
    * 响应式流式入口：惰性扫描全部 wire.jsonl，仅提取 StatusUpdate 用量。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listWireFiles();
        if (files.isEmpty()) {
            log.debug("[kimi] no wire.jsonl under {}", sessionsDir);
            return Flux.empty();
        }
        String model = resolveDefaultModel();
        Set<String> seenIds = ConcurrentHashMap.newKeySet();
        log.info("[kimi] scanning {} wire files (model={})", files.size(), model);
        return Flux.fromIterable(files)
                .flatMap(file -> streamWireFile(file, model, seenIds), 4)
                .onErrorResume(e -> {
                    log.debug("[kimi] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
    * 枚举 sessions 目录下全部 wire.jsonl。
    *
    * @return wire 文件列表
    */
    private List<Path> listWireFiles() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sessionsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> "wire.jsonl".equals(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[kimi] walk failed: {}", e.getMessage());
        }
        return files;
    }

    /**
    * 从 {@code config.toml} 解析默认模型名。
    *
    * @return 模型名；缺失时 {@code kimi-for-coding}
    */
    private String resolveDefaultModel() {
        try {
            String raw = Files.readString(configPath);
            Matcher m = DEFAULT_MODEL_LINE.matcher(raw);
            if (m.find()) {
                String key = m.group(1).trim();
                int slash = key.lastIndexOf('/');
                return slash >= 0 ? key.substring(slash + 1) : key;
            }
        } catch (Exception e) {
            log.debug("[kimi] config.toml read failed: {}", e.getMessage());
        }
        return DEFAULT_MODEL;
    }

    /**
    * 流式解析单个 wire 文件；相同 message_id 仅保留首条。
    *
    * @param file    wire 文件
    * @param model   默认模型名
    * @param seenIds 已处理的 message_id 集合
    * @return 用量记录流
    */
    private Flux<AiUsage> streamWireFile(Path file, String model, Set<String> seenIds) {
        return streamLines(file)
                .map(line -> parseLine(line, model, seenIds))
                .filter(java.util.Objects::nonNull);
    }

    /**
    * 解析单行 StatusUpdate；非用量行、重复 id、全零计数返回 null。
    *
    * @param line    单行 JSON
    * @param model   默认模型名
    * @param seenIds 已处理的 message_id 集合
    * @return 用量记录或 null
    */
    private AiUsage parseLine(String line, String model, Set<String> seenIds) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode entry = Json.parse(line);
            JsonNode msg = entry.get("message");
            if (msg.isMissingValue()
                    || !"StatusUpdate".equals(msg.get("type").toStringValue())) {
                return null;
            }
            JsonNode payload = msg.get("payload");
            if (payload.isMissingValue()) {
                return null;
            }
            String messageId = payload.get("message_id").toStringValue();
            if (messageId == null || messageId.isBlank() || !seenIds.add(messageId)) {
                return null;
            }
            JsonNode tu = payload.get("token_usage");
            if (tu.isMissingValue()) {
                return null;
            }
            int input = tu.get("input_other").toIntValue(0);
            int output = tu.get("output").toIntValue(0);
            int cacheRead = tu.get("input_cache_read").toIntValue(0);
            int cacheCreation = tu.get("input_cache_creation").toIntValue(0);
            if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheCreation <= 0) {
                return null;
            }

            JsonNode tsNode = entry.get("timestamp");
            if (tsNode.isMissingValue()) {
                tsNode = payload.get("timestamp");
            }
            long tsMillis = toMillis(tsNode);

            int cacheTokens = cacheRead > 0 ? cacheRead
                    : (cacheCreation > 0 ? cacheCreation : 0);

            return AiUsage.builder()
                    .provider(PROVIDER_KIMI)
                    .model(model)
                    .requestId(messageId)
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(input + output + cacheRead + cacheCreation)
                    .cacheTokens(cacheTokens > 0 ? cacheTokens : null)
                    .currency("USD")
                    .startTime(tsMillis > 0 ? tsMillis : null)
                    .build();
        } catch (Exception e) {
            log.debug("[kimi] line parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
    * 时间戳归一：秒/毫秒 epoch 数值转毫秒；ISO 字符串转毫秒。
    *
    * @param node 时间戳节点
    * @return epoch 毫秒；无法解析返回 0
    */
    private long toMillis(JsonNode node) {
        if (node == null || node.isMissingValue()) {
            return 0L;
        }
        long raw = node.toLongValue(0L);
        if (raw > 0) {
            return raw > 1_000_000_000_000L ? raw : raw * 1000L;
        }
        return parseInstantToMillis(node.toStringValue());
    }
}
