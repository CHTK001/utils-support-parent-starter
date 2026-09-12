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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Roo Code (VS Code extension) usage parser.
 *
 * <p>Roo Code (rooveterinaryinc.roo-cline) is a Cline-derived VS Code
 * family extension. Task folders live under
 * {@code <ide>/User/globalStorage/rooveterinaryinc.roo-cline/tasks/<uuid>/
 * ui_messages.json} (JSON array, rewritten in place each turn). Usage
 * records are the {@code say == "api_req_started"} / {@code api_req_deleted}
 * entries whose {@code text} field holds a JSON-stringified payload with
 * {@code tokensIn / tokensOut / cacheReads / cacheWrites}.</p>
 *
 * <p>Unlike Kilo Code, the payload carries no model name (only
 * {@code apiProtocol}). The real model is read from the sibling
 * {@code api_conversation_history.json}: the <i>last</i> occurrence of
 * {@code <model>…</model>} inside an {@code <environment_details>} block,
 * since Roo can switch models mid-task. When absent, the model falls back
 * to {@code protocol:<apiProtocol>} and finally {@code "unknown"}.</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("roocode")
public class RooCodeUsageParser extends BaseUsageParser {

    private static final String PROVIDER_ROOCODE = "roocode";

    private static final List<Path> IDE_ROOTS = KiloCodeUsageParser.ideRoots();

    private static final String TASKS_SUBPATH =
            "User/globalStorage/rooveterinaryinc.roo-cline/tasks";

    /** 从 history 尾部窗口提取最近一次 model 的匹配模式。 */
    private static final Pattern MODEL_TAG =
            Pattern.compile("<model>\\s*([^<>\\s][^<>]*?)\\s*</model>");

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "roocode"}
     */
    @Override
    public String name() {
        return PROVIDER_ROOCODE;
    }

    /**
     * 流式解析全部 IDE 安装下的 Roo Code 任务用量记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> taskFiles = listTaskFiles();
        if (taskFiles.isEmpty()) {
            log.debug("[roocode] no ui_messages.json found under {} IDE roots", IDE_ROOTS.size());
            return Flux.empty();
        }
        log.info("[roocode] streaming from {} task files", taskFiles.size());
        return Flux.fromIterable(taskFiles)
                .flatMap(RooCodeUsageParser::streamTaskFile, 2)
                .onErrorResume(e -> {
                    log.debug("[roocode] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 枚举全部存在 ui_messages.json 的任务目录。
     *
     * @return 任务 ui_messages.json 文件列表
     */
    private List<Path> listTaskFiles() {
        List<Path> files = new ArrayList<>();
        for (Path ideRoot : IDE_ROOTS) {
            Path tasksDir = ideRoot.resolve(TASKS_SUBPATH);
            if (!Files.isDirectory(tasksDir)) {
                continue;
            }
            try (var taskDirs = Files.list(tasksDir)) {
                taskDirs.filter(Files::isDirectory)
                        .map(d -> d.resolve("ui_messages.json"))
                        .filter(Files::isRegularFile)
                        .forEach(files::add);
            } catch (IOException e) {
                log.debug("[roocode] list failed {}: {}", tasksDir, e.getMessage());
            }
        }
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * 流式解析单个 ui_messages.json。
     *
     * @param file ui_messages.json 路径
     * @return 用量记录流
     */
    private static Flux<AiUsage> streamTaskFile(Path file) {
        String taskUuid = file.getParent() != null
                ? file.getParent().getFileName().toString() : "";
        return Flux.defer(() -> {
            try {
                JsonNode root = Json.parse(Files.readString(file));
                if (!root.isArray()) {
                    return Flux.<AiUsage>empty();
                }
                Path taskDir = file.getParent();
                String taskModel = readTaskModel(taskDir);
                List<AiUsage> records = new ArrayList<>();
                for (int i = 0; i < root.size(); i++) {
                    JsonNode msg = root.get(i);
                    String say = msg.get("say").toStringValue();
                    if (!"api_req_started".equals(say) && !"api_req_deleted".equals(say)) {
                        continue;
                    }
                    AiUsage usage = parsePayload(msg, taskUuid, taskModel);
                    if (usage != null) {
                        records.add(usage);
                    }
                }
                return Flux.fromIterable(records);
            } catch (Exception e) {
                log.debug("[roocode] read failed {}: {}", file, e.getMessage());
                return Flux.<AiUsage>empty();
            }
        });
    }

    /**
     * 从任务目录的 api_conversation_history.json 提取最近一次 model。
     *
     * @param taskDir 任务目录
     * @return model 名；无则 null
     */
    private static String readTaskModel(Path taskDir) {
        if (taskDir == null) {
            return null;
        }
        Path history = taskDir.resolve("api_conversation_history.json");
        if (!Files.isRegularFile(history)) {
            return null;
        }
        try {
            String raw;
            try (var reader = Files.newBufferedReader(history)) {
                raw = reader.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            // 限制尾部 1MB 避免超大 history 文件阻塞；取最后一次出现的 model
            String window = raw.length() > 1_048_576
                    ? raw.substring(raw.length() - 1_048_576) : raw;
            int blockStart = window.indexOf("<environment_details>");
            if (blockStart >= 0) {
                window = window.substring(blockStart);
            }
            Matcher matcher = MODEL_TAG.matcher(window);
            String last = null;
            while (matcher.find()) {
                last = matcher.group(1).trim();
            }
            return (last != null && !last.isEmpty()) ? last : null;
        } catch (Exception e) {
            log.debug("[roocode] history read failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析单条 api_req 消息。
     *
     * @param msg UI 消息节点
     * @param taskUuid 任务 ID
     * @param taskModel 任务级 model（可 null）
     * @return 用量记录；零用量或无 payload 时返回 null
     */
    private static AiUsage parsePayload(JsonNode msg, String taskUuid, String taskModel) {
        String text = msg.get("text").toStringValue();
        long ts = msg.get("ts").toLongValue(0L);
        if (text.isBlank() || !text.startsWith("{")) {
            return null;
        }
        JsonNode payload;
        try {
            payload = Json.parse(text);
        } catch (Exception e) {
            return null;
        }
        int tokensIn = payload.get("tokensIn").toIntValue(0);
        int tokensOut = payload.get("tokensOut").toIntValue(0);
        int cacheReads = payload.get("cacheReads").toIntValue(0);
        int cacheWrites = payload.get("cacheWrites").toIntValue(0);
        if (tokensIn == 0 && tokensOut == 0 && cacheReads == 0 && cacheWrites == 0) {
            return null;
        }
        String apiProtocol = payload.get("apiProtocol").toStringValue();
        String model;
        if (taskModel != null && !taskModel.isBlank()) {
            model = taskModel;
        } else if (!apiProtocol.isBlank()) {
            String slug = apiProtocol.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "");
            model = slug.isEmpty() ? "unknown" : "protocol:" + slug;
        } else {
            model = "unknown";
        }
        double cost = payload.get("cost").toDoubleValue(0.0);

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_ROOCODE)
                .model(model)
                .requestId(taskUuid + ":" + ts)
                .inputTokens(tokensIn)
                .outputTokens(tokensOut)
                .totalTokens(tokensIn + tokensOut + cacheReads + cacheWrites)
                .cacheTokens(cacheReads > 0 ? cacheReads : (cacheWrites > 0 ? cacheWrites : null))
                .currency("USD")
                .estimated(false)
                .startTime(ts > 0 ? ts : null);
        if (cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }
        return builder.build();
    }
}
