package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Antigravity (Google, agentic IDE/CLI) usage parser.
 *
 * <p>Antigravity persists session transcripts under
 * {@code <geminiHome>/antigravity/brain/<uuid>/.system_generated/logs/
 * transcript.jsonl} ({@code geminiHome} = {@code GEMINI_HOME} /
 * {@code ~/.gemini}). Transcript lines carry model-selection changes and
 * planner/thinking events but <b>no token counters</b>; the parser derives
 * an estimated per-turn usage using the same heuristic TokenTracker uses:</p>
 *
 * <pre>
 *   input  = max(0, contextTokensDelta)      // CJK 1 token/char, other 1/4 chars
 *   output = content + tool_calls tokens
 *   reasoning = thinking tokens
 * </pre>
 *
 * <p>Context tokens are accumulated across the transcript; on each
 * {@code PLANNER_RESPONSE} the delta since the previous billed planner
 * (or since context reset on model switch) is emitted as the input.
 * Model is recovered from {@code USER_INPUT} "Model Selection" lines
 * and from the variant-root {@code settings.json}; unknown →
 * {@code "antigravity-unknown"}.</p>
 *
 * <p>All records are flagged {@code estimated = true} because Antigravity
 * does not persist a token counter locally.</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Spi("antigravity")
public class AntigravityUsageParser extends BaseUsageParser {

    private static final Path GEMINI_HOME = resolveGeminiHome();

    private static final String PROVIDER_ANTIGRAVITY = "antigravity";

    private static final String VARIANT_SUBDIRS[] =
            {"antigravity", "antigravity-ide", "antigravity-cli"};

    private static final Pattern MODEL_SELECTION = Pattern.compile(
            "changed setting `Model Selection` from .*? to ([^`\\n]+?)(?:\\s*\\([^)]*\\))?\\.(?:\\s+|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SETTINGS_MODEL = Pattern.compile(
            "\"model\"\\s*:\\s*\"([^\"]+)\"");

    private static Path resolveGeminiHome() {
        String geminiHome = System.getenv("GEMINI_HOME");
        if (geminiHome != null && !geminiHome.isBlank()) {
            return Path.of(geminiHome);
        }
        return Path.of(System.getProperty("user.home"), ".gemini");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "antigravity"}
     */
    @Override
    public String name() {
        return PROVIDER_ANTIGRAVITY;
    }

