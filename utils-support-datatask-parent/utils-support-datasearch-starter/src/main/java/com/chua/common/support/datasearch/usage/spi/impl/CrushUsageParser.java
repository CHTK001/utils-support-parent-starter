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
 * Crush usage parser.
 *
 * <p>Crush (github.com/charmbracelet/crush) keeps per-project SQLite databases
 * at {@code <project>/.crush/crush.db}, indexed by
 * {@code ~/.local/share/crush/projects.json} (or
 * {@code %USERPROFILE%\AppData\Local\crush\projects.json} on Windows).
 * The {@code sessions} table holds session-level token and cost aggregates:</p>
 *
 * <pre>{@code
 * CREATE TABLE sessions (
 *   id, title, message_count,
 *   prompt_tokens INTEGER, completion_tokens INTEGER, cost REAL,
 *   created_at, updated_at, ... )   -- timestamps in epoch seconds
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("crush")
public class CrushUsageParser extends BaseUsageParser {

    private static final Path PROJECTS_INDEX = Path.of(
            System.getProperty("user.home"), "AppData", "Local", "crush", "projects.json");

    private static final String SQL_SESSIONS =
            "SELECT id, title, prompt_tokens, completion_tokens, cost, "
                    + "created_at, updated_at FROM sessions "
                    + "WHERE prompt_tokens > 0 OR completion_tokens > 0 "
                    + "ORDER BY created_at ASC";

    private static final String PROVIDER_CRUSH = "crush";

    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L;

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "crush"}
     */
    @Override
    public String name() {
        return PROVIDER_CRUSH;
    }

    /**
     * 流式解析全部项目的会话用量。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> databases = listProjectDatabases();
        if (databases.isEmpty()) {
            log.debug("[crush] no project databases found");
            return Flux.empty();
        }
        log.info("[crush] scanning {} project databases", databases.size());
        return Flux.fromIterable(databases)
                .flatMap(this::streamDatabase, 2);
    }

    /**
     * 从 projects.json 索引收集所有存在 crush.db 的项目路径。
     *
     * @return crush.db 文件列表；索引缺失或无有效条目时为空
     */
    private List<Path> listProjectDatabases() {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(PROJECTS_INDEX)) {
            return result;
        }
        try {
            JsonNode root = Json.parse(Files.readString(PROJECTS_INDEX));
            JsonNode projects = root.get("projects");
            if (projects.isMissingValue() || !projects.isArray()) {
                return result;
            }
            for (int i = 0; i < projects.size(); i++) {
                JsonNode dataDir = projects.get(i).get("data_dir");
                if (dataDir.isMissingValue()) {
                    continue;
                }
                Path db = Path.of(dataDir.toStringValue(), "crush.db");
                if (Files.exists(db)) {
                    result.add(db);
                }
            }
        } catch (Exception e) {
            log.warn("[crush] index parse failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 流式解析单个项目数据库的会话聚合用量。
     *
     * @param db crush.db 文件路径
     * @return 用量记录流
     */
    private Flux<AiUsage> streamDatabase(Path db) {
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("crush-" + System.identityHashCode(db), db.toString());
        return engine.query(SQL_SESSIONS)
                .map(row -> toAiUsage(row, db))
                .onErrorResume(e -> {
                    log.debug("[crush] db read failed {}: {}", db, e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 将 sessions 表行映射为会话级聚合用量记录。
     *
     * @param row sessions 行
     * @param db  来源数据库路径
     * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row, Path db) {
        int promptTokens = asInt(row.get("prompt_tokens"));
        int completionTokens = asInt(row.get("completion_tokens"));
        double cost = asDouble(row.get("cost"));
        long createdAt = asLong(row.get("created_at"));

        return AiUsage.builder()
                .provider(PROVIDER_CRUSH)
                .model("crush-session")
                .requestId(asStr(row.get("id")))
                .inputTokens(promptTokens)
                .outputTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .totalCost(cost > 0 ? BigDecimal.valueOf(cost) : null)
                .currency("USD")
                .startTime(createdAt > 0 ? createdAt * EPOCH_SECONDS_TO_MILLIS : null)
                .finishReason(asStr(row.get("title")).isBlank()
                        ? "session" : asStr(row.get("title")))
                .build();
    }
}
