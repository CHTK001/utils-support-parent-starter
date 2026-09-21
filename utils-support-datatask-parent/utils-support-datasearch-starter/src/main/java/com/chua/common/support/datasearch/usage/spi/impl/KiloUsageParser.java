package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kilo CLI 用量解析器。
 *
 * <p>Kilo CLI（kilo.ai）是 OpenCode 的分支，它把助手回复写入
 * {@code ~/.local/share/kilo/kilo.db}（或
 * {@code $XDG_DATA_HOME/kilo/kilo.db}）的 {@code message} 表。每条助手记录的 {@code data}
 * JSON 列携带逐次请求的 token 明细：</p>
 *
 * <pre>{@code
 * {
 *   "role": "assistant",
 *   "modelID": "google/gemini-3-pro-image",
 *   "providerID": "kilo",
 *   "time": { "created": 1787616871678, "completed": 1787616888000 },
 *   "tokens": { "input": 1200, "output": 210, "reasoning": 0,
 *               "cache": { "read": 0, "write": 0 } },
 *   "cost": 0.0012
 * }
 * }</pre>
 *
 * <p>token 语义：{@code tokens.input} 本身就是<b>非缓存</b>
 * 输入；{@code cache.read} / {@code cache.write} 单独统计，
 * <i>不</i>计入该字段。由于本仓库约定 {@code inputTokens} 含缓存，产出时把两段并回，
 * 命中量单独落在 {@code cacheTokens}（写入量不是命中，不报）。{@code session} 表同样存在，
 * 保存按会话累计的计数，但用于计费的逐次请求用量记录在本表；若把会话累计值也取过来会与本源重复计数，
 * 因此只读取 {@code message} 行。</p>
 *
 * <p>新版 Kilo CLI 可能写入 OpenCode v2 结构
 * （{@code session_message}）；两张表都会被探测并合并。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("kilo")
public class KiloUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = resolveDbPath();

    private static final String PROVIDER_KILO = "kilo";

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        Path home = Path.of(System.getProperty("user.home"));
        Path share = home.resolve(Path.of(".local", "share", "kilo", "kilo.db"));
        Path legacy = home.resolve(Path.of(".kilo", "kilo.db"));
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return firstExisting(Path.of(xdgDataHome, "kilo", "kilo.db"), share, legacy);
        }
        return firstExisting(share, legacy);
    }

    /**
     * v1 schema：助手行携带 data JSON，按 token 量筛选。
     */
    private static final String SQL_V1 =
            "SELECT id AS message_id, "
                    + "time_created, "
                    + "json_extract(data, '$.providerID') AS providerID, "
                    + "json_extract(data, '$.modelID') AS modelID, "
                    + "json_extract(data, '$.tokens') AS tokens, "
                    + "json_extract(data, '$.cost') AS cost, "
                    + "json_extract(data, '$.time.completed') AS time_completed, "
                    + "json_extract(data, '$.time.created') AS time_created_inner, "
                    + "(SELECT MIN(p.time_created) FROM part p "
                    + " WHERE p.message_id = message.id "
                    + "   AND json_extract(p.data, '$.type') IN ('text', 'reasoning', 'tool')) AS first_part "
                    + "FROM message "
                    + "WHERE json_extract(data, '$.role') = 'assistant' "
                    + "ORDER BY time_created ASC";

    /**
     * v2 schema（session_message 表，type 列而非 role）。
     */
    private static final String SQL_V2 =
            "SELECT id AS message_id, "
                    + "time_created, "
                    + "json_extract(data, '$.providerID') AS providerID, "
                    + "json_extract(data, '$.modelID') AS modelID, "
                    + "json_extract(data, '$.tokens') AS tokens, "
                    + "json_extract(data, '$.cost') AS cost, "
                    + "json_extract(data, '$.time.completed') AS time_completed, "
                    + "json_extract(data, '$.time.created') AS time_created_inner, "
                    + "(SELECT MIN(p.time_created) FROM part p "
                    + " WHERE p.message_id = session_message.id "
                    + "   AND json_extract(p.data, '$.type') IN ('text', 'reasoning', 'tool')) AS first_part "
                    + "FROM session_message "
                    + "WHERE type = 'assistant' "
                    + "ORDER BY time_created ASC";

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "kilo"}
     */
    @Override
    public String name() {
        return PROVIDER_KILO;
    }

    /**
     * 流式解析全部助手用量记录：v1 {@code message} 表与 v2
     * {@code session_message} 表合并，过渡期两表可能装着同一条消息，
     * 故按消息号在本解析器内去重，避免双计。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[kilo] database not found: {} (Kilo CLI not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("kilo", DB_PATH.toString());
        Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
        List<Flux<AiUsage>> streams = new ArrayList<>();
        streams.add(engine.query(SQL_V1).mapNotNull(row -> toAiUsage(row, seenMessageIds)).onErrorResume(e -> {
            log.debug("[kilo] v1 table read failed: {}", e.getMessage());
            return Flux.empty();
        }));
        streams.add(engine.query(SQL_V2).mapNotNull(row -> toAiUsage(row, seenMessageIds)).onErrorResume(e -> {
            log.debug("[kilo] v2 table read failed: {}", e.getMessage());
            return Flux.empty();
        }));
        return Flux.merge(streams.toArray(new Flux[0]))
                .doOnComplete(() -> log.info("[kilo] stream complete"));
    }

    /**
     * 将 SQL 行映射为 {@link AiUsage}。
     *
     * @param row            数据库行
     * @param seenMessageIds 已输出的消息号集合，用于跨 v1/v2 去重
     * @return 用量记录；没有用量或已经输出过的消息返回 {@code null}
     */
    private AiUsage toAiUsage(Map<String, Object> row, Set<String> seenMessageIds) {
        String rawTokens = asStr(row.get("tokens"));
        JsonNode tokens = parseJsonOrEmpty(rawTokens);
        int input = tokens.get("input").toIntValue(0);
        int output = tokens.get("output").toIntValue(0);
        int reasoning = tokens.get("reasoning").toIntValue(0);
        int cacheRead = tokens.get("cache").get("read").toIntValue(0);
        int cacheWrite = tokens.get("cache").get("write").toIntValue(0);
        if (input <= 0 && output <= 0) {
            return null;
        }
        String messageId = asStr(row.get("message_id"));
        if (!messageId.isBlank() && !seenMessageIds.add(messageId)) {
            return null;
        }
        // tokens.input 只是非缓存段，按本仓库口径把命中与写入并回全量输入；写入不是命中，不进 cacheTokens。
        int promptTokens = Math.max(0, input) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
        int cacheHit = Math.min(cacheRead, promptTokens);
        double cost = asDouble(row.get("cost"));
        long completed = asLong(row.get("time_completed"));
        long created = asLong(row.get("time_created_inner"));
        // 源里 created 是发起、completed 是回复完成，两枚戳基本每行都齐；只有单枚时无耗时。
        Long duration = created > 0 && completed > created
                ? Long.valueOf(completed - created) : null;
        long endTime = completed > 0 ? completed : (created > 0 ? created
                : asLong(row.get("time_created")));
        long startTime = startTimeOf(endTime, duration);
        long firstPart = asLong(row.get("first_part"));
        Long firstToken = firstPart > startTime ? Long.valueOf(firstPart - startTime) : null;
        String modelId = asStr(row.get("modelID"));
        String providerId = asStr(row.get("providerID"));

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_KILO)
                .model(modelId.isBlank() ? "kilo-unknown" : modelId)
                .requestId(messageId.isBlank() ? providerId + ":" + modelId : messageId)
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(output)
                .totalTokens(promptTokens + output)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                .currency("USD")
                .estimated(false)
                .durationMillis(duration)
                .firstTokenLatencyMillis(firstToken)
                .startTime(startTime > 0 ? startTime : null);
        if (cost > 0) {
            builder.totalCost(BigDecimal.valueOf(cost));
        }
        return builder.build();
    }

    /**
     * 解析可能为 null / 空字符串的 JSON 字符串字段。
     *
     * @param raw 原始字符串
     * @return 解析结果；失败返回缺失值节点
     */
    private JsonNode parseJsonOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return JsonNode.valueOf(null);
        }
        try {
            return Json.parse(raw);
        } catch (Exception e) {
            return JsonNode.valueOf(null);
        }
    }
}
