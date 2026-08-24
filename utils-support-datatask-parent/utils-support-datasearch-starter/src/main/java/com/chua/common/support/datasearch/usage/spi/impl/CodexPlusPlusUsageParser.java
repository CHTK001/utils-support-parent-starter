package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/**
 * Codex++ 用量解析器 — 从本地 SQLite 数据库解析会话用量。
 *
 * <p>数据源为 {@code %USERPROFILE%\.codex\state_5.sqlite} 中的 {@code threads} 表。
 * 该表按会话（thread）聚合 tokens_used，但不区分输入/输出缓存粒度。</p>
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
     * 响应式流式入口：订阅时才执行装载，配合 limitRate/take 可控制内存水位。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[codex++] 数据库文件不存在: {}", DB_PATH);
            return Flux.empty();
        }
        return Flux.<AiUsage>create(sink -> {
            long count = 0L;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 PreparedStatement stmt = conn.prepareStatement(SQL_THREADS);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && !sink.isCancelled()) {
                    long startTime = rs.getLong(1);
                    String model = rs.getString(2);
                    String provider = rs.getString(3);
                    int tokensUsed = rs.getInt(4);
                    sink.next(AiUsage.builder()
                            .provider(provider)
                            .model(model)
                            .totalTokens(tokensUsed)
                            .startTime(startTime > 0 ? startTime : null)
                            .build());
                    count++;
                }
                sink.complete();
                log.info("[codex++] 流式解析完成，共 {} 条会话记录", count);
            } catch (SQLException e) {
                log.warn("[codex++] 解析失败: {}", e.getMessage(), e);
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
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
