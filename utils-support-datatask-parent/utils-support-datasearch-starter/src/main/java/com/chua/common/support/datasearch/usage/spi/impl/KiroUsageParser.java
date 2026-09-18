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
* Kiro (AWS) usage parser.
*
* <p>Kiro persists token usage in a SQLite database under its editor config
* root (Windows: {@code %APPDATA%\Kiro\User\globalStorage\kiro.kiroagent\...},
* macOS: {@code ~/Library/Application Support/Kiro/...}, Linux:
* {@code ~/.config/Kiro/...}); the CLI variant keeps {@code ~/.local/share/kiro-cli/data.db}
* (Windows: {@code %APPDATA%\kiro-cli\data.db}).</p>
*
* <p>Both layouts store per-turn usage events with real token counts. Kiro
* bills by Bedrock credits, so cost is surface as platform-computed USD where
* available and flagged otherwise.</p>
*
* @author CH
* @since 4.0.0.43
 */
@Spi("kiro")
public class KiroUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = resolveDbPath();

    private static final String SQL_USAGE_EVENTS =
            "SELECT id, session_id, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, cost_usd, created_at "
                    + "FROM usage_events "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    private static final String PROVIDER_KIRO = "kiro";

    /**
    * 解析 Kiro SQLite 数据库路径（按操作系统与安装形态）。
    *
    * @return DB 文件路径
    */
    private static Path resolveDbPath() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "kiro-cli", "data.db");
            }
            return Path.of(System.getProperty("user.home"),
                    "AppData", "Roaming", "kiro-cli", "data.db");
        }
        if (osName.contains("mac")) {
            return Path.of(System.getProperty("user.home"),
                    "Library", "Application Support", "kiro-cli", "data.db");
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "kiro-cli", "data.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "kiro-cli", "data.db");
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
    * 流式解析全部用量事件。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[kiro] database not found: {} (Kiro not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("kiro", DB_PATH.toString());
        return engine.query(SQL_USAGE_EVENTS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[kiro] stream complete"));
    }

    /**
    * 将一条 usage_events 行转换为 AiUsage 记录。
    *
    * @param row 数据库行
    * @return AiUsage 记录
    */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        int cacheWrite = asInt(row.get("cache_write_tokens"));
        double costUsd = asDouble(row.get("cost_usd"));
        long startTime = parseInstantToMillis(asStr(row.get("created_at")));

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_KIRO)
                .model(asStr(row.get("model")))
                .requestId(firstNonBlank(asStr(row.get("id")), asStr(row.get("session_id"))))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null);
        if (costUsd > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(costUsd));
        }
        return builder.build();
    }
}
