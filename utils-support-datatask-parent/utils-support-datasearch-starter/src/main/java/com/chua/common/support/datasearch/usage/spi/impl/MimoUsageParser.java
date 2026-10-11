package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiMo Code（小米）用量解析器。
 *
 * <p>MiMo Code（mimocode）是小米推出的 agentic CLI，是 OpenCode 的分支，
 * 会把助手轮次持久化到 {@code ~/.local/share/mimocode/mimocode.db} 的
 * {@code message} 表中（Windows 下为
 * {@code %APPDATA%\mimocode\mimocode.db}）。{@code data} JSON 列
 * 携带每次请求的 token：</p>
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
 * <p>miMo code 会把用户的 Claude Code / claude-mem 历史镜像进自己的
 * {@code message} 表，其 {@code providerID="anthropic"}。这些行
 * <b>在此处被排除</b>（Claude 解析器已统计过它们）；
 * 只输出 {@code providerID} 为 {@code "mimo"} 或
 * {@code "xiaomi"}（miMo 自身的运行时/自动路由）的轮次，
 * 与 TokenTracker 的判别口径保持一致。
 *
 * <p><b>关于小米 MiMo 桌面软件：</b>该桌面端（日志中
 * {@code [sess-diag] ... engineId=mimocode}）跑的就是同一个 mimocode 引擎，
 * 只是把数据根目录换成了 {@code %APPDATA%\Xiaomi MiMo}（目录下能看到
 * {@code mimocode/locks}），而不是 CLI 的 {@code %APPDATA%\mimocode}。
 * 两者<b>同源</b>：表结构、{@code message} 行格式完全一致，因此
 * <b>不另开一个解析器</b>（新 SPI 名会让「用量分布」里出现两个 MiMo，
 * 且两份数据同源时无法区分来源）。这里改为按路径列表全部扫描并合并输出，
 * 两个目录恰好都存在也不会双计——按 requestId 去重由上游
 * {@code UsageAggregationTask} 负责，且下面已给 requestId 加了库来源前缀。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("mimo")
public class MimoUsageParser extends BaseUsageParser {

    private static final List<Path> DB_PATHS = resolveDbPaths();

    private static final String PROVIDER_MIMO = "mimo";

    /**
     * 解析全部候选数据库路径：MiMo Code CLI 与小米 MiMo 桌面软件。
     *
     * <p>Windows 下 CLI 走 {@code %APPDATA%\mimocode}、桌面端走
     * {@code %APPDATA%\Xiaomi MiMo}；两个都可能装，所以都列上，实际存在哪个
     * 就读哪个。非 Windows 只有 CLI 形态（XDG / ~/.local/share）。</p>
     *
     * @return 候选数据库路径列表（已按存在性过滤并去重）
     */
    private static List<Path> resolveDbPaths() {
        List<Path> candidates = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            candidates.add(Path.of(appData, "mimocode", "mimocode.db"));
            candidates.add(Path.of(appData, "Xiaomi MiMo", "mimocode", "mimocode.db"));
            candidates.add(Path.of(appData, "Xiaomi MiMo", "mimocode.db"));
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            candidates.add(Path.of(xdgDataHome, "mimocode", "mimocode.db"));
        }
        candidates.add(Path.of(System.getProperty("user.home"), ".local", "share",
                "mimocode", "mimocode.db"));
        return candidates.stream().filter(Files::exists).distinct().toList();
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
     * 流式解析 MiMo 自身轮次的用量记录（CLI 与桌面软件的所有已存在库合并输出）。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (DB_PATHS.isEmpty()) {
            log.debug("[mimo] database not found under APPDATA/XDG (MiMo Code 或桌面版未安装)");
            return Flux.empty();
        }
        log.debug("[mimo] scanning {} database(s): {}", DB_PATHS.size(), DB_PATHS);
        // 每个库一个数据源名（同一引擎内数据源名不能重复），逐个 concat 而非 merge：
        // merge 会交错发射，concat 保证单库内仍按时间有序，便于上游做增量水位判断
        Flux<AiUsage> merged = Flux.empty();
        for (int i = 0; i < DB_PATHS.size(); i++) {
            final String source = "mimo-" + i;
            final Path db = DB_PATHS.get(i);
            Flux<AiUsage> one;
            try {
                SqliteReactorEngine engine = new SqliteReactorEngine()
                        .addDataSource(source, db.toString());
                one = engine.query(SQL_MESSAGES)
                        .mapNotNull(row -> toAiUsage(row, source));
            } catch (Throwable e) {
                // 单个库损坏/被占用不应让整次同步失败，降级跳过继续读下一个
                log.warn("[mimo] 跳过不可用的数据库 {}: {}", db, e.toString());
                continue;
            }
            merged = merged.concatWith(one);
        }
        return merged.doOnComplete(() -> log.info("[mimo] stream complete, {} db(s)", DB_PATHS.size()));
    }

    /**
     * 将 SQL 行映射为 {@link AiUsage}；零用量行返回 null 由上游过滤。
     *
     * @param row    数据库行
     * @param source 数据源名，用于给 requestId 加库前缀（见下）
     * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row, String source) {
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
        String model = asStr(row.get("modelID"));

        // tokens.input 只是非缓存段（与 opencode/kilo 同结构），并回全量输入；写入不是命中。
        int promptTokens = Math.max(0, input) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
        int cacheHit = Math.min(cacheRead, promptTokens);

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_MIMO)
                .model(model.isBlank() ? "mimo-unknown" : model)
                // requestId 必须逐次调用唯一，否则上游按 requestId 去重会把同模型的
                // 多次调用互相覆盖、只留一条。这里用「库来源 + 时间戳 + 模型」，
                // 保证跨库不撞、库内每条轮次也各不相同。
                .requestId("mimo:" + source + ":" + startTime + ":" + model)
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(output)
                .totalTokens(promptTokens + output)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                .currency("USD")
                .estimated(false)
                .startTime(startTime > 0 ? startTime : null);
        if (cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }
        return builder.build();
    }
}
