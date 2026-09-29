package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Goose usage parser.
 *
 * <p>Goose (github.com/block/goose) stores sessions in a SQLite database at
 * {@code %APPDATA%\Block\goose\data\sessions\sessions.db} on Windows. The
 * {@code usage_ledger} table records one row per LLM call with exact token,
 * 缓存 和 cost 归因:</p>
 *
 * <pre>{@code
 * CREATE TABLE usage_ledger (
 *   id, session_id, created_timestamp,   -- epoch seconds
 *   model TEXT, input_tokens INTEGER, output_tokens INTEGER,
 *   total_tokens INTEGER, cache_read_tokens INTEGER,
 *   cache_write_tokens INTEGER, cost REAL, cost_source TEXT, ...
 * )
 * }</pre>
 *
 * <p>台账本身不记耗时。同一轮请求在 {@code messages} 表里留下一条同
 * {@code session_id} + {@code created_timestamp} 的 assistant 消息，其
 * {@code metadata_json.usage} 带 {@code elapsedMs} 与 {@code timeToFirstTokenMs}，
 * 由查询侧关联取出后落到 {@code durationMillis} / {@code firstTokenLatencyMillis}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("goose")
public class GooseUsageParser extends BaseUsageParser {

    private static final String SQL_LEDGER =
            "SELECT l.session_id, l.created_timestamp, l.model, l.input_tokens, l.output_tokens, "
                    + "l.total_tokens, l.cache_read_tokens, l.cache_write_tokens, l.cost, l.cost_source, "
                    + "(SELECT CAST(json_extract(m.metadata_json, '$.usage.elapsedMs') AS INTEGER) "
                    + "   FROM messages m WHERE m.session_id = l.session_id "
                    + "     AND m.role = 'assistant' AND m.created_timestamp = l.created_timestamp "
                    + "   LIMIT 1) AS elapsed_ms, "
                    + "(SELECT CAST(json_extract(m.metadata_json, '$.usage.timeToFirstTokenMs') AS INTEGER) "
                    + "   FROM messages m WHERE m.session_id = l.session_id "
                    + "     AND m.role = 'assistant' AND m.created_timestamp = l.created_timestamp "
                    + "   LIMIT 1) AS ttft_ms "
                    + "FROM usage_ledger l "
                    + "WHERE l.input_tokens > 0 OR l.output_tokens > 0 "
                    + "ORDER BY l.created_timestamp ASC";

    private static final String PROVIDER_GOOSE = "goose"; // 提供者goose
    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L; // 轮次seconds转为millis

    /**
     * 解析 Goose 会话库路径。
     *
     * <p>Goose 是 Windows 工具，库固定落在 {@code %APPDATA%\Block\goose\data\sessions\sessions.db}。
     * 但 {@code APPDATA} 是 Windows 环境变量，Linux/macOS 上 {@link System#getenv} 返回 {@code null}，
     * 而 {@code Path.of} 首个实参为 null 会抛 {@link NullPointerException}。</p>
     *
     * <p>原先写成静态字段初始化，代价是类初始化失败抛出
     * {@link ExceptionInInitializerError}——它是 {@link Error} 而非 {@link Exception}，
     * 调用方的 {@code catch (Exception)} 抓不住，会一路穿透成接口 500
     * （实测 2026-09-29 远端 {@code GET /v2/usage/parsers} 即报 {@code S9999C0000}；
     * 且首次失败后该类永久不可用，后续访问改抛 {@link NoClassDefFoundError}）。
     * 故改为调用期解析，用 {@code null} 表示「本机没有 Goose」，
     * 与本包其它解析器（如 KiroUsageParser 的 resolveDbPath）风格一致。</p>
     *
     * @return Goose 会话库路径；环境变量缺失（本机非 Windows）时返回 null
     */
    private static Path resolveDbPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, "Block", "goose", "data", "sessions", "sessions.db");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "goose"}
     */
    public String name() {
        return "goose";
    }

    /**
     * 流式解析全部用量台账记录。
     *
     * @return 用量记录流；本机无 Goose 时返回空流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        Path dbPath = resolveDbPath();
        if (dbPath == null || !Files.exists(dbPath)) {
            log.debug("[goose] database not found (Goose 未安装于本平台或路径不存在)");
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("goose", dbPath.toString());
        return engine.query(SQL_LEDGER)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[goose] stream complete"));
    }

    /**
     * 转为AiUsage。
     *
     * @param row 行，不允许为 null
     * @return AiUsage 对象
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        long createdAtSeconds = asLong(row.get("created_timestamp"));
        double cost = asDouble(row.get("cost"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        int cacheWrite = asInt(row.get("cache_write_tokens"));
        long elapsedMs = asLong(row.get("elapsed_ms"));
        long ttftMs = asLong(row.get("ttft_ms"));

        // 台账的 total_tokens 含一段无字段可归的令牌（实测 6156 + 1 而 total 6182），
        // 按契约只由可见分段相加。
        return AiUsage.builder()
                .provider(PROVIDER_GOOSE)
                .model(asStr(row.get("model")))
                .requestId(asStr(row.get("session_id")))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(firstPositive(cacheRead, cacheWrite))
                .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                .currency("USD")
                .estimated("estimated".equals(asStr(row.get("cost_source"))))
                .startTime(createdAtSeconds > 0
                        ? createdAtSeconds * EPOCH_SECONDS_TO_MILLIS : null)
                .durationMillis(elapsedMs > 0 ? elapsedMs : null)
                .firstTokenLatencyMillis(ttftMs > 0 ? ttftMs : null)
                .build();
    }
}
