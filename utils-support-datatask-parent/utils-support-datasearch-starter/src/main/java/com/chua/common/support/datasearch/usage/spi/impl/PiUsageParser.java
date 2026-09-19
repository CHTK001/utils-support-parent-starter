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
import java.util.stream.Stream;

/**
 * Pi (Stochastic Labs / pi 路由Agent) 用量解析器。
 *
 * <p>Pi 将会话转录写入 {@code ~/.pi/agent/sessions/<cwd>/<sessionId>.jsonl}
 * （{@code TOKENTRACKER_PI_AGENT_DIR} / {@code PI_CODING_AGENT_DIR} 覆盖）。
 * 每行 {@code type=message} 且 {@code role=assistant} 携带用量块。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("pi")
public class PiUsageParser extends BaseUsageParser {

    private static final String PROVIDER_PI = "pi";
    private static final String DEFAULT_MODEL = "pi-unknown";

    private final Path agentDir;

    /**
     * 默认构造器。
    */
    public PiUsageParser() {
        String explicit = System.getenv("TOKENTRACKER_PI_AGENT_DIR");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("PI_CODING_AGENT_DIR");
        }
        Path piHome = (explicit != null && !explicit.isBlank())
                ? Path.of(explicit)
                : Path.of(System.getProperty("user.home"), ".pi");
        this.agentDir = piHome.resolve("agent");
    }

    @Override
    public String name() {
        return PROVIDER_PI;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[pi] no session files under {}", agentDir);
            return Flux.empty();
        }
        log.info("[pi] scanning {} session files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamFile, 4)
                .onErrorResume(e -> {
                    log.debug("[pi] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 流文件。
     *
     * @param file 文件，不允许为 null
     * @return Flux 对象
     */
    private Flux<AiUsage> streamFile(Path file) {
        java.util.List<AiUsage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.toList()) {
                AiUsage usage = parseLine(line);
                if (usage != null) {
                    out.add(usage);
                }
            }
        } catch (IOException e) {
            log.debug("[pi] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return Flux.fromIterable(out);
    }

    /**
     * 解析Line。
     *
     * @param line 方法入参 line
     * @return AiUsage 对象
     */
    private AiUsage parseLine(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode entry = Json.parse(line);
            JsonNode msg = entry.get("message");
            if (msg.isMissingValue() || !"assistant".equals(msg.get("role").toStringValue())) {
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
            if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && reasoning <= 0) {
                return null;
            }
            String id = entry.get("id").toStringValue();
            if (id == null || id.isBlank()) {
                return null;
            }
            long tsMillis = toMillis(entry, msg);
            if (tsMillis <= 0) {
                return null;
            }
            int total = usage.get("totalTokens").toIntValue(input + output + cacheRead + cacheWrite + reasoning);
            String provider = msg.get("provider").toStringValue();
            String model = msg.get("model").toStringValue();
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            Integer cacheTokens = cacheRead > 0 ? Integer.valueOf(cacheRead) : (cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null);
            Integer reasoningTokens = reasoning > 0 ? Integer.valueOf(reasoning) : null;
            return AiUsage.builder()
                    .provider(firstNonBlank(provider, PROVIDER_PI))
                    .model(model)
                    .requestId(id)
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total)
                    .cacheTokens(cacheTokens)
                    .reasoningTokens(reasoningTokens)
                    .currency("USD")
                    .estimated(false)
                    .startTime(tsMillis)
                    .build();
        } catch (Exception e) {
            log.debug("[pi] line parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 转为毫秒数。
     *
     * @param entry 条目，不允许为 null
     * @param msg 消息，不允许为 null
     * @return 结果数值
     */
    private long toMillis(JsonNode entry, JsonNode msg) {
        JsonNode tsNode = msg.get("timestamp");
        if (tsNode.isMissingValue()) {
            tsNode = entry.get("timestamp");
        }
        if (tsNode == null || tsNode.isMissingValue()) {
            return 0L;
        }
        long raw = tsNode.toLongValue(0L);
        if (raw > 0) {
            return raw > 1_000_000_000_000L ? raw : raw * 1000L;
        }
        return parseInstantToMillis(tsNode.toStringValue());
    }

    /**
     * 列出会话Files。
     *
     * @return 结果列表，无数据时为空列表
     */
    private List<Path> listSessionFiles() {
        Path sessionsDir = agentDir.resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sessionsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[pi] walk failed: {}", e.getMessage());
        }
        return files.stream().sorted().toList();
    }
}
