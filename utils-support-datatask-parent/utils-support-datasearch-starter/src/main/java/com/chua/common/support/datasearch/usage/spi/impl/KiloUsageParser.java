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

/**
* Kilo CLI usage parser.
*
* <p>Kilo CLI (kilo.ai) is an OpenCode fork that persists assistant turns in
* the {@code message} table of {@code ~/.local/share/kilo/kilo.db} (or
* {@code $XDG_DATA_HOME/kilo/kilo.db}). Each assistant row's {@code data}
* JSON column carries per-request token breakdown:</p>
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
* <p>Token semantics: {@code tokens.input} is already the <b>non-cached</b>
* input; {@code cache.read} / {@code cache.write} are tracked separately and
* are <i>not</i> added into the total. The {@code session} table also exists
* with cumulative per-session counters, but per-request usage (used for
* billing) lives here; the cumulative session totals would double-count
* against this source so only {@code message} rows are streamed.</p>
*
* <p>Newer Kilo CLI versions may write to the OpenCode v2 layout
* ({@code session_message}); both tables are probed and merged.</p>
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
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "kilo", "kilo.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "kilo", "kilo.db");
    }

    /**
    * v1 schema：助手行携带 data JSON，按 token 量筛选。
     */
    private static final String SQL_V1 =
            "SELECT time_created, "
                    + "json_extract(data, '$.providerID') AS providerID, "
                    + "json_extract(data, '$.modelID') AS modelID, "
                    + "json_extract(data, '$.tokens') AS tokens, "
                    + "json_extract(data, '$.cost') AS cost, "
                    + "json_extract(data, '$.time.completed') AS time_completed, "
                    + "json_extract(data, '$.time.created') AS time_created_inner "
                    + "FROM message "
                    + "WHERE json_extract(data, '$.role') = 'assistant' "
                    + "ORDER BY time_created ASC";

    /**
    * v2 schema（session_message 表，type 列而非 role）。
     */
    private static final String SQL_V2 =
            "SELECT time_created, "
                    + "json_extract(data, '$.providerID') AS providerID, "
                    + "json_extract(data, '$.modelID') AS modelID, "
                    + "json_extract(data, '$.tokens') AS tokens, "
                    + "json_extract(data, '$.cost') AS cost, "
                    + "json_extract(data, '$.time.completed') AS time_completed, "
                    + "json_extract(data, '$.time.created') AS time_created_inner "
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
    * {@code session_message} 表合并，两表并存时由下游按 (session, message)
    * 去重，避免过渡期双计。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[kilo] database not found: {} (Kilo CLI not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("kilo", DB_PATH.toString());
        List<Flux<AiUsage>> streams = new ArrayList<>();
        streams.add(engine.query(SQL_V1).map(this::toAiUsage).onErrorResume(e -> {
            log.debug("[kilo] v1 table read failed: {}", e.getMessage());
            return Flux.empty();
        }));
        streams.add(engine.query(SQL_V2).map(this::toAiUsage).onErrorResume(e -> {
            log.debug("[kilo] v2 table read failed: {}", e.getMessage());
            return Flux.empty();
        }));
        return Flux.merge(streams.toArray(new Flux[0]))
                .doOnComplete(() -> log.info("[kilo] stream complete"));
    }

    /**
    * 将 SQL 行映射为 {@link AiUsage}。
    *
    * @param row 数据库行
    * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
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
        double cost = asDouble(row.get("cost"));
        long completed = asLong(row.get("time_completed"));
        long created = asLong(row.get("time_created_inner"));
        long start = completed > 0 ? completed : (created > 0 ? created
                : asLong(row.get("time_created")));
        String modelId = asStr(row.get("modelID"));
        String providerId = asStr(row.get("providerID"));

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_KILO)
                .model(modelId.isBlank() ? "kilo-unknown" : modelId)
                .requestId(providerId + ":" + modelId)
                .inputTokens(input)
                .outputTokens(output)
                .totalTokens(input + output)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheRead > 0 ? cacheRead : (cacheWrite > 0 ? cacheWrite : null))
                .currency("USD")
                .estimated(false)
                .startTime(start > 0 ? start : null);
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
