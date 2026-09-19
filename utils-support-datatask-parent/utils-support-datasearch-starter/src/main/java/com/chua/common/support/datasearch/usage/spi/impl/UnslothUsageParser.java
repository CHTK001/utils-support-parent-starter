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
 * Unsloth Studio 用量解析器。
 *
 * <p>Unsloth Studio 会把可持久化的推理用量写入 SQLite 数据库
 * {@code ~/.unsloth/studio/studio.db}（可用 {@code UNSLOTH_STUDIO_HOME}
 * 覆盖主目录）。共读取两个来源，优先取 chat：</p>
 *
 * <ul>
 *   <li>{@code chat_messages}（role 为 {@code assistant}）—— token 计数位于
 *       {@code metadata_json} 列的 {@code $.contextUsage.*} 下；
 *       模型取自 {@code $.responseDetails.responseModelId}，
 *       依次回退到 {@code $.contextUsage.modelId}，再回退到会话的
 *       {@code model_id}。</li>
 *   <li>{@code api_usage_events} —— 独立的 {@code prompt_tokens}/
 *       {@code completion_tokens}/{@code total_tokens} 列。</li>
 * </ul>
 *
 * <p>token 语义遵循 {@code normalizeLocalStudioTokens}：
 * {@code prompt_tokens} 已包含缓存，因此非缓存输入 = 总量 −
 * completion − cacheRead − cacheWrite；推理 token 包含在
 * {@code completion_tokens} 之内，并单独拆分出来。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("unsloth")
public class UnslothUsageParser extends BaseUsageParser {

    private static final String PROVIDER_UNSLOTH = "unsloth";

    private static final Path DB_PATH = resolveDbPath();

    /**
     * 解析 studio.db 路径。
     *
     * @return 数据库路径
     */
    private static Path resolveDbPath() {
        String studioHome = System.getenv("UNSLOTH_STUDIO_HOME");
        if (studioHome != null && !studioHome.isBlank()) {
            return Path.of(studioHome, "studio.db");
        }
        return Path.of(System.getProperty("user.home"), ".unsloth", "studio", "studio.db");
    }

    /**
     * chat_messages 用量投影：contextUsage 计数 + 三级模型回退。
     */
    private static final String SQL_CHAT_MESSAGES =
            "SELECT 'chat' AS usage_kind, m.id, m.created_at, "
                    + "json_valid(m.metadata_json) AS meta_ok, "
                    + "json_extract(m.metadata_json, '$.responseDetails.responseModelId') AS response_model, "
                    + "json_extract(m.metadata_json, '$.contextUsage.modelId') AS requested_model, "
                    + "json_extract(m.metadata_json, '$.responseDetails.providerType') AS provider_type, "
                    + "t.model_id AS fallback_model, "
                    + "json_extract(m.metadata_json, '$.contextUsage.promptTokens') AS prompt_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.completionTokens') AS completion_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.totalTokens') AS total_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.cachedTokens') AS cached_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.cacheWriteTokens') AS cache_write_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.reasoningTokens') AS reasoning_tokens "
                    + "FROM chat_messages m LEFT JOIN chat_threads t ON t.id = m.thread_id "
                    + "WHERE m.role = 'assistant'";

    /** 无 chat_threads 表时的降级投影。 */
    private static final String SQL_CHAT_MESSAGES_NO_THREAD =
            "SELECT 'chat' AS usage_kind, m.id, m.created_at, "
                    + "json_valid(m.metadata_json) AS meta_ok, "
                    + "json_extract(m.metadata_json, '$.responseDetails.responseModelId') AS response_model, "
                    + "json_extract(m.metadata_json, '$.contextUsage.modelId') AS requested_model, "
                    + "json_extract(m.metadata_json, '$.responseDetails.providerType') AS provider_type, "
                    + "NULL AS fallback_model, "
                    + "json_extract(m.metadata_json, '$.contextUsage.promptTokens') AS prompt_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.completionTokens') AS completion_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.totalTokens') AS total_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.cachedTokens') AS cached_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.cacheWriteTokens') AS cache_write_tokens, "
                    + "json_extract(m.metadata_json, '$.contextUsage.reasoningTokens') AS reasoning_tokens "
                    + "FROM chat_messages m "
                    + "WHERE m.role = 'assistant'";

