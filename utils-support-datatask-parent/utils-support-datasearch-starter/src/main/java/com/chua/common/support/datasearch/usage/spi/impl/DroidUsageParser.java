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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Droid (Factory) usage parser.
 *
 * <p>Droid (Factory's CLI) persists session settings under
 * {@code ~/.factory/sessions/}: each session keeps a
 * {@code settings.json} with accumulated token totals and a session
 * header carrying the model + provider. The session file is also a
 * JSONL event log: lines carrying {@code tokenUsage} / {@code usage}
 * blocks report per-turn real usage.</p>
 *
 * <p>This parser reads both the settings.json cumulative counters and
 * the per-turn usage blocks, normalizing Droid's model names
 * (e.g. {@code "custom:GLM-5.1-[Proxy]-0"} becomes {@code "glm-5-1-0"})
 * for cross-tool comparison.</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("droid")
public class DroidUsageParser extends BaseUsageParser {

    private static final String PROVIDER_DROID = "droid";

    private static final Path FACTORY_HOME = Path.of(
            System.getProperty("user.home"), ".factory");

    private static final Path SESSIONS_DIR = FACTORY_HOME.resolve("sessions");

    /** Droid model 名归一：去掉 custom: 前缀与 [Proxy] 括注。 */
    private static final Pattern PROXY_BRACKET =
            Pattern.compile("\\[[^\\]]*\\]");

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "droid"}
     */
    @Override
    public String name() {
        return PROVIDER_DROID;
    }

    /**
    * 流式解析全部 Droid 会话文件（settings.json 与 JSONL 事件流）。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[droid] no session files under {}", SESSIONS_DIR);
            return Flux.empty();
        }
        log.info("[droid] streaming {} session files", files.size());
        return Flux.fromIterable(files)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(file -> Flux.fromIterable(parseFile(file)), 4);
    }

    /**
    * 枚举 {@code ~/.factory/sessions} 下的会话文件
    * （*.settings.json 与事件 JSONL）。
    *
    * @return 会话文件列表
     */
    private List<Path> listSessionFiles() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(SESSIONS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".settings.json")
                                || (n.endsWith(".jsonl") && !n.contains(".checkpoints."));
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[droid] walk {} failed: {}", SESSIONS_DIR, e.getMessage());
        }
        return files;
    }

    /**
    * 解析单个会话文件：settings.json 取累计计数，JSONL 取逐回合用量。
    *
    * @param file 会话文件
    * @return 用量记录列表
     */
    private List<AiUsage> parseFile(Path file) {
        List<AiUsage> result = new ArrayList<>();
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".settings.json")) {
            parseSettings(file).ifPresent(result::add);
        } else {
            try (var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    parseLineSafe(line, file).ifPresent(result::add);
                }
            } catch (IOException e) {
                log.debug("[droid] read {} failed: {}", file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    private Optional<AiUsage> parseLineSafe(String line, Path file) {
        try {
            return parseLine(line, file);
        } catch (Exception e) {
            log.debug("[droid] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
    * 解析一条 JSONL 事件行：接受 {@code tokenUsage} 或顶层 {@code usage} 块。
    *
    * @param line JSONL 行
    * @param file 所属文件（用于 requestId 兜底）
    * @return 用量记录或 empty
     */
    private Optional<AiUsage> parseLine(String line, Path file) {
        JsonNode node = Json.parse(line);
        JsonNode usage = node.get("tokenUsage");
        if (usage.isMissingValue()) {
            usage = node.get("usage");
        }
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        int inputTokens = usage.get("inputTokens").toIntValue(
                usage.get("promptTokens").toIntValue(-1));
        int outputTokens = usage.get("outputTokens").toIntValue(
                usage.get("completionTokens").toIntValue(-1));
        if (inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        int cacheRead = usage.get("cacheReadTokens").toIntValue(
                usage.get("cache_read_input_tokens").toIntValue(0));
        int reasoning = usage.get("reasoningTokens").toIntValue(
                usage.get("reasoning_output_tokens").toIntValue(0));

        String model = firstNonBlank(
                usage.get("model").toStringValue(),
                node.get("model").toStringValue());
        model = normalizeDroidModel(model);
        String provider = firstNonBlank(
                node.get("provider").toStringValue(),
                PROVIDER_DROID);

        long startTime = node.get("timestamp").toLongValue(0L);
        if (startTime > 0 && startTime < 10_000_000_000L) {
            startTime = startTime * 1000L;
        }

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(provider)
                .model(model.isBlank() ? "unknown" : model)
                .requestId(file.getFileName().toString())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .reasoningTokens(reasoning > 0 ? reasoning : null)
                .startTime(startTime > 0 ? startTime : null);
        return Optional.of(builder.build());
    }

    /**
    * 解析 settings.json 的累计计数块（会话级聚合）。
    *
    * @param file settings.json 文件
    * @return 单条会话级用量记录或 empty
     */
    private Optional<AiUsage> parseSettings(Path file) {
        try {
            JsonNode node = Json.parse(Files.readString(file));
            int input = node.get("totalInputTokens").toIntValue(
                    node.get("inputTokens").toIntValue(0));
            int output = node.get("totalOutputTokens").toIntValue(
                    node.get("outputTokens").toIntValue(0));
            if (input <= 0 && output <= 0) {
                return Optional.empty();
            }
            String model = normalizeDroidModel(node.get("model").toStringValue());
            return Optional.of(AiUsage.builder()
                    .provider(PROVIDER_DROID)
                    .model(model.isBlank() ? "unknown" : model)
                    .requestId(file.getFileName().toString().replace(".settings.json", ""))
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(input + output)
                    .estimated(true)
                    .finishReason("session-cumulative")
                    .build());
        } catch (Exception e) {
            log.debug("[droid] settings parse failed {}: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
    * 归一化 Droid 模型名：去掉 {@code custom:} 前缀与 {@code [Proxy]} 类括注，
    * 空白/点/连字符折叠为单一连字符。
    *
    * @param raw 原始模型名
    * @return 归一化模型名；空白返回空串
     */
    private String normalizeDroidModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.startsWith("custom:") ? raw.substring(7) : raw;
        s = PROXY_BRACKET.matcher(s).replaceAll("");
        s = s.toLowerCase().replace(' ', '-').replace('.', '-');
        s = s.replaceAll("-+", "-");
        return s.replaceAll("^-+|-+$", "");
    }
}
