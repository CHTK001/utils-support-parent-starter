package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
* VS Code 系扩展用量解析器共享基类。
*
* <p>Kilo Code（kilocode.kilo-code）、Roo Code（rooveterinaryinc.roo-cline）等
* Cline 派生扩展将任务持久化到 VS Code globalStorage 目录
* {@code <IDE>/User/globalStorage/<extension-id>/tasks/<task-uuid>/ui_messages.json}。
* 每个任务文件的消息数组中，助手消息携带 {@code role:"assistant"} 与
* {@code usage:{inputTokens, outputTokens, cacheReadInputTokens,
* cacheWriteTokens, costUSD}} 真实用量（由上游 provider 报告）：</p>
*
* <pre>{@code
* {
*   "role": "assistant",
*   "provider": "anthropic",
*   "api": "anthropic",
*   "model": "claude-sonnet-4-5",
*   "timestamp": 1730000000000,
*   "requestId": "...",
*   "text": "...",
*   "usage": {
*     "inputTokens": 1024,
*     "outputTokens": 256,
*     "cacheReadInputTokens": 800,
*     "cacheWriteTokens": 0,
*     "costUSD": 0.0112
*   }
* }
* </pre>
*
* <p>不同 IDE 安装（Code / Cursor / CodeBuddy / ...）使用相同的 globalStorage
* 布局，因此子类仅需扩展 id 前缀列表 + 任务目录名。任务级时间戳缺失时，
* 以任务文件 mtime 兜底（仅用于聚合排序）。</p>
*
* @author CH
* @since 4.0.0.43
 */
public abstract class VscodeExtensionTaskUsageParser extends BaseUsageParser {

    private static final String CURRENCY_USD = "USD";

    /**
    * 扩展 id 前缀列表（匹配 globalStorage 下的扩展目录）。
    *
    * @return 扩展 id 前缀（如 {@code "kilocode"}、{@code "rooveterinaryinc"}）
     */
    protected abstract List<String> extensionIdPrefixes();

    /**
    * 任务目录名（globalStorage/&lt;ext-id&gt;/ 之下的目录）。
    *
    * @return 任务目录名（如 {@code "tasks"}）
     */
    protected abstract String taskDirName();

    /**
    * 默认响应式流式入口：扫描全部 IDE 安装，惰性解析任务文件。
    *
    * <p>子类无需再自行覆写转接 {@link #fromTaskFiles()}。</p>
    *
    * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        return fromTaskFiles();
    }

    /**
    * 扫描全部 IDE 安装并解析任务文件（子类可直接复用）。
    *
    * @return 用量记录流
     */
    protected Flux<AiUsage> fromTaskFiles() {
        List<Map<Path, String>> batches = collectTaskFiles();
        if (batches.isEmpty()) {
            log.debug("[{}] no {} task files found under any IDE globalStorage",
                    name(), taskDirName());
            return Flux.empty();
        }
        int total = batches.stream().mapToInt(Map::size).sum();
        log.info("[{}] scanning {} task files across {} IDE installs",
                name(), total, batches.size());
        return Flux.fromIterable(batches)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(batch -> Flux.fromIterable(parseTaskFiles(null, batch)), 4);
    }

    /**
     * 收集全部 IDE 安装下的任务文件（按 globalStorage 根分组）。
     *
     * <p>每个 globalStorage 根扫描扩展目录：目录名匹配 {@link #extensionIdPrefixes()}
     * 前缀之一即视为本扩展安装，取其下的 {@code <taskDirName>}/&lt;task-uuid&gt;/
     * 作为任务目录。各批次 value 为「任务文件 → 任务 id」映射。</p>
     *
     * @return 任务文件批次（批次内 key 为任务文件，value 为任务 id）
     */
    protected List<Map<Path, String>> collectTaskFiles() {
        List<String> prefixes = extensionIdPrefixes();
        List<Map<Path, String>> batches = new ArrayList<>();
        for (Path globalStorage : enumerateGlobalStorageRoots()) {
            Path extensionDir = resolveExtensionDir(globalStorage, prefixes);
            if (extensionDir == null) {
                continue;
            }
            Path tasksDir = extensionDir.resolve(taskDirName());
            if (!Files.isDirectory(tasksDir)) {
                continue;
            }
            Map<Path, String> files = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(tasksDir)) {
                stream.filter(Files::isDirectory)
                        .forEach(taskDir -> {
                            Path taskFile = taskDir.resolve("ui_messages.json");
                            if (Files.isRegularFile(taskFile)) {
                                files.put(taskFile, taskDir.getFileName().toString());
                            }
                        });
            } catch (IOException e) {
                log.debug("[{}] list {} failed: {}", name(), tasksDir, e.getMessage());
            }
            if (!files.isEmpty()) {
                batches.add(files);
            }
        }
        return batches;
    }

    /**
     * 在 globalStorage 根下查找匹配任一扩展 id 前缀的扩展目录。
     *
     * @param globalStorage globalStorage 根
     * @param prefixes 扩展 id 前缀列表
     * @return 匹配到的扩展目录；无匹配或读取失败时返回 null
     */
    private static Path resolveExtensionDir(Path globalStorage, List<String> prefixes) {
        try (Stream<Path> stream = Files.list(globalStorage)) {
            List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String dirName = p.getFileName().toString().toLowerCase();
                        return prefixes.stream().anyMatch(prefix ->
                                dirName.startsWith(prefix.toLowerCase()));
                    })
                    .toList();
            return candidates.isEmpty() ? null : candidates.getFirst();
        } catch (IOException e) {
            return null;
        }
    }

    /**
    * 枚举本机各 IDE 安装的 globalStorage 根目录。
    *
    * <p>支持 Code / Cursor / CodeBuddy / Windsurf / VS Code Insiders / Trae
    * 等常见 VS Code 系 IDE 的 AppData 安装目录；{@code VSCODE_APPDATA_CANDIDATES}
    * 覆盖主流产品名。找不到任何安装时返回空列表（解析器安静跳过）。</p>
    *
    * @return 存在的 globalStorage 根目录列表
     */
    protected List<Path> enumerateGlobalStorageRoots() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        for (String ide : VSCODE_APPDATA_CANDIDATES) {
            Path globalStorage = Path.of(appData, ide, "User", "globalStorage");
            if (Files.isDirectory(globalStorage)) {
                roots.add(globalStorage);
            }
        }
        return roots;
    }

    /**
    * VS Code 系 IDE 的 AppData 产品目录候选列表。
     */
    protected static final String[] VSCODE_APPDATA_CANDIDATES = {
            "Code", "Cursor", "VSCodium", "CodeBuddy", "CodeBuddyCN", "Windsurf",
            "Trae", "TraeCN", "Insiders"
    };

    /**
    * 解析 {@code ui_messages.json} 中的任务文件，提取助手消息用量。
    *
    * @param root globalStorage 根（{@code <IDE>/User/globalStorage}）
    * @param taskFiles 任务文件及其所属任务 id
    * @return 解析出的用量记录
     */
    protected List<AiUsage> parseTaskFiles(Path root, Map<Path, String> taskFiles) {
        List<AiUsage> result = new ArrayList<>();
        for (Map.Entry<Path, String> entry : taskFiles.entrySet()) {
            Path file = entry.getKey();
            String taskId = entry.getValue();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String raw = readAll(reader);
                List<Map<String, Object>> messages = parseMessagesArray(raw);
                long fallbackTime = FileTimeResolver.millisOf(file);
                for (Map<String, Object> msg : messages) {
                    AiUsage usage = toAiUsage(msg, taskId, fallbackTime);
                    if (usage != null) {
                        result.add(usage);
                    }
                }
            } catch (IOException e) {
                log.debug("[{}] read failed {}: {}", name(), file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    /**
    * 将一条助手消息转换为 AiUsage 记录；非助手或无用量时返回 null。
    *
    * @param msg 解析后的消息对象
    * @param taskId 任务 id（作为 requestId）
    * @param fallbackTime 任务文件 mtime（毫秒，时间戳缺失时兜底）
    * @return 用量记录或 null
     */
    protected AiUsage toAiUsage(Map<String, Object> msg, String taskId, long fallbackTime) {
        if (!"assistant".equals(asStr(msg.get("role")))) {
            return null;
        }
        Object usageObj = msg.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) {
            return null;
        }
        int inputTokens = asInt(usage.get("inputTokens"));
        int outputTokens = asInt(usage.get("outputTokens"));
        int cacheRead = asInt(usage.get("cacheReadInputTokens"));
        int cacheWrite = asInt(usage.get("cacheWriteTokens"));
        if (inputTokens <= 0 && outputTokens <= 0) {
            return null;
        }
        double costUsd = asDouble(usage.get("costUSD"));
        long startTime = asLong(msg.get("timestamp"));
        if (startTime <= 0) {
            startTime = fallbackTime;
        }

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(firstNonBlank(asStr(msg.get("provider")), name()))
                .model(extractModelName(msg))
                .requestId(firstNonBlank(asStr(msg.get("requestId")), taskId))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .startTime(startTime > 0 ? startTime : null);
        if (costUsd > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(costUsd)).currency(CURRENCY_USD);
        }
        return builder.build();
    }

    /**
    * 从消息中解析模型名；缺失时返回兜底值（provider 前缀形式）。
    *
    * @param msg 消息对象
    * @return 模型名
     */
    protected String extractModelName(Map<String, Object> msg) {
        String model = asStr(msg.get("model"));
        if (!model.isBlank()) {
            return model;
        }
        String provider = asStr(msg.get("api"));
        return provider.isBlank() ? "unknown" : "protocol:" + provider.toLowerCase();
    }

    private static String readAll(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /**
    * 解析任务 JSON 中的消息数组（兼容顶层数组或 {@code messages} 字段）。
    *
    * @param raw JSON 文本
    * @return 消息列表（不可解析时为空）
     */
    private static List<Map<String, Object>> parseMessagesArray(String raw) {
        com.chua.common.support.lang.json.JsonNode node =
                com.chua.common.support.lang.json.Json.parse(raw);
        com.chua.common.support.lang.json.JsonNode arr = node;
        if (arr.isMissingValue() && !node.isMissingValue() && !node.isArray()) {
            // 兼容 { "messages": [...] } 包装
            arr = node.get("messages");
        }
        if (arr.isMissingValue() || !arr.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : arr.toJsonArray()) {
            if (element instanceof com.chua.common.support.lang.json.JsonObject obj) {
                result.add(new LinkedHashMap<>(obj.toMap()));
            }
        }
        return result;
    }

    /**
    * 文件毫秒时间解析器（mtime 兜底）。
     */
    static final class FileTimeResolver {

        private FileTimeResolver() {
        }

        /**
        * 文件 mtime 毫秒；不可用时返回 0。
        *
        * @param file 文件
        * @return mtime 毫秒或 0
         */
        static long millisOf(Path file) {
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }
    }
}