    /**
     * api_usage_events 标量列投影（chat_messages 缺失时的最终回退）。
     */
    private static final String SQL_API_USAGE_EVENTS =
            "SELECT 'api' AS usage_kind, id, created_at, "
                    + "NULL AS response_model, NULL AS requested_model, "
                    + "NULL AS provider_type, model AS fallback_model, "
                    + "prompt_tokens, completion_tokens, total_tokens, "
                    + "cached_tokens, cache_write_tokens, reasoning_tokens "
                    + "FROM api_usage_events";

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "unsloth"}
     */
    @Override
    public String name() {
        return PROVIDER_UNSLOTH;
    }

    /**
     * 响应式流式入口：优先 chat_messages（含 thread join），逐级降级到
     * 无 join 投影与 api_usage_events。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[unsloth] database not found: {} (Unsloth Studio not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("unsloth", DB_PATH.toString());
        return engine.query(SQL_CHAT_MESSAGES)
                .onErrorResume(e -> engine.query(SQL_CHAT_MESSAGES_NO_THREAD))
                .onErrorResume(e -> engine.query(SQL_API_USAGE_EVENTS))
                .map(this::toAiUsage)
                .filter(java.util.Objects::nonNull)
                .doOnComplete(() -> log.info("[unsloth] stream complete"));
    }

    /**
     * 将用量行映射为记录；全零行返回 null。
     *
     * @param row 数据库行
     * @return 用量记录或 null
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        Tokens t = normalizeTokens(row);
        if (t == null) {
            return null;
        }
        long createdAt = asLong(row.get("created_at"));
        if (createdAt <= 0) {
            createdAt = parseInstantToMillis(asStr(row.get("created_at")));
        }
        String kind = firstNonBlank(asStr(row.get("usage_kind")), "chat");
        String model = resolveModel(row);

        return AiUsage.builder()
                .provider(PROVIDER_UNSLOTH)
                .model(model)
                .requestId(kind + ":" + asStr(row.get("id")))
                .inputTokens(t.input)
                .outputTokens(t.output)
                .totalTokens(t.total)
                .cacheTokens(t.cacheRead > 0 ? t.cacheRead
                        : (t.cacheWrite > 0 ? t.cacheWrite : null))
                .reasoningTokens(t.reasoning > 0 ? t.reasoning : null)
                .currency("USD")
                .estimated(false)
                .startTime(createdAt > 0 ? createdAt : null)
                .build();
    }

    /**
     * 模型名解析：response_model → requested_model → fallback_model；
     * providerType 存在且模型未带前缀时补 {@code providerType/model}。
     *
     * @param row 数据库行
     * @return 模型名
     */
    private String resolveModel(Map<String, Object> row) {
        String model = firstNonBlank(asStr(row.get("response_model")),
                firstNonBlank(asStr(row.get("requested_model")),
                        firstNonBlank(asStr(row.get("fallback_model")), "unsloth")));
        String providerType = asStr(row.get("provider_type"));
        if (!providerType.isBlank() && !model.isBlank() && !model.equals("unsloth")
                && !model.startsWith(providerType + "/")) {
            return providerType + "/" + model;
        }
        return model;
    }

    /**
     * TokenTracker normalizeLocalStudioTokens 口径的令牌归一。
     *
     * @param row 数据库行
     * @return 归一后的令牌计数；总量为 0 返回 null
     */
    private Tokens normalizeTokens(Map<String, Object> row) {
        int prompt = asInt(row.get("prompt_tokens"));
        int completion = asInt(row.get("completion_tokens"));
        int total = Math.max(asInt(row.get("total_tokens")), prompt + completion);
        if (total <= 0) {
            return null;
        }
        int cacheRead = Math.min(prompt, Math.max(0, asInt(row.get("cached_tokens"))));
        int cacheWrite = Math.min(Math.max(0, prompt - cacheRead),
                Math.max(0, asInt(row.get("cache_write_tokens"))));
        int reasoning = Math.min(completion, Math.max(0, asInt(row.get("reasoning_tokens"))));
        Tokens t = new Tokens();
        t.total = total;
        t.input = Math.max(0, total - completion - cacheRead - cacheWrite);
        t.output = Math.max(0, completion - reasoning);
        t.cacheRead = cacheRead;
        t.cacheWrite = cacheWrite;
        t.reasoning = reasoning;
        return t;
    }

    /** 归一后的令牌计数载体。 */
    private static class Tokens {
        private int input;
        private int output;
        private int total;
        private int cacheRead;
        private int cacheWrite;
        private int reasoning;
    }
}
