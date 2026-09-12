package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * oh-my-pi (omp) usage parser.
 *
 * <p>oh-my-pi (a pi-coding-agent fork/router) writes one append-only JSONL
 * per session under {@code ~/.omp/agent/sessions/}
 * ({@code OMP_HOME} overrides the home). Only {@code type=message} lines
 * with {@code message.role=assistant} carry real token usage:</p>
 *
 * <pre>{@code
 * {
 *   "type": "message",
 *   "id": "a1b2c3d4",
 *   "timestamp": "2026-02-16T10:21:00.000Z",
 *   "message": {
 *     "role": "assistant",
 *     "provider": "anthropic",
 *     "model": "claude-sonnet-4-5",
 *     "timestamp": 1760000000000,
 *     "usage": {
 *       "input": 100, "output": 20, "cacheRead": 0, "cacheWrite": 0,
 *       "totalTokens": 120, "reasoningTokens": 0
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>{@code usage.input} is cache-exclusive; cached input is reported
 * separately via {@code cacheRead}/{@code cacheWrite}. Records are
 * de-duplicated by the line-level 8-char {@code id}. Model is per-message
 * (omp is a router); the fallback is {@code omp-unknown}.</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("omp")
public class OmpUsageParser extends BaseUsageParser {

    private static final String PROVIDER_OMP = "omp";

    private static final String UNKNOWN_MODEL = "omp-unknown";

    private final Path sessionsDir;

    public OmpUsageParser() {
        String home = System.getenv("OMP_HOME");
        Path ompHome = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".omp");
        this.sessionsDir = ompHome.resolve("agent").resolve("sessions");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "omp"}
     */
    @Override
    public String name() {
        return PROVIDER_OMP;
    }

    /**
     * 响应式流式入口：惰性扫描全部会话 JSONL（含子代理转录）。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[omp] no session files under {}", sessionsDir);
            return Flux.empty();
        }
        Set<String> seenIds = ConcurrentHashMap.newKeySet();
        log.info("[omp] scanning {} session files", files.size());
        return Flux.fromIterable(files)
                .flatMap(file -> streamSessionFile(file, seenIds), 4)
                .onErrorResume(e -> {
                    log.debug("[omp] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 枚举 sessions 目录下全部 JSONL 文件。
     *
     * @return 会话文件列表
     */
    private List<Path> listSessionFiles() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sessionsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[omp] walk failed: {}", e.getMessage());
        }
        return files;
    }

    /**
     * 流式解析单个会话文件。
     *
     * @param file    会话文件
     * @param seenIds 已处理的记录 id 集合
     * @return 用量记录流
     */
    private Flux<AiUsage> streamSessionFile(Path file, Set<String> seenIds) {
        return streamLines(file)
                .map(line -> parseLine(line, seenIds))
                .filter(java.util.Objects::nonNull);
    }

    /**
     * 解析单行；非 assistant 用量行、重复 id 返回 null。
     *
     * @param line    单行 JSON
     * @param seenIds 已处理的记录 id 集合
     * @return 用量记录或 null
     */
    private AiUsage parseLine(String line, Set<String> seenIds) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode entry = Json.parse(line);
            if (!"message".equals(entry.get("type").toStringValue())) {
                return null;
            }
            JsonNode msg = entry.get("message");
            if (msg.isMissingValue()
                    || !"assistant".equals(msg.get("role").toStringValue())) {
                return null;
            }
            JsonNode usage = msg.get("usage");
            if (usage.isMissingValue()) {
                return null;
            }
            int input = usage.get("input").toIntValue(0);
            int output = usage.get("output").toIntValue(0);
            int cacheRead = usage.get("cacheRead").toIntValue(0);
            int cacheWrite = usage.get("cacheWrite").toIntValue(0);
            int reasoning = usage.get("reasoningTokens").toIntValue(0);
            if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0) {
                return null;
            }
            String id = entry.get("id").toStringValue();
            if (id != null && !id.isBlank() && !seenIds.add(id)) {
                return null;
            }

            String model = msg.get("model").toStringValue();
            long tsMillis = msg.get("timestamp").toLongValue(0L);
            if (tsMillis <= 0) {
                tsMillis = parseInstantToMillis(entry.get("timestamp").toStringValue());
            }

            int cacheTokens = Math.max(cacheRead, cacheWrite);

            return AiUsage.builder()
                    .provider(PROVIDER_OMP)
                    .model(model != null && !model.isBlank() ? model : UNKNOWN_MODEL)
                    .requestId(id != null ? id : "")
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(input + output + cacheRead + cacheWrite)
                    .cacheTokens(cacheTokens > 0 ? cacheTokens : null)
                    .reasoningTokens(reasoning > 0 ? reasoning : null)
                    .currency("USD")
                    .startTime(tsMillis > 0 ? tsMillis : null)
                    .build();
        } catch (Exception e) {
            log.debug("[omp] line parse failed: {}", e.getMessage());
            return null;
        }
    }
}
