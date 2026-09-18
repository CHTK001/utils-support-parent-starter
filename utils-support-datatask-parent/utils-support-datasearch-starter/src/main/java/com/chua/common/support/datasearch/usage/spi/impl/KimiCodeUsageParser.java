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
 * Kimi Code (Moonshot 官方 CLI) 用量解析器。
 *
 * <p>官方单二进制产品将会话转录写入
 * {@code ~/.kimi-code/sessions/<wd_dir_hash>/<session_id>/agents/<name>/wire.jsonl}
 * （{@code KIMI_CODE_HOME} 覆盖主目录）。每步令牌用量挂在
 * {@code context.append_loop_event} 包装的 {@code step.end} 上。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kimi-code")
public class KimiCodeUsageParser extends BaseUsageParser {

    private static final String PROVIDER_KIMI_CODE = "kimi-code";
    private static final String DEFAULT_MODEL = "kimi-code";

    private final Path kimiCodeHome;

    /** 默认构造器。 */
    public KimiCodeUsageParser() {
        String home = System.getenv("KIMI_CODE_HOME");
        this.kimiCodeHome = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".kimi-code");
    }

    @Override
    public String name() {
        return PROVIDER_KIMI_CODE;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listWireFiles();
        if (files.isEmpty()) {
            log.debug("[kimi-code] no wire.jsonl under {}", kimiCodeHome);
            return Flux.empty();
        }
        log.info("[kimi-code] scanning {} wire files", files.size());
        Set<String> seenUuids = ConcurrentHashMap.newKeySet();
        return Flux.fromIterable(files)
                .flatMap(file -> streamWireFile(file, seenUuids), 4)
                .onErrorResume(e -> {
                    log.debug("[kimi-code] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /** 直接读取全量行再逐行解析，避免 Flux 流式 emit 丢失行。 */
    private Flux<AiUsage> streamWireFile(Path file, Set<String> seenUuids) {
        String model = resolveModel(file);
        List<AiUsage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file)) {
            for (String line : lines.toList()) {
                AiUsage usage = parseLine(line, model, seenUuids);
                if (usage != null) {
                    out.add(usage);
                }
            }
        } catch (IOException e) {
            log.debug("[kimi-code] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return Flux.fromIterable(out);
    }

    /**
    * 解析单行；仅提取 step.end 用量事件。
    *
    * @param line      单行 JSON
    * @param model     模型名
    * @param seenUuids 已处理的 step uuid 集合
    * @return 用量记录；非目标行返回 null
    */
    private AiUsage parseLine(String line, String model, Set<String> seenUuids) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = Json.parse(line);
            if (!"config.update".equals(node.get("type").toStringValue())
                    && !"context.append_loop_event".equals(node.get("type").toStringValue())) {
                return null;
            }
            JsonNode event = node.get("event");
            if (event.isMissingValue() || !"step.end".equals(event.get("type").toStringValue())) {
                return null;
            }
            JsonNode usage = event.get("usage");
            if (usage.isMissingValue()) {
                return null;
            }
            String uuid = event.get("uuid").toStringValue();
            if (uuid == null || uuid.isBlank() || !seenUuids.add(uuid)) {
                return null;
            }
            int input = usage.get("input_tokens").toIntValue(0);
            int output = usage.get("output_tokens").toIntValue(0);
            int cacheRead = usage.get("cache_read_input_tokens").toIntValue(0);
            int cacheWrite = usage.get("cache_creation_input_tokens").toIntValue(0);
            if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0) {
                return null;
            }
            long timeMs = toEpochMillis(node.get("time"));
            if (timeMs <= 0) {
                return null;
            }
            int total = input + output + cacheRead + cacheWrite;
            return AiUsage.builder()
                    .provider(PROVIDER_KIMI_CODE)
                    .model(model)
                    .requestId(uuid)
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total)
                    .cacheTokens(cacheRead > 0 ? cacheRead
                            : (cacheWrite > 0 ? cacheWrite : null))
                    .currency("USD")
                    .estimated(false)
                    .startTime(timeMs)
                    .build();
        } catch (Exception e) {
            log.debug("[kimi-code] line parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String resolveModel(Path wireFile) {
        String session = DEFAULT_MODEL;
        try (Stream<String> lines = Files.lines(wireFile)) {
            java.util.Optional<String> candidate = lines.map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .map(l -> {
                        try {
                            JsonNode node = Json.parse(l);
                            if ("config.update".equals(node.get("type").toStringValue())) {
                                return node;
                            }
                            JsonNode event = node.get("event");
                            if (event.isMissingValue()
                                    || !"config.update".equals(event.get("type").toStringValue())) {
                                return null;
                            }
                            return node;
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .map(node -> {
                        String alias = node.get("modelAlias").toStringValue();
                        if (alias == null || alias.isBlank()) {
                            alias = node.get("event").get("modelAlias").toStringValue();
                        }
                        return alias;
                    })
                    .filter(a -> a != null && !a.isBlank())
                    .findFirst();
            if (candidate.isPresent()) {
                String alias = candidate.get();
                int slash = alias.lastIndexOf('/');
                session = slash >= 0 ? alias.substring(slash + 1) : alias;
            }
        } catch (IOException e) {
            log.debug("[kimi-code] wire read failed: {}", e.getMessage());
        }
        return session.isEmpty() ? DEFAULT_MODEL : session;
    }

    private long toEpochMillis(JsonNode node) {
        if (node == null || node.isMissingValue()) {
            return 0L;
        }
        long raw = node.toLongValue(0L);
        if (raw > 0) {
            return raw > 1_000_000_000_000L ? raw : raw * 1000L;
        }
        return parseInstantToMillis(node.toStringValue());
    }

    private List<Path> listWireFiles() {
        Path sessionsDir = kimiCodeHome.resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sessionsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> "wire.jsonl".equals(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[kimi-code] walk failed: {}", e.getMessage());
        }
        return files;
    }
}
