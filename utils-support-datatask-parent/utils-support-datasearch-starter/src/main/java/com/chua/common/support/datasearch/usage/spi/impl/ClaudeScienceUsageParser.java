package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Claude Science (Anthropic 本地研究工平台) 用量解析器。
 *
 * <p>数据源为 {@code ~/.claude-science/operon-cli.db}（或旧名 {@code operon.db}）中
 * 的 {@code frames} 表，每帧（frame）携带真实输入/输出/缓存令牌计数。
 * 支持 {@code CLAUDE_SCIENCE_DB_PATH} 显式指定数据库文件。</p>
 *
 * <p>令牌口径对齐 TokenTracker {@code normalizeClaudeScienceTokens}：
 * {@code input_tokens} 含缓存，需拆出非缓存输入（主 + aux 组独立扣减缓存）；
 * {@code output_tokens} 与 {@code aux_output_tokens} 合并；推理令牌计入输出。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("claude-science")
public class ClaudeScienceUsageParser extends BaseUsageParser {

    private static final String PROVIDER = "claude-science";

    /** 两种可能的数据库文件名（新名 + 旧名）。 */
    private static final String[] DB_FILENAMES = {"operon-cli.db", "operon.db"};

    /** 令牌列（新版必含主列；aux 等其余列为可选，缺失时按 0 计）。 */
    private static final String[] TOKEN_COLUMNS = {
        "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens",
        "aux_input_tokens", "aux_output_tokens", "aux_cache_read_tokens", "aux_cache_write_tokens"
    };

    /**
     * 解析 DB 文件路径。
     * @return 数据库文件路径；未找到返回 null
     */
    private static Path resolveDbPath() {
        String explicit = System.getenv("CLAUDE_SCIENCE_DB_PATH");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        Path root = Path.of(System.getProperty("user.home"), ".claude-science");
        for (String name : DB_FILENAMES) {
            Path candidate = root.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return root.resolve(DB_FILENAMES[0]);
    }

    private final Path dbPath;

    /** 默认构造器。 */
    public ClaudeScienceUsageParser() {
        this.dbPath = resolveDbPath();
    }

    @Override
    public String name() {
        return PROVIDER;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        return Flux.defer(() -> Flux.fromIterable(parseAll()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 全量解析 frames 表。 */
    @Override
    protected List<AiUsage> parseAll() {
        if (!Files.exists(dbPath)) {
            log.debug("[claude-science] 数据库文件不存在: {}", dbPath);
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            List<String> present = collectPresentColumns(conn);
            if (present.isEmpty()) {
                log.debug("[claude-science] frames 表不存在或无令牌列: {}", dbPath);
                return List.of();
            }
            try (PreparedStatement stmt = conn.prepareStatement(buildQuery(present))) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        AiUsage usage = toAiUsage(rs, present);
                        if (usage != null) {
                            result.add(usage);
                        }
                    }
                }
            }
            log.info("[claude-science] 解析完成，共 {} 条用量记录", result.size());
        } catch (SQLException e) {
            log.warn("[claude-science] 解析失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /** 探测 frames 表实际存在的令牌列。 */
    private List<String> collectPresentColumns(Connection conn) throws SQLException {
        List<String> present = new ArrayList<>();
        Set<String> columns = new HashSet<>();
        try (PreparedStatement stmt = conn.prepareStatement("PRAGMA table_info(frames)");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = asStr(rs.getObject("name"));
                if (!name.isEmpty()) {
                    columns.add(name);
                }
            }
        } catch (SQLException e) {
            log.debug("[claude-science] PRAGMA 探测失败: {}", e.getMessage());
            return List.of();
        }
        if (columns.isEmpty()) {
            return List.of();
        }
        for (String col : TOKEN_COLUMNS) {
            if (columns.contains(col)) {
                present.add(col);
            }
        }
        return present;
    }

    /** 构建可选的令牌列投影查询。 */
    private static String buildQuery(List<String> present) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, parent_frame_id, model, created_at, updated_at, completed_at");
        for (String col : present) {
            sql.append(", ").append(col);
        }
        sql.append(" FROM frames");
        StringBuilder where = new StringBuilder();
        for (String col : present) {
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("COALESCE(").append(col).append(", 0) <> 0");
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY created_at, id");
        return sql.toString();
    }

    private AiUsage toAiUsage(ResultSet rs, List<String> present) throws SQLException {
        int mainInput = getInt(rs, present, "input_tokens");
        int mainOutput = getInt(rs, present, "output_tokens");
        int mainCacheRead = getInt(rs, present, "cache_read_tokens");
        int mainCacheWrite = getInt(rs, present, "cache_write_tokens");
        int auxInput = getInt(rs, present, "aux_input_tokens");
        int auxOutput = getInt(rs, present, "aux_output_tokens");
        int auxCacheRead = getInt(rs, present, "aux_cache_read_tokens");
        int auxCacheWrite = getInt(rs, present, "aux_cache_write_tokens");

        int uncachedInput = Math.max(0, mainInput - mainCacheRead - mainCacheWrite)
                + Math.max(0, auxInput - auxCacheRead - auxCacheWrite);
        int output = mainOutput + auxOutput;
        int cacheRead = mainCacheRead + auxCacheRead;
        int cacheWrite = mainCacheWrite + auxCacheWrite;
        int total = uncachedInput + output + cacheRead + cacheWrite;
        if (total <= 0) {
            return null;
        }

        String frameId = asStr(rs.getObject("id"));
        String parentFrameId = asStr(rs.getObject("parent_frame_id"));
        long startTime = timestampToMillis(asStr(rs.getObject("completed_at")),
                asStr(rs.getObject("updated_at")), asStr(rs.getObject("created_at")));

        return AiUsage.builder()
                .provider(PROVIDER)
                .model(firstNonBlank(asStr(rs.getObject("model")), "claude-science"))
                .requestId(frameId)
                .inputTokens(uncachedInput)
                .outputTokens(output)
                .totalTokens(total)
                .cacheTokens(cacheRead > 0 ? cacheRead
                        : (cacheWrite > 0 ? cacheWrite : null))
                .estimated(false)
                .finishReason(parentFrameId == null || parentFrameId.isBlank() ? "root-frame" : "child-frame")
                .startTime(startTime > 0 ? startTime : null)
                .build();
    }

    private int getInt(ResultSet rs, List<String> present, String column) {
        if (!present.contains(column)) {
            return 0;
        }
        try {
            return asInt(rs.getObject(column));
        } catch (SQLException e) {
            return 0;
        }
    }

    private long timestampToMillis(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                long epoch = Long.parseLong(value.trim());
                if (epoch > 0) {
                    return epoch > 1_000_000_000_000L ? epoch : epoch * 1000L;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            long iso = parseInstantToMillis(value);
            if (iso > 0) {
                return iso;
            }
        }
        return 0L;
    }
}