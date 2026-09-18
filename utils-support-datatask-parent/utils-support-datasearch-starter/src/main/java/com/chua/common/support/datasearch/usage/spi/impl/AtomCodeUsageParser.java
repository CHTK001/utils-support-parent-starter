package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
* Atom编码 usage parser - 解析 令牌 usage 从 本地 会话 turn transcripts.
*
* <p>Data source is {@code ~/.atomcode/sessions/<session-dir>/<session-id>.jsonl}.
* Each 线 records one turn 的 a 会话; every turn carries a top-级别
* {@code usage} block with real per-turn token counts:</p>
*
* <pre>{@code
* {
*   "v": 1,
*   "ts": 1787964681603,
*   "iso": "2026-08-29T00:51:21.603+00:00",
*   "session_id": "a5947423-8deb-412c-9246-4683628d72c7",
*   "turn_id": 1,
*   "undone": false,
*   "user": "...",
*   "assistant": "...",
*   "reasoning": "...",
*   "tools": [...],
*   "usage": { "prompt": 46135, "completion": 9507, "cached": 45824 }
* }
* }</pre>
*
* <p>AtomCode 的轮次行不含 {@code model}/{@code provider}/{@code costUsd}
* 字段。实际服务模型从同级的 {@code <session-id>.meta}
* 文件解析（{@code turn_stats[].model_usage[]} 以 {@code turn_id} 为键，取 token
* 占比最大的条目的 {@code model_id}）；若 meta 文件缺失
* 或该轮次未记录其中，则回退到 {@code ~/.atomcode/config.toml} 声明的
* {@code default_model}（再通过其 {@code [models."..."]} 段映射为真实模型名）。
* {@code prompt} 计数已包含命中缓存的输入，因此 {@code inputTokens}
* 存放非缓存部分（{@code prompt - cached}），缓存量单独通过
* {@code cacheTokens} 上报，避免重复计数。此处只提取 token 计数，因此不做费用估算。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("atomcode")
public class AtomCodeUsageParser extends BaseUsageParser {

    private static final Logger log = LoggerFactory.getLogger(AtomCodeUsageParser.class); // 日志

    /**
    * Atom编码 Home 目录，支持 ATOMCODE_Home 环境变量覆盖。
    * 默认为 ~/.atomcode
    */
    private static final Path ATOMCODE_HOME;

    /** 会话 transcripts 根: $ATOMCODE_Home/会话 */
    private static final Path SESSIONS_DIR;

    private static final String PROVIDER_ATOMCODE = "atomcode"; // 提供者atomcode

    /** config.toml 的 default_model 声明行 */
    private static final Pattern DEFAULT_MODEL_PATTERN =
            Pattern.compile("^\\s*default_model\\s*=\\s*\"([^\"]+)\"");

    /** config.toml 的 [models."xxx"] 小节头（键名可带引号） */
    private static final Pattern MODELS_SECTION_PATTERN =
            Pattern.compile("^\\s*\\[models\\.\"?([^\"\\]]+)\"?\\]\\s*$");

    /** config.toml 小节内的 model = "xxx" 声明行 */
    private static final Pattern SECTION_MODEL_PATTERN =
            Pattern.compile("^\\s*model\\s*=\\s*\"([^\"]+)\"");

    /**
    * config.toml 的兜底模型名（已映射为真实 model 名）。
    * 空串表示解析过但无结果，避免重复读盘。
    */
    private static volatile String CONFIG_DEFAULT_MODEL;

    static {
        String envHome = System.getenv("ATOMCODE_HOME");
        if (envHome != null && !envHome.isBlank()) {
            ATOMCODE_HOME = Path.of(envHome);
        } else {
            ATOMCODE_HOME = Path.of(System.getProperty("user.home"), ".atomcode");
        }
        SESSIONS_DIR = ATOMCODE_HOME.resolve("sessions");
    }

    @Override
    public String name() {
        return "atomcode";
    }

    /**
    * 流式解析全部 会话 转录：逐文件、逐行惰性拉取，内存占用与单条记录相关而与总量无关。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            log.debug("[atomcode] sessions directory not found: {}", SESSIONS_DIR);
            return Flux.empty();
        }
        try {
            List<Path> files;
            try (var stream = Files.walk(SESSIONS_DIR)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .toList();
            }
            log.debug("[atomcode] found {} JSONL files in {}", files.size(), SESSIONS_DIR);
            return Flux.fromIterable(files)
                    .subscribeOn(Schedulers.boundedElastic())
                    .concatMap(this::streamJsonlFile);
        } catch (IOException e) {
            return Flux.error(new IllegalStateException("walk failed", e));
        }
    }

    /**
    * 单个 JSONL 文件的行流（惰性 + 背压）。
    * 模型名取自同目录同名 {@code .meta} 的 turn_stats.model_usage，
    * meta 缺失或该 turn 无记录时回退 config.toml 的 default_model。
    * @param file 文件
    * @return 流jsonl文件的结果
    */
    private Flux<AiUsage> streamJsonlFile(Path file) {
        Map<Integer, String> turnModels = loadTurnModels(metaFileOf(file));
        String fallbackModel = configDefaultModel();
        return streamLines(file)
                .filter(line -> !line.isBlank())
                .map(line -> parseLineSafe(line, turnModels, fallbackModel))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    /**
    * 由 JSONL 文件路径推导同目录同名 .meta 文件路径。
    * @param file 会话 JSONL 文件
    * @return 对应的 .meta 文件
    */
    private Path metaFileOf(Path file) {
        String name = file.getFileName().toString();
        return file.resolveSibling(name.substring(0, name.length() - ".jsonl".length()) + ".meta");
    }

    /**
    * 读取会话 meta 文件，建立 turn_id 到模型名的映射。
    * 每个 turn 的 model_usage 可能含多个模型条目，取 token 总量最大者。
    * @param metaFile meta 文件
    * @return turn_id 到模型名的映射；文件缺失或解析失败时为空映射
    */
    private Map<Integer, String> loadTurnModels(Path metaFile) {
        if (!Files.isRegularFile(metaFile)) {
            return Map.of();
        }
        try {
            JsonNode turnStats = Json.parse(Files.readString(metaFile)).get("turn_stats");
            if (turnStats.isMissingValue() || !turnStats.isArray()) {
                return Map.of();
            }
            Map<Integer, String> models = new HashMap<>();
            int count = turnStats.size();
            for (int i = 0; i < count; i++) {
                JsonNode turn = turnStats.get(i);
                int turnId = turn.get("turn_id").toIntValue(-1);
                if (turnId < 0) {
                    continue;
                }
                String model = pickDominantModel(turn.get("model_usage"));
                if (model != null) {
                    models.put(turnId, model);
                }
            }
            return models;
        } catch (Exception e) {
            log.debug("[atomcode] meta parse failed {}: {}", metaFile, e.getMessage());
            return Map.of();
        }
    }

    /**
    * 从一个 turn 的 model_usage 数组中选出 token 占比最大的模型。
    * @param modelUsage model_usage 数组节点
    * @return 模型名（model_id 优先，provider_id 兜底）；无有效条目时返回 null
    */
    private String pickDominantModel(JsonNode modelUsage) {
        if (modelUsage.isMissingValue() || !modelUsage.isArray()) {
            return null;
        }
        String best = null;
        long bestTokens = -1;
        int count = modelUsage.size();
        for (int i = 0; i < count; i++) {
            JsonNode entry = modelUsage.get(i);
            JsonNode tokens = entry.get("tokens");
            long total = tokens.get("input").toLongValue(0L)
                    + tokens.get("output").toLongValue(0L)
                    + tokens.get("cached_input").toLongValue(0L);
            if (total <= bestTokens) {
                continue;
            }
            String modelId = entry.get("model_id").toStringValue("");
            String candidate = !modelId.isBlank()
                    ? modelId
                    : entry.get("provider_id").toStringValue("");
            if (candidate.isBlank()) {
                continue;
            }
            bestTokens = total;
            best = candidate;
        }
        return best;
    }

    /**
    * config.toml 的 default_model 兜底值（懒加载，结果缓存）。
    * @return 真实模型名；无法解析时返回 null
    */
    private String configDefaultModel() {
        String cached = CONFIG_DEFAULT_MODEL;
        if (cached == null) {
            synchronized (AtomCodeUsageParser.class) {
                if (CONFIG_DEFAULT_MODEL == null) {
                    CONFIG_DEFAULT_MODEL = resolveConfigDefaultModel();
                }
                cached = CONFIG_DEFAULT_MODEL;
            }
        }
        return cached.isBlank() ? null : cached;
    }

    /**
    * 解析 config.toml：先取 default_model 声明，再映射到
    * 对应 [models."xxx"] 小节内的真实 model 名。
    * @return 真实模型名；声明缺失或映射不到时返回声明原值，读盘失败返回空串
    */
    private String resolveConfigDefaultModel() {
        Path config = ATOMCODE_HOME.resolve("config.toml");
        if (!Files.isRegularFile(config)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(config);
            String declared = null;
            for (String line : lines) {
                Matcher matcher = DEFAULT_MODEL_PATTERN.matcher(line);
                if (matcher.find()) {
                    declared = matcher.group(1);
                    break;
                }
            }
            if (declared == null) {
                return "";
            }
            boolean inSection = false;
            for (String line : lines) {
                Matcher section = MODELS_SECTION_PATTERN.matcher(line);
                if (section.find()) {
                    inSection = declared.equals(section.group(1));
                    continue;
                }
                if (inSection) {
                    Matcher model = SECTION_MODEL_PATTERN.matcher(line);
                    if (model.find()) {
                        return model.group(1);
                    }
                }
            }
            return declared;
        } catch (Exception e) {
            log.debug("[atomcode] config.toml parse failed: {}", e.getMessage());
            return "";
        }
    }

    /**
    * 安全解析单行，失败返回 空。
    * @param line 线
    * @param turnModels turn_id 到模型名的映射
    * @param fallbackModel 兜底模型名
    * @return 解析线safe的结果
    */
    private Optional<AiUsage> parseLineSafe(String line, Map<Integer, String> turnModels, String fallbackModel) {
        try {
            return parseNode(Json.parse(line), turnModels, fallbackModel);
        } catch (Exception e) {
            log.debug("[atomcode] line parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
    * 将一条转录行转换为 AIusage 记录。
    *
    * <p>仅接受带顶层 {@code usage} 且含有效 token 数的 turn 记录。
    * 模型名按 turn_id 查 meta 映射，查不到用 config 兜底值。</p>
    * @param node 节点
    * @param turnModels turn_id 到模型名的映射
    * @param fallbackModel 兜底模型名
    * @return 解析节点的结果
    */
    private Optional<AiUsage> parseNode(JsonNode node, Map<Integer, String> turnModels, String fallbackModel) {
        JsonNode usage = node.get("usage");
        if (usage.isMissingValue()) {
            return Optional.empty();
        }
        int inputTokens = usage.get("prompt").toIntValue(-1);
        int outputTokens = usage.get("completion").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return Optional.empty();
        }
        int cached = Math.max(usage.get("cached").toIntValue(0), 0);
        long startTime = node.get("ts").toLongValue(0L);
        String sessionId = node.get("session_id").toStringValue("unknown");
        int turnId = node.get("turn_id").toIntValue(-1);

        // prompt 口径含缓存输入；非缓存输入 = 全量输入 - 缓存，缓存单列防双计。
        int nonCachedInput = Math.max(0, inputTokens - cached);
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_ATOMCODE)
                .model(turnModels.getOrDefault(turnId, fallbackModel))
                .requestId(sessionId + "-" + turnId)
                .startTime(startTime > 0 ? startTime : null);
        if (nonCachedInput > 0) {
            builder.inputTokens(nonCachedInput);
        }
        if (outputTokens > 0) {
            builder.outputTokens(outputTokens);
        }
        builder.totalTokens(nonCachedInput + Math.max(outputTokens, 0));
        if (cached > 0) {
            builder.cacheTokens(cached);
        }
        return Optional.of(builder.build());
    }
}