    /**
     * 流式解析全部 Antigravity 转录文件。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> transcripts = listTranscripts();
        if (transcripts.isEmpty()) {
            log.debug("[antigravity] no transcripts under {} (not installed)", GEMINI_HOME);
            return Flux.empty();
        }
        log.info("[antigravity] streaming from {} transcripts", transcripts.size());
        return Flux.fromIterable(transcripts)
                .flatMap(this::streamTranscript, 2)
                .onErrorResume(e -> {
                    log.debug("[antigravity] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 枚举三个变体目录下的全部 transcript.jsonl。
     *
     * @return 转录文件列表
     */
    private List<Path> listTranscripts() {
        List<Path> files = new ArrayList<>();
        for (String variant : VARIANT_SUBDIRS) {
            Path brainDir = GEMINI_HOME.resolve(variant).resolve("brain");
            if (!Files.isDirectory(brainDir)) {
                continue;
            }
            try (var sessionDirs = Files.list(brainDir)) {
                sessionDirs.filter(Files::isDirectory).forEach(sessionDir -> {
                    Path transcript = sessionDir
                            .resolve(".system_generated").resolve("logs")
                            .resolve("transcript.jsonl");
                    if (Files.isRegularFile(transcript)) {
                        files.add(transcript);
                    }
                });
            } catch (IOException e) {
                log.debug("[antigravity] list failed {}: {}", brainDir, e.getMessage());
            }
        }
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * 解析单个转录文件：逐行累积 context tokens，在每个 PLANNER_RESPONSE
     * 处发出一条（估算）用量记录。
     *
     * @param file 转录文件
     * @return 估算用量记录流
     */
    private Flux<AiUsage> streamTranscript(Path file) {
        return Flux.defer(() -> {
            List<AiUsage> records = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                long contextTokens = 0L;
                long previousContextTokens = 0L;
                String currentModel = readDefaultModel(file);
                String lastPlannerModel = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event;
                    try {
                        event = Json.parse(line);
                    } catch (Exception e) {
                        continue;
                    }
                    String type = event.get("type").toStringValue();
                    if ("USER_INPUT".equals(type) || "USER_SETTINGS_CHANGE".equals(type)) {
                        String model = extractModelSelection(event.get("content").toStringValue());
                        if (model != null) {
                            currentModel = model;
                        }
                    }
                    long eventContextTokens = contextTokensOf(event);
                    long inputDelta = 0L;
                    long outputTokens = 0L;
                    long reasoningTokens = 0L;
                    boolean billedPlanner = false;
                    if ("PLANNER_RESPONSE".equals(type)) {
                        String modelNow = currentModel != null ? currentModel : "antigravity-unknown";
                        if (lastPlannerModel != null && !modelNow.equals(lastPlannerModel)) {
                            // 模型切换：上下文重置，之前的累计不再计给旧模型
                            previousContextTokens = 0L;
                        }
                        inputDelta = Math.max(0L, contextTokens - previousContextTokens);
                        outputTokens = estimateTokens(event.get("content").toStringValue())
                                + estimateTokens(serialize(event.get("tool_calls")));
                        reasoningTokens = estimateTokens(event.get("thinking").toStringValue());
                        billedPlanner = (inputDelta + outputTokens + reasoningTokens) > 0;
                    }
                    if (billedPlanner) {
                        long startTime = parseInstantToMillis(event.get("created_at").toStringValue());
                        records.add(AiUsage.builder()
                                .provider(PROVIDER_ANTIGRAVITY)
                                .model(currentModel != null ? currentModel : "antigravity-unknown")
                                .inputTokens((int) inputDelta)
                                .outputTokens((int) outputTokens)
                                .totalTokens((int) (inputDelta + outputTokens + reasoningTokens))
                                .reasoningTokens(reasoningTokens > 0 ? (int) reasoningTokens : null)
                                .currency("USD")
                                .estimated(true)
                                .startTime(startTime > 0 ? startTime : null)
                                .build());
                        previousContextTokens = contextTokens;
                        lastPlannerModel = currentModel;
                    }
                    contextTokens += eventContextTokens;
                }
            } catch (IOException e) {
                log.debug("[antigravity] read failed {}: {}", file, e.getMessage());
            }
            return Flux.fromIterable(records);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从 settings.json（变体根目录）读取默认模型。
     *
     * @param transcript 转录文件路径
     * @return 归一化模型名；无则 null
     */
    private String readDefaultModel(Path transcript) {
        try {
            Path dir = transcript;
            for (int i = 0; i < 5; i++) {
                dir = dir.getParent();
                if (dir == null) {
                    return null;
                }
            }
            if (dir == null) {
                return null;
            }
            Path settings = dir.resolve("settings.json");
            if (!Files.isRegularFile(settings)) {
                return null;
            }
            String raw = Files.readString(settings);
            Matcher matcher = SETTINGS_MODEL.matcher(raw);
            if (matcher.find()) {
                return normalizeAntigravityModel(matcher.group(1));
            }
        } catch (Exception ignored) {
            // settings 读取失败不影响主体解析
        }
        return null;
    }

    /**
     * 从 USER_INPUT 内容里提取 "Model Selection" 变更后的模型名。
     *
     * @param content 用户输入内容
     * @return 归一化模型名；无则 null
     */
    private String extractModelSelection(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = MODEL_SELECTION.matcher(content);
        if (matcher.find()) {
            return normalizeAntigravityModel(matcher.group(1));
        }
        return null;
    }

    /**
     * 归一化模型名（对齐 TokenTracker normalizeAntigravityTranscriptModel）。
     *
     * @param modelName 原始模型名
     * @return 归一化结果；空则 null
     */
    private static String normalizeAntigravityModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String slug = modelName.trim()
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\b(thinking|xhigh|high|medium|low|fast)\\b", " ",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-");
        if (slug.isEmpty()) {
            return null;
        }
        for (String marker : new String[]{"gemini", "claude", "gpt"}) {
            int idx = slug.indexOf(marker);
            if (idx >= 0) {
                slug = slug.substring(idx);
                break;
            }
        }
        if (slug.matches("(gemini|claude|gpt)-.*")) {
            return slug;
        }
        return "antigravity-" + slug;
    }

    /**
     * 事件上下文 token 量：内容 + （PLANNER_RESPONSE 时）tool_calls。
     *
     * @param event 事件节点
     * @return 估算 token 数
     */
    private long contextTokensOf(JsonNode event) {
        long tokens = estimateTokens(event.get("content").toStringValue());
        if ("PLANNER_RESPONSE".equals(event.get("type").toStringValue())) {
            tokens += estimateTokens(serialize(event.get("tool_calls")));
        }
        return tokens;
    }

    /**
     * 序列化 tool_calls 节点（缺失/null 时返回空串）。
     *
     * @param node 工具调用节点
     * @return 字符串形式
     */
    private String serialize(JsonNode node) {
        if (node == null || node.isMissingValue() || !node.isValueNode()) {
            return "";
        }
        try {
            return node.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * CJK 字符 1 token/字，其余 1/4 字符 1 token（向上取整）。
     *
     * @param text 文本
     * @return 估算 token 数
     */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjk = 0L;
        long other = 0L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /**
     * 判断字符是否 CJK。
     *
     * @param c 字符
     * @return 是否 CJK
     */
    private static boolean isCjk(char c) {
        int code = c;
        return (code >= 0x3400 && code <= 0x4DBF)
                || (code >= 0x4E00 && code <= 0x9FFF)
                || (code >= 0x3040 && code <= 0x30FF);
    }
}
