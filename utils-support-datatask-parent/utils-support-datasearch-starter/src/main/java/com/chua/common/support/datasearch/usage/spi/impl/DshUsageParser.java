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
 * DeepSeek Harness 用量解析器（best-effort）。
 *
 * <p>数据源为 {@code ~/.dsh/sessions/<项目>/<会话>/session.jsonl}
 * （{@code DSH_HOME} / {@code TOKENTRACKER_DSH_HOME} 覆盖）。会话为事件流 JSONL，
 * 每次 {@code assistant/message} 事件在 {@code data.usage} 携带真实令牌计数。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("dsh")
public class DshUsageParser extends BaseUsageParser {

    private static final String PROVIDER_DSH = "dsh";
    private static final String DEFAULT_MODEL = "dsh-unknown";

    private final Path dshHome;

    /** 默认构造器。 */
    public DshUsageParser() {
        String home = System.getenv("DSH_HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("TOKENTRACKER_DSH_HOME");
        }
        this.dshHome = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".dsh");
    }

    @Override
    public String name() {
        return PROVIDER_DSH;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[dsh] no session.jsonl under {}", dshHome);
            return Flux.empty();
        }
        log.info("[dsh] scanning {} session files", files.size());
        return Flux.fromIterable(files)
                .map(this::parseSessionFile)
                .flatMapIterable(l -> l)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.debug("[dsh] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 解析会话文件。
     *
     * @param file 文件，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<AiUsage> parseSessionFile(Path file) {
        List<AiUsage> result = new ArrayList<>();
        String headerModel = null;
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.map(String::trim).toList()) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode node = Json.parse(line);
                    String type = node.get("type").toStringValue();
                    if ("session".equals(type)) {
                        continue;
                    }
                    if ("request/header".equals(type)) {
                        JsonNode data = node.get("data");
                        if (!data.isMissingValue()) {
                            String model = data.get("header").get("config").get("model").toStringValue();
                            if (model != null && !model.isBlank()) {
                                headerModel = model;
                            }
                        }
                        continue;
                    }
                    if (!"assistant/message".equals(type)) {
                        continue;
                    }
                    JsonNode data = node.get("data");
                    if (data.isMissingValue() || data.get("usage").isMissingValue()) {
                        continue;
                    }
                    JsonNode usage = data.get("usage");
                    int input = usage.get("inputTokens").toIntValue(0);
                    int output = usage.get("outputTokens").toIntValue(0);
                    int cacheRead = usage.get("cacheReadTokens").toIntValue(0);
                    int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
                    int reasoning = usage.get("reasoningTokens").toIntValue(0);
                    if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && reasoning <= 0) {
                        continue;
                    }
                    long timeMs = node.get("time").toLongValue(0L);
                    if (timeMs <= 0) {
                        continue;
                    }
                    String model = data.get("message").get("source").get("model").toStringValue();
                    if (model == null || model.isBlank()) {
                        model = headerModel;
                    }
                    if (model == null || model.isBlank()) {
                        model = DEFAULT_MODEL;
                    }
                    int total = input + output + cacheRead + cacheWrite + reasoning;
                    Integer cacheTokens = cacheRead > 0 ? Integer.valueOf(cacheRead)
                            : (cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null);
                    Integer reasoningTokens = reasoning > 0 ? Integer.valueOf(reasoning) : null;
                    result.add(AiUsage.builder()
                            .provider(PROVIDER_DSH)
                            .model(model)
                            .requestId(node.get("seq").toStringValue() + ":" + file.getFileName())
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(total)
                            .cacheTokens(cacheTokens)
                            .reasoningTokens(reasoningTokens)
                            .currency("USD")
                            .estimated(false)
                            .startTime(timeMs)
                            .build());
                } catch (Exception e) {
                    log.debug("[dsh] line parse failed: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[dsh] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return result;
    }

    /**
     * 列出会话Files。
     *
     * @return 结果列表，无数据时为空列表
     */
    private List<Path> listSessionFiles() {
        Path sessionsRoot = dshHome.resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sessionsRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> "session.jsonl".equals(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[dsh] walk failed: {}", e.getMessage());
        }
        return files.stream().sorted().toList();
    }
}