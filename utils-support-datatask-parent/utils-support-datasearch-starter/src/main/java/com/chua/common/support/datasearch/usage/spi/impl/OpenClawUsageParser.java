package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OpenClaw usage parser.
 *
 * <p>OpenClaw records each agent run as a trajectory JSONL under
 * {@code ~/.openclaw/agents/<agent>/sessions/<id>.trajectory.jsonl}.
 * Every LLM completion emits a {@code model.completed} trace carrying real
 * per-call usage:</p>
 *
 * <pre>{@code
 * {
 *   "type": "model.completed",
 *   "ts": "2026-07-04T07:16:40.531Z",
 *   "sessionId": "7284d6f1-...",
 *   "provider": "custom-custom9d",
 *   "modelId": "deepseek-v4-flash",
 *   "usage": { "input": 79086, "output": 6, "cacheRead": 4096, "total": 83188 }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("openclaw")
public class OpenClawUsageParser extends BaseUsageParser {

    private static final Path OPENCLAW_DIR = Path.of(
            System.getProperty("user.home"), ".openclaw");

    private static final String PROVIDER_OPENCLAW = "openclaw";

    /**
    * 返回 OpenClaw 的 SPI 名称。
    *
    * @return {@code "openclaw"}
    */
    public String name() {
        return "openclaw";
    }

    /**
    * 从所有转录文件中以流式方式输出按补全粒度的用量记录。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTrajectoryFiles();
        if (files.isEmpty()) {
            log.debug("[openclaw] no trajectory files under {}", OPENCLAW_DIR);
            return Flux.empty();
        }
        log.info("[openclaw] streaming from {} trajectory files", files.size());
        return Flux.fromIterable(files)
                .flatMap(this::streamTrajectoryFile, 4);
    }

    /**
     * 流Trajectory文件。
     *
     * @param file 文件，不允许为 null
     * @return Flux 对象
     */
    private Flux<AiUsage> streamTrajectoryFile(Path file) {
        return streamLines(file)
                .flatMap(line -> Mono.fromCallable(() -> parseLine(line))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(Mono::justOrEmpty),
                        16)
                .onErrorResume(e -> {
                    log.debug("[openclaw] read failed {}: {}", file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 列出TrajectoryFiles。
     *
     * @return 结果列表，无数据时为空列表
     */
    private List<Path> listTrajectoryFiles() {
        Path agentsDir = OPENCLAW_DIR.resolve("agents");
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var agents = Files.list(agentsDir)) {
            for (Path agent : (Iterable<Path>) agents::iterator) {
                Path sessions = agent.resolve("sessions");
                if (!Files.isDirectory(sessions)) {
                    continue;
                }
                try (var stream = Files.list(sessions)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".trajectory.jsonl"))
                            .forEach(files::add);
                }
            }
        } catch (IOException e) {
            log.warn("[openclaw] scan failed: {}", e.getMessage(), e);
        }
        return files;
    }

    /**
    * 定位 model.completed 事件的用量块。
    *
    * <p>OpenClaw 把用量嵌套在 {@code data.usage} 下；较早的 schema 可能把它
    * 放在顶层，因此两个位置都要检查。</p>
    *
    * @param node 已解析的转录行
    * @return 用量块；不存在时返回 missing 节点
    */
    private JsonNode readUsage(JsonNode node) {
        JsonNode data = node.get("data");
        if (!data.isMissingValue() && !data.get("usage").isMissingValue()) {
            return data.get("usage");
        }
        return node.get("usage");
    }

    /**
     * 解析Line。
     *
     * @param line 方法入参 line
     * @return 可选结果，不存在时为 Optional.empty()
     */
    private Optional<AiUsage> parseLine(String line) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = Json.parse(line);
            if (!"model.completed".equals(node.get("type").toStringValue())) {
                return Optional.empty();
            }
            JsonNode usage = readUsage(node);
            if (usage.isMissingValue()) {
                return Optional.empty();
            }
            int inputTokens = usage.get("input").toIntValue(-1);
            int outputTokens = usage.get("output").toIntValue(-1);
            if (inputTokens <= 0 && outputTokens <= 0) {
                return Optional.empty();
            }
            int cacheRead = usage.get("cacheRead").toIntValue(0);
            long startTime = parseInstantToMillis(node.get("ts").toStringValue());
            JsonNode modelNode = node.get("modelId");
            String model = modelNode.isMissingValue() ? "unknown" : modelNode.toStringValue();
            JsonNode providerNode = node.get("provider");
            return Optional.of(AiUsage.builder()
                    .provider(PROVIDER_OPENCLAW)
                    .model(model)
                    .requestId(node.get("sessionId").toStringValue())
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(usage.get("total").toIntValue(inputTokens + outputTokens))
                    .cacheTokens(cacheRead > 0 ? cacheRead : null)
                    .currency("USD")
                    .startTime(startTime > 0 ? startTime : null)
                    .finishReason("stop")
                    .build());
        } catch (Exception e) {
            log.debug("[openclaw] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
