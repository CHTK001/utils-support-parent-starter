package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Zed editor agent usage parser.
 *
 * <p>Zed persists agent threads in a SQLite database whose {@code threads}
 * table stores one row per thread with a BLOB {@code data} column — either
 * raw JSON or zstd-compressed JSON (governed by {@code data_type}). Only
 * raw-JSON rows are parsed here; compressed rows are skipped silently.</p>
 *
 * <p>Each thread JSON carries {@code cumulative_token_usage} (a cumulative
 * snapshot rewritten on every send) and/or {@code request_token_usage}
 * (per-request entries with {@code input_tokens} / {@code output_tokens} /
 * {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}).
 * Per-request entries are preferred; otherwise the cumulative snapshot
 * yields one record per thread. {@code input_tokens} is cache-exclusive.
 * Location: {@code %LOCALAPPDATA%\Zed\threads\threads.db} on Windows,
 * {@code $XDG_DATA_HOME/zed/threads/threads.db} on Linux,
 * {@code ~/Library/Application Support/Zed/threads/threads.db} on macOS.</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("zed")
public class ZedUsageParser extends BaseUsageParser {

    private static final String PROVIDER_ZED = "zed";

    private static final Path DB_PATH = resolveDbPath();

    /**
     * 解析 threads.db 路径（按平台）。
     *
     * @return 数据库路径
     */
    private static Path resolveDbPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "Zed", "threads", "threads.db");
            }
            return Path.of(System.getProperty("user.home"),
                    "AppData", "Local", "Zed", "threads", "threads.db");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(System.getProperty("user.home"), "Library",
                    "Application Support", "Zed", "threads", "threads.db");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path dataHome = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return dataHome.resolve("zed").resolve("threads").resolve("threads.db");
    }

    private static final String SQL_THREADS =
            "SELECT id, data, updated_at FROM threads ORDER BY id ASC";

    private static final String SQL_THREADS_NO_TIME =
            "SELECT id, data, NULL AS updated_at FROM threads ORDER BY id ASC";

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "zed"}
     */
    @Override
    public String name() {
        return PROVIDER_ZED;
    }

    /**
     * 响应式流式入口：流出各 thread 的用量；缺 updated_at 列时降级。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[zed] database not found: {} (Zed not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("zed", DB_PATH.toString());
        return engine.query(SQL_THREADS)
                .onErrorResume(e -> engine.query(SQL_THREADS_NO_TIME))
                .map(this::toAiUsage)
                .filter(java.util.Objects::nonNull)
                .doOnComplete(() -> log.info("[zed] stream complete"));
    }

    /**
     * 将 thread 行映射为用量记录；压缩行或全零返回 null。
     *
     * @param row 数据库行
     * @return 用量记录或 null
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        String json = asJsonString(row.get("data"));
        if (json == null || !json.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonNode data = Json.parse(json);
            long updatedAt = asLong(row.get("updated_at"));
            String threadId = asStr(row.get("id"));

            // 优先逐请求记录
            JsonNode requests = data.get("request_token_usage");
            AiUsage perRequest = toPerRequest(threadId, requests, updatedAt);
            if (perRequest != null) {
                return perRequest;
            }

            // 回退：thread 级累计快照（每 thread 一条）
            JsonNode cumulative = data.get("cumulative_token_usage");
            if (cumulative.isMissingValue()) {
                return null;
            }
            int input = cumulative.get("input_tokens").toIntValue(0);
            int output = cumulative.get("output_tokens").toIntValue(0);
            int cacheRead = cumulative.get("cache_read_input_tokens").toIntValue(0);
            int cacheWrite = cumulative.get("cache_creation_input_tokens").toIntValue(0);
            if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0) {
                return null;
            }
            return build(threadId, threadId, cumulative.get("model").toStringValue(),
                    input, output, cacheRead, cacheWrite,
                    cumulative.get("reasoning_tokens").toIntValue(0), updatedAt);
        } catch (Exception e) {
            log.debug("[zed] thread parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析逐请求用量：request_token_usage 为数组时仅取末条
     * （累计语义），为 map 时取各模型用量之和。
     *
     * @param threadId  thread id
     * @param requests  request_token_usage 节点
     * @param updatedAt thread 更新时间（毫秒）
     * @return 用量记录；无有效数据返回 null
     */
    private AiUsage toPerRequest(String threadId, JsonNode requests, long updatedAt) {
        if (requests.isMissingValue()) {
            return null;
        }
        if (requests.isArray()) {
            // 数组：取末条（最新快照）
            java.util.List<?> arr = requests.toJsonArray();
            if (arr.isEmpty() || !(arr.get(arr.size() - 1) instanceof Map<?, ?> last)) {
                return null;
            }
            return build(threadId, threadId + ":last",
                    asStr(last.get("model")),
                    asInt(last.get("input_tokens")),
                    asInt(last.get("output_tokens")),
                    asInt(last.get("cache_read_input_tokens")),
                    asInt(last.get("cache_creation_input_tokens")),
                    asInt(last.get("reasoning_tokens")), updatedAt);
        }
        if (!requests.isObject()) {
            return null;
        }
        Map<String, Object> map = requests.toJsonObject().toMap();
        int input = 0;
        int output = 0;
        int cacheRead = 0;
        int cacheWrite = 0;
        String model = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> usage) {
                input += asInt(usage.get("input_tokens"));
                output += asInt(usage.get("output_tokens"));
                cacheRead += asInt(usage.get("cache_read_input_tokens"));
                cacheWrite += asInt(usage.get("cache_creation_input_tokens"));
                String m = asStr(usage.get("model"));
                if (model == null && !m.isBlank()) {
                    model = m;
                }
            }
        }
        if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0) {
            return null;
        }
        return build(threadId, threadId + ":requests", model,
                input, output, cacheRead, cacheWrite, 0, updatedAt);
    }

    /**
     * 构建用量记录。
     *
     * @param threadId   thread id
     * @param requestId  请求 id
     * @param model      模型名
     * @param input      非缓存输入
     * @param output     输出
     * @param cacheRead  缓存读取
     * @param cacheWrite 缓存写入
     * @param reasoning  推理令牌
     * @param updatedAt  时间戳（毫秒）
     * @return 用量记录
     */
    private AiUsage build(String threadId, String requestId, String model,
                          int input, int output, int cacheRead, int cacheWrite,
                          int reasoning, long updatedAt) {
        int cacheTokens = Math.max(cacheRead, cacheWrite);
        return AiUsage.builder()
                .provider(PROVIDER_ZED)
                .model(model != null && !model.isBlank() ? model : PROVIDER_ZED)
                .requestId(requestId)
                .inputTokens(Math.max(0, input))
                .outputTokens(Math.max(0, output))
                .totalTokens(Math.max(0, input) + Math.max(0, output)
                        + Math.max(0, cacheRead) + Math.max(0, cacheWrite))
                .cacheTokens(cacheTokens > 0 ? cacheTokens : null)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .currency("USD")
                .estimated(false)
                .startTime(updatedAt > 0 ? updatedAt : null)
                .build();
    }

    /**
     * BLOB/字符串列转 JSON 文本；非文本返回 null。
     *
     * @param value 列值
     * @return UTF-8 文本或 null
     */
    private String asJsonString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String s) {
            return s;
        }
        return null;
    }
}
