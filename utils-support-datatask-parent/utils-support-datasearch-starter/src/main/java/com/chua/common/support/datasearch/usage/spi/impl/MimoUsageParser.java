package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
* MiMo Code (Xiaomi) usage parser.
*
* <p>MiMo Code (mimocode) is Xiaomi's agentic CLI, an OpenCode fork that
* persists assistant turns in the {@code message} table of
* {@code ~/.local/share/mimocode/mimocode.db} (Windows:
* {@code %APPDATA%\mimocode\mimocode.db}). The {@code data} JSON column
* carries per-request tokens:</p>
*
* <pre>{@code
* {
*   "role": "assistant",
*   "modelID": "mimo-v2.5-pro",
*   "providerID": "mimo",
*   "time": { "created": 1787616871678 },
*   "tokens": { "input": 1200, "output": 210, "reasoning": 0,
*               "cache": { "read": 0, "write": 0 } }
* }
* }</pre>
*
* <p>miMo code mirrors the user's Claude Code / claude-mem history into its
* own {@code message} table with {@code providerID="anthropic"}. Those rows
* are <b>excluded</b> here (they are already counted by the Claude parser);
* only turns whose {@code providerID} is {@code "mimo"} or
* {@code "xiaomi"} (miMo's own runtime/auto-router) are emitted, matching
* TokenTracker's discriminator.
*
* @author CH
* @since 4.0.0.44
 */
@Spi("mimo")
public class MimoUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = resolveDbPath();

    private static final String PROVIDER_MIMO = "mimo";

    /**
    * resolvedb路径：Windows 走 APPDATA，其余走 XDG_DATA_HOME /
    * ~/.local/share。
    *
    * @return MiMo 数据库路径
    */
    private static Path resolveDbPath() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "mimocode", "mimocode.db");
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "mimocode", "mimocode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share",
                "mimocode", "mimocode.db");
    }

    /**
    * 仅统计 MiMo 自身轮次（providerID 为 mimo / xiaomi），排除镜像进来的
    * anthropic/openai/google 行——那些已由各自的 Claude / Codex / Gemini
    * 解析器计数，纳入本解析器会双计。
    */
    private static final String SQL_MESSAGES =
            "SELECT time_created, "
                    + "json_extract(data, '$.providerID') AS providerID, "
                    + "json_extract(data, '$.modelID') AS modelID, "
                    + "json_extract(data, '$.tokens') AS tokens, "
                    + "json_extract(data, '$.cost') AS cost "
                    + "FROM message "
                    + "WHERE json_extract(data, '$.role') = 'assistant' "
                    + "AND (lower(json_extract(data, '$.providerID')) = 'mimo' "
                    + "     OR lower(json_extract(data, '$.providerID')) = 'xiaomi') "
                    + "ORDER BY time_created ASC";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "mimo"}
    */
    @Override
    public String name() {
        return PROVIDER_MIMO;
    }

    /**
    * 流式解析 MiMo 自身轮次的用量记录。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[mimo] database not found: {} (MiMo Code not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("mimo", DB_PATH.toString());
        return engine.query(SQL_MESSAGES)
                .map(this::toAiUsage)
                .filter(java.util.Objects::nonNull)
                .doOnComplete(() -> log.info("[mimo] stream complete"));
    }

    /**
    * 将 SQL 行映射为 {@link AiUsage}；零用量行返回 null 由上游过滤。
    *
    * @param row 数据库行
    * @return 用量记录
    */
    private AiUsage toAiUsage(Map<String, Object> row) {
        String rawTokens = asStr(row.get("tokens"));
        int input = 0, output = 0, reasoning = 0, cacheRead = 0, cacheWrite = 0;
        if (rawTokens != null && !rawTokens.isBlank()) {
            try {
                com.chua.common.support.lang.json.JsonNode tokens =
                        com.chua.common.support.lang.json.Json.parse(rawTokens);
                input = tokens.get("input").toIntValue(0);
                output = tokens.get("output").toIntValue(0);
                reasoning = tokens.get("reasoning").toIntValue(0);
                cacheRead = tokens.get("cache").get("read").toIntValue(0);
                cacheWrite = tokens.get("cache").get("write").toIntValue(0);
            } catch (Exception e) {
                log.debug("[mimo] tokens parse failed: {}", e.getMessage());
            }
        }
        if (input <= 0 && output <= 0) {
            return null;
        }
        double cost = asDouble(row.get("cost"));
        long startTime = asLong(row.get("time_created"));

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_MIMO)
                .model(asStr(row.get("modelID")).isBlank()
                        ? "mimo-unknown" : asStr(row.get("modelID")))
                .requestId(asStr(row.get("providerID")) + ":" + asStr(row.get("modelID")))
                .inputTokens(input)
                .outputTokens(output)
                .totalTokens(input + output)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheRead > 0 ? cacheRead : (cacheWrite > 0 ? cacheWrite : null))
                .currency("USD")
                .estimated(false)
                .startTime(startTime > 0 ? startTime : null);
        if (cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }
        return builder.build();
    }
}
