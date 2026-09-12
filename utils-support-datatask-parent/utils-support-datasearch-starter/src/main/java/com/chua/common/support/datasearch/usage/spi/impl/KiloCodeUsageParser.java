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
import java.util.Map;

/**
 * Kilo Code (VS Code extension) usage parser.
 *
 * <p>Kilo Code (kilo.ai) is a VS Code / Cursor / Windsurf family extension.
 * Its task folders live under the IDE's global storage:
 * {@code <ide>/User/globalStorage/kilocode.kilo-code/tasks/<uuid>/
 * ui_messages.json}. The file is a JSON array of UI messages; LLM usage
 * records are the entries with {@code say == "api_req_started"} whose
 * {@code text} field is a JSON-stringified payload:</p>
 *
 * <pre>{@code
 * [
 *   {
 *     "say": "api_req_started",
 *     "text": "{ \"apiProtocol\":\"anthropic\", \"tokensIn\":28673, "
 *              + "\"tokensOut\":31, \"cacheWrites\":0, \"cacheReads\":5120, "
 *              + "\"inferenceProvider\":\"Moonshot AI\" }",
 *     "ts": 1783322059000
 *   }
 * ]
 * }</pre>
 *
 * <p>{@code tokensIn} already excludes cache; {@code cacheReads} /
 * {@code cacheWrites} are tracked separately. The {@code ts} of the
 * placeholder (zero-token) write at request start equals that of the
 * in-place back-filled completion, so both collapse to one record per
 * (task, ts).</p>
 *
 * <p>Multi-IDE roots are scanned on the current platform
 * (Windows: {@code %APPDATA%\<ide>}, macOS: {@code ~/Library/Application
 * Support/<ide>}, Linux: {@code ~/.config/<ide>}).</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("kilocode")
public class KiloCodeUsageParser extends BaseUsageParser {

    private static final String PROVIDER_KILOCODE = "kilo-code";

    private static final List<Path> IDE_ROOTS = resolveIdeRoots();

    private static final String TASKS_SUBPATH = "User/globalStorage/kilocode.kilo-code/tasks";

    /**
     * 供同包内的 Roo Code 解析器复用：各 IDE 安装的全局存储根目录。
     *
     * @return IDE 根目录列表
     */
    static List<Path> ideRoots() {
        return IDE_ROOTS;
    }

    /**
     * 解析各 IDE 安装的全局存储根目录。
     *
     * @return IDE 根目录列表
     */
    private static List<Path> resolveIdeRoots() {
        String appData = System.getenv("APPDATA");
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        String home = System.getProperty("user.home");
        String[] ides = {"Code", "Code - Insiders", "Cursor", "CodeBuddy",
                "Windsurf", "VSCodium", "Trae", "Trae CN"};
        List<Path> roots = new ArrayList<>();
        if (appData != null && !appData.isBlank()) {
            for (String ide : ides) {
                roots.add(Path.of(appData, ide));
            }
            return roots;
        }
        if (xdgConfig != null && !xdgConfig.isBlank()) {
            for (String ide : ides) {
                roots.add(Path.of(xdgConfig, ide));
            }
            return roots;
        }
        for (String ide : ides) {
            roots.add(Path.of(home, "Library", "Application Support", ide));
        }
        return roots;
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "kilocode"}
     */
    @Override
    public String name() {
        return PROVIDER_KILOCODE;
    }

    /**
     * 流式解析全部 IDE 安装下的 Kilo Code 任务用量记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> taskFiles = listTaskFiles();
        if (taskFiles.isEmpty()) {
            log.debug("[kilocode] no ui_messages.json found under {} IDE roots", IDE_ROOTS.size());
            return Flux.empty();
        }
        log.info("[kilocode] streaming from {} task files", taskFiles.size());
        return Flux.fromIterable(taskFiles)
                .flatMap(KiloCodeUsageParser::streamTaskFile, 2)
                .onErrorResume(e -> {
                    log.debug("[kilocode] read failed: {}", e.getMessage());
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
                log.debug("[kilocode] list failed {}: {}", tasksDir, e.getMessage());
            }
        }
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * 流式解析单个 ui_messages.json（整体重写文件，无字节尾部追加，需全量读取）。
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
                List<AiUsage> records = new ArrayList<>();
                for (int i = 0; i < root.size(); i++) {
                    JsonNode msg = root.get(i);
                    String say = msg.get("say").toStringValue();
                    if (!"api_req_started".equals(say) && !"api_req_deleted".equals(say)) {
                        continue;
                    }
                    records.add(parsePayload(msg, taskUuid));
                }
                return Flux.fromIterable(records);
            } catch (Exception e) {
                log.debug("[kilocode] read failed {}: {}", file, e.getMessage());
                return Flux.<AiUsage>empty();
            }
        });
    }

    /**
     * 解析单条 api_req 消息的 text payload。
     *
     * @param msg UI 消息节点
     * @param taskUuid 任务 ID
     * @return 用量记录（零用量或无 payload 时返回 null 记录由调用方过滤）
     */
    private static AiUsage parsePayload(JsonNode msg, String taskUuid) {
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
        // 请求开始时的占位记录（全零）会在完成后原地回填相同 ts，
        // 零用量行返回 null 让下游跳过，避免把占位行计入。
        if (tokensIn == 0 && tokensOut == 0 && cacheReads == 0 && cacheWrites == 0) {
            return null;
        }
        double cost = payload.get("cost").toDoubleValue(0.0);
        String provider = payload.get("inferenceProvider").toStringValue();

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_KILOCODE)
                .model(normalizeProviderToModel(provider))
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

    /**
     * 将推理服务商名称归一为 model 列：
     * {@code "Moonshot AI"} → {@code "provider:moonshot-ai"}，空值 →
     * {@code "provider:unknown"}。
     *
     * @param providerName 服务商名
     * @return 归一后的 model 值
     */
    private static String normalizeProviderToModel(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return "provider:unknown";
        }
        String slug = providerName.trim().toLowerCase()
                .replace(" ", "-")
                .replaceAll("[^a-z0-9._-]", "");
        if (slug.isEmpty() || !slug.matches(".*[a-z0-9].*")) {
            return "provider:unknown";
        }
        return "provider:" + slug;
    }
}
