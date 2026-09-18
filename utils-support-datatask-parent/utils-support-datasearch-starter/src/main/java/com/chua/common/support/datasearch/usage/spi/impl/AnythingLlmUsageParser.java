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
 * AnythingLLM Desktop（Mintplex Labs）用量解析器。
 *
 * <p>AnythingLLM Desktop 把聊天历史存放在 SQLite 数据库中，其
 * {@code workspace_chats} 表在 {@code response} JSON 列（{@code $.metrics.*}）
 * 内记录了每条消息的 token 指标，该字段自 1.7.1 版本起提供：</p>
 *
 * <pre>{@code
 * metrics = { "prompt_tokens": 120, "completion_tokens": 45,
 *             "total_tokens": 165, "model": "gpt-5-mini" }
 * }</pre>
 *
 * <p>数据库位置：Windows 下为 {@code %APPDATA%\anythingllm-desktop\storage\anythingllm.db}，
 * macOS 下为 {@code ~/Library/Application Support/...}，
 * Linux 下为 {@code $XDG_CONFIG_HOME/anythingllm-desktop/storage/anythingllm.db}。
 * {@code createdAt} 为 Prisma 写入的 epoch 毫秒值（为兼容也接受秒）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("anythingllm")
public class AnythingLlmUsageParser extends BaseUsageParser {

    private static final String PROVIDER_ANYTHINGLLM = "anythingllm";

    private static final Path DB_PATH = resolveDbPath();

    /**
    * 解析 anythingllm.db 路径（按平台）。
    *
    * @return 数据库路径
    */
    private static Path resolveDbPath() {
        String override = System.getenv("ANYTHINGLLM_DB");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "anythingllm-desktop", "storage", "anythingllm.db");
            }
            return Path.of(System.getProperty("user.home"),
                    "AppData", "Roaming", "anythingllm-desktop", "storage", "anythingllm.db");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(System.getProperty("user.home"), "Library",
                    "Application Support", "anythingllm-desktop", "storage", "anythingllm.db");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path configHome = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return configHome.resolve("anythingllm-desktop").resolve("storage").resolve("anythingllm.db");
    }

    private static final String SQL_WORKSPACE_CHATS =
            "SELECT id, createdAt, "
                    + "json_extract(response, '$.metrics.prompt_tokens') AS prompt_tokens, "
                    + "json_extract(response, '$.metrics.completion_tokens') AS completion_tokens, "
                    + "json_extract(response, '$.metrics.total_tokens') AS total_tokens, "
                    + "json_extract(response, '$.metrics.model') AS model "
                    + "FROM workspace_chats "
                    + "WHERE json_extract(response, '$.metrics.total_tokens') > 0 "
                    + "ORDER BY id ASC";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "anythingllm"}
    */
    @Override
    public String name() {
        return PROVIDER_ANYTHINGLLM;
    }

    /**
    * 响应式流式入口：流出 workspace_chats 的逐消息用量。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[anythingllm] database not found: {} (AnythingLLM not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("anythingllm", DB_PATH.toString());
        return engine.query(SQL_WORKSPACE_CHATS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[anythingllm] stream complete"));
    }

    /**
    * 将 workspace_chats 行映射为用量记录。
    *
    * @param row 数据库行
    * @return 用量记录
    */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int prompt = asInt(row.get("prompt_tokens"));
        int completion = asInt(row.get("completion_tokens"));
        int total = Math.max(asInt(row.get("total_tokens")), prompt + completion);
        if (total <= 0) {
            return null;
        }
        long createdAt = asLong(row.get("createdAt"));
        if (createdAt <= 0) {
            createdAt = parseInstantToMillis(asStr(row.get("createdAt")));
        }
        // Prisma 存 epoch 毫秒；旧库可能写秒
        if (createdAt > 0 && createdAt < 100_000_000_000L) {
            createdAt *= 1000L;
        }
        String model = firstNonBlank(asStr(row.get("model")), "unknown");

        return AiUsage.builder()
                .provider(PROVIDER_ANYTHINGLLM)
                .model(model)
                .requestId(asStr(row.get("id")))
                .inputTokens(prompt)
                .outputTokens(completion)
                .totalTokens(total)
                .currency("USD")
                .estimated(true)
                .startTime(createdAt > 0 ? createdAt : null)
                .build();
    }
}
