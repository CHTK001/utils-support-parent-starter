package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
* Hermes 智能体 usage parser.
*
* <p>Hermes (by Hugging Face) is an agentic coding assistant that persists
* per-会话 令牌 和 cost tallies 入 a sqlite database at
* {@code ~/.hermes/state.db} on Linux/macOS or
* {@code %LOCALAPPDATA%\hermes\state.db} on Windows. The
* {@code sessions} table records one row per session with cumulative
* 令牌 counters 和 per-call breakdown:</p>
*
* <pre>{@code
* 创建 TABLE 会话 (
* 标识 文本 PRIMARY 键,
* 模型 文本,
* 启动_at INTEGER,   -- 轮次 seconds
* 结束_at INTEGER,     -- 空 When.js 入-进步
* 输入_令牌 INTEGER,
* 输出_令牌 INTEGER,
* 缓存_读取_令牌 INTEGER,
* 缓存_写入_令牌 INTEGER,
* ReasonML_令牌 INTEGER,
* 消息_数量 INTEGER,
*   cost REAL,
*   ...
* );
* }</pre>
*
* <p>Token counts are per-session aggregates (not per-request), so
* this parser emits one {@link AiUsage} record per 会话 with the
* cumulative breakdown. 时间戳 are 轮次 seconds; cost 是否 USD
* When.js.js present.</p>
*
* @author CH
* @since 4.0.0.44
 */
@Spi("hermes")
public class HermesUsageParser extends BaseUsageParser {

    private static final String PROVIDER = "hermes"; // 提供者

    private static final Path DB_PATH = resolveDbPath(); // db路径

    /**
    * resolvedb路径。
    * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "hermes", "state.db");
        }
        return Path.of(System.getProperty("user.home"), ".hermes", "state.db");
    }

    private static final String SQL_SESSIONS =
            "SELECT id, model, started_at, ended_at, "
                    + "input_tokens, output_tokens, cache_read_tokens, "
                    + "cache_write_tokens, reasoning_tokens, message_count, cost "
                    + "FROM sessions "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY started_at ASC";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "hermes"}
     */
    @Override
    public String name() {
        return PROVIDER;
    }

    /**
    * 流式解析全部会话用量记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[hermes] database not found: {} (Hermes not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("hermes", DB_PATH.toString());
        return engine.query(SQL_SESSIONS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[hermes] stream complete"));
    }

    /**
    * 将 sqlite 行映射为 {@link AiUsage}。
    *
    * @param row 数据库行
    * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        int cacheWrite = asInt(row.get("cache_write_tokens"));
        int reasoning = asInt(row.get("reasoning_tokens"));
        double cost = asDouble(row.get("cost"));
        long startedAtSeconds = asLong(row.get("started_at"));
        long endedAtSeconds = asLong(row.get("ended_at"));

        String model = firstNonBlank(asStr(row.get("model")), "unknown");
        String requestId = firstNonBlank(asStr(row.get("id")), "");

        int totalTokens = inputTokens + outputTokens;

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER)
                .model(model)
                .requestId(requestId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead : (cacheWrite > 0 ? cacheWrite : null))
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .currency("USD")
                .estimated(false)
                .startTime(startedAtSeconds > 0 ? startedAtSeconds * 1000L : null);

        if (cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }
        if (endedAtSeconds > 0 && startedAtSeconds > 0) {
            long durationMs = (endedAtSeconds - startedAtSeconds) * 1000L;
            if (durationMs > 0) {
                builder.durationMillis(durationMs);
            }
        }

        return builder.build();
    }
}
