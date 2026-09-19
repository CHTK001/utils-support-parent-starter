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
 * Codex++ 用量解析器 — 从本地 sqlite 数据库解析会话用量。
 *
 * <p>数据源为 {@code %USERPROFILE%\.codex\state_5.sqlite} 中的 {@code threads} 表。
 * 该表按会话（thread）聚合 令牌_used，但不区分输入/输出缓存粒度。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codex++")
public class CodexPlusPlusUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".codex", "state_5.sqlite");

    private static final String SQL_THREADS =
            "SELECT created_at_ms, model, model_provider, tokens_used FROM threads "
                    + "WHERE tokens_used > 0 ORDER BY created_at_ms ASC";

    /**
     * 响应式流式入口：通过 sqlitereactorengine 流出会话记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[codex++] 数据库文件不存在: {}", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("codex", DB_PATH.toString());
        return engine.query(SQL_THREADS)
                .map(row -> AiUsage.builder()
                        .provider(asStr(row.get("model_provider")))
                        .model(asStr(row.get("model")))
                        .totalTokens(asInt(row.get("tokens_used")))
                        .startTime(asLong(row.get("created_at_ms")) > 0
                                ? asLong(row.get("created_at_ms")) : null)
                        .build())
                .doOnComplete(() -> log.info("[codex++] 流式解析完成"));
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "codex++"}
     */
    @Override
    public String name() {
        return "codex++";
    }
}
