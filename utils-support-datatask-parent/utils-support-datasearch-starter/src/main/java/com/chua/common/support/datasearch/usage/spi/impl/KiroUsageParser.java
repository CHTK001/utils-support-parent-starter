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
 * Kiro IDE usage parser.
 *
 * <p>Kiro (AWS's agentic IDE, VS Code fork) persists token usage in a SQLite
   * database under its editor 配置 根:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Kiro\User\globalStorage\kiro.kiroagent\dev_data\devdata.sqlite}</li>
 *   <li>Linux/macOS: {@code ~/.config|~/Library/Application\ Support/Kiro/User/
   * 全局storage/kiro.kiroagent/dev_数据/devdata.sqlite}</li>
 * </ul>
 *
 * <p>The {@code tokens_generated} table stores one row per model call:</p>
 *
 * <pre>{@code
   * 创建 TABLE 令牌_generated (
   * 标识 INTEGER PRIMARY 键,
   * 模型 文本,
   * 提供者 文本,
 *   tokens_prompt INTEGER,      -- 非缓存输入
 *   tokens_generated INTEGER,   -- 输出
   * 时间戳 文本              -- UTC "2026-01-09 15:25:30"
 * );
 * }</pre>
 *
 * <p>When the DB is absent, Kiro falls back to a JSONL file
 * {@code dev_data/tokens_generated.jsonl} with lines shaped
 * {@code {"model":"agent","provider":"kiro","promptTokens":N,
   * "generated令牌":N}}. Rows with both counters zero are skipped.
   * Because the 降级 文件 carries no per-row 时间戳, 新 线 are
   * attributed 转为 the 文件's 最后一个-modified 时间 和 the parser 是否 estimated.</p>
 *
 * @author CH
 * @since 4.0.0.44
 * @return resolvejsonl路径的结果
 */
@Spi("kiro")
public class KiroUsageParser extends BaseUsageParser {

    private static final String PROVIDER_KIRO = "kiro"; // 提供者kiro

    private static final Path DB_PATH = resolveDbPath(); // db路径

    private static final Path JSONL_PATH = resolveJsonlPath(); // jsonl路径

    private static final String SQL_TOKENS =
            "SELECT id, model, provider, tokens_prompt, tokens_generated, timestamp "
                    + "FROM tokens_generated "
                    + "WHERE tokens_prompt > 0 OR tokens_generated > 0 "
                    + "ORDER BY id ASC";
/**
 * resolvedb路径。
 * @return resolvedb路径的结果
 */

    private static Path resolveDbPath() {
        Path base = resolveKiroBasePath();
        return base.resolve("dev_data").resolve("devdata.sqlite");
    }

    private static Path resolveJsonlPath() {
        Path base = resolveKiroBasePath();
        return base.resolve("dev_data").resolve("tokens_generated.jsonl");
    }

    /**
     * 按平台解析 Kiro globalStorage 根目录。
     *
     * @return Kiro 数据根目录
     */
    private static Path resolveKiroBasePath() {
        String[] suffix = {"Kiro", "User", "globalStorage", "kiro.kiroagent"};
        String appData = System.getenv("APPDATA");
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        String home = System.getProperty("user.home");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, suffix);
        }
        if (xdgConfig != null && !xdgConfig.isBlank()) {
            return Path.of(xdgConfig, suffix);
        }
        return Path.of(home, "Library", "Application Support", suffix);
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "kiro"}
     */
    @Override
    public String name() {
        return PROVIDER_KIRO;
    }

    /**
     * 流式解析 Kiro 用量：DB 优先，DB 缺失时回退 JSONL。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (Files.exists(DB_PATH)) {
            SqliteReactorEngine engine = new SqliteReactorEngine()
                    .addDataSource("kiro", DB_PATH.toString());
            return engine.query(SQL_TOKENS)
                    .map(this::toAiUsageFromDb)
                    .doOnComplete(() -> log.info("[kiro] db stream complete"));
        }
        if (Files.exists(JSONL_PATH)) {
            return streamJsonlFallback();
        }
        log.debug("[kiro] no data source under {} (Kiro not installed)", DB_PATH);
        return Flux.empty();
    }

    /**
     * 将 DB 行映射为用量记录。
     *
     * @param row 数据库行
     * @return 用量记录
     */
    private AiUsage toAiUsageFromDb(Map<String, Object> row) {
        int inputTokens = asInt(row.get("tokens_prompt"));
        int outputTokens = asInt(row.get("tokens_generated"));
        String model = firstNonBlank(asStr(row.get("model")), "kiro-agent");
        long timestamp = parseKiroTimestamp(asStr(row.get("timestamp")));

        return AiUsage.builder()
                .provider(PROVIDER_KIRO)
                .model(model)
                .requestId(asStr(row.get("id")))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .currency("USD")
                .estimated(false)
                .startTime(timestamp > 0 ? timestamp : null)
                .build();
    }

    /**
     * 回退：流式解析 JSONL（无逐行时间戳，统一按文件 mtime 归因，estimated）。
     * @return 流jsonl降级的结果
     */
    private Flux<AiUsage> streamJsonlFallback() {
        long fileMtime;
        try {
            fileMtime = Files.getLastModifiedTime(JSONL_PATH).toMillis();
        } catch (Exception e) {
            fileMtime = 0L;
        }
        log.info("[kiro] falling back to jsonl: {}", JSONL_PATH);
        return streamLines(JSONL_PATH)
                .map(line -> {
                    com.chua.common.support.lang.json.JsonNode node =
                            com.chua.common.support.lang.json.Json.parse(line);
                    int input = node.get("promptTokens").toIntValue(0);
                    int output = node.get("generatedTokens").toIntValue(0);
                    if (input <= 0 && output <= 0) {
                        return null;
                    }
                    return AiUsage.builder()
                            .provider(PROVIDER_KIRO)
                            .model(node.get("model").isMissingValue()
                                    ? "kiro-agent"
                                    : node.get("model").toStringValue())
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(input + output)
                            .currency("USD")
                            .estimated(true)
                            .startTime(fileMtime > 0 ? fileMtime : null)
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .doOnComplete(() -> log.info("[kiro] jsonl stream complete"));
    }

    /**
     * 解析 Kiro DB 的 "yyyy-MM-dd HH:mm:ss"（UTC）时间戳。
     *
     * @param raw 原始时间戳字符串
     * @return epoch 毫秒；无法解析时 0
     */
    private long parseKiroTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String normalized = raw.replace(' ', 'T') + "Z";
        return parseInstantToMillis(normalized);
    }
}
