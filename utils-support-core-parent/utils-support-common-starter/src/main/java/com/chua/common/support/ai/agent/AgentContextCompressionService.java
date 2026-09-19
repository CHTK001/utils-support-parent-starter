package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryEntry;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文压缩服务——两阶段策略。
 *
 * <p>阶段一：当消息条数达到阈值时，先把完整上下文保存为基线快照，
 * 再执行常规压缩（只保留最后 N 条消息）。</p>
 * <p>阶段二：基线建立之后，每经过 N 轮：加载基线快照，
 * 用全新的 ChatClient 会话对其进行摘要，再结合当前上下文修正偏差。</p>
 *
 * <p>轻量复用入口见 {@link com.chua.common.support.ai.context.ContextCompressor}，
 * Agent 与普通 ChatClient 均可使用，不绑定工具/计划。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentContextCompressionService implements AgentContextCompressionConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentContextCompressionService.class);

    /** 基线快照类型标识 */
    private static final String BASELINE_TYPE = "context_baseline";

    /** 基线摘要类型标识 */
    private static final String BASELINE_SUMMARY_TYPE = "context_baseline_summary";

    /** 轮数计数器类型标识 */
    private static final String ROUNDS_COUNTER_TYPE = "context_rounds_counter";

    /** 压缩配置 */
    private final AgentCompressionConfig config;

    /** 压缩用聊天客户端 */
    private final ChatClient compressionChatClient;

    /** 备用聊天客户端 */
    private final ChatClient fallbackChatClient;

    /** 工作空间路径 */
    private final String workspace;

    /** 是否已保存基线 */
    private boolean baselineSaved = false;

    /** 基线之后经过的轮数 */
    private int roundsAfterBaseline = 0;

    /** 缓存的基线摘要 */
    private String cachedBaselineSummary = null;

    /**
     * 创建 AgentContextCompressionService 实例
     * @param config config
     * @param compressionChatClient compressionChatClient
     * @param fallbackChatClient fallbackChatClient
     * @param workspace workspace
     */
    public AgentContextCompressionService(AgentCompressionConfig config,
                                          ChatClient compressionChatClient,
                                          ChatClient fallbackChatClient,
                                          String workspace) {
        this.config = config != null ? config : AgentCompressionConfig.builder().build();
        this.compressionChatClient = compressionChatClient != null ? compressionChatClient : fallbackChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.workspace = workspace;
    }

    /**
     * 创建 AgentContextCompressionService 实例
     * @param config config
     * @param fallbackChatClient fallbackChatClient
     * @param workspace workspace
     */
    public AgentContextCompressionService(AgentCompressionConfig config,
                                          ChatClient fallbackChatClient,
                                          String workspace) {
        this(config,
                config != null ? config.getCompressionChatClient() : null,
                fallbackChatClient,
                workspace);
        this.roundsAfterBaseline = loadRoundsAfterBaseline();
    }

    @Override
    /** OnFirstCompression */
    public void onFirstCompression(List<ChatMessage> fullContext) {
        if (!config.isEnabled() || baselineSaved) {
            return;
        }
        log.info("[Compression] First compression trigger, saving baseline snapshot, messageCount={}", fullContext.size());
        saveBaseline(fullContext);
        ChatClient client = resolveCompressionClient();
        if (client != null) {
            String summary = summarizeBaseline(client, fullContext);
            saveBaselineSummary(summary);
            cachedBaselineSummary = summary;
        }
        baselineSaved = true;
        roundsAfterBaseline = 0;
    }

    @Override
    /**
    * OnDeviationCompression
    * @param baselineContext baselineContext
    * @param currentContext currentContext
    */
    public List<ChatMessage> onDeviationCompression(List<ChatMessage> baselineContext,
                                                     List<ChatMessage> currentContext) {
        if (!config.isEnabled()) {
            return currentContext;
        }
        ChatClient client = resolveCompressionClient();
        if (client == null) {
            log.warn("[Compression] No ChatClient available, skip deviation correction");
            return currentContext;
        }

        try {
            String baselineSummary = cachedBaselineSummary != null
                    ? cachedBaselineSummary
                    : loadBaselineSummary();
            if (baselineSummary == null || baselineSummary.isEmpty()) {
                baselineSummary = summarizeBaseline(client, baselineContext);
                saveBaselineSummary(baselineSummary);
            }
            cachedBaselineSummary = baselineSummary;

            List<ChatMessage> corrected = correctDeviation(client, baselineSummary, currentContext);
            log.info("[Compression] Deviation correction done, current={} -> corrected={}",
                    currentContext.size(), corrected.size());
            roundsAfterBaseline = 1;
            saveRoundsAfterBaseline();
            return corrected;
        } catch (Exception e) {
            log.error("[Compression] Deviation correction failed, fallback to current context", e);
            return currentContext;
        }
    }

    /**
     * 压缩Context
     * @param fullContext full上下文，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    public List<ChatMessage> compressContext(List<ChatMessage> fullContext) {
        if (fullContext == null || fullContext.isEmpty()) {
            return fullContext;
        }
        int threshold = config.getContextCompressionThreshold();
        if (fullContext.size() < threshold) {
            return fullContext;
        }

        int retain = Math.min(config.getRetainMessages(), fullContext.size() - 1);
        List<ChatMessage> retained = new ArrayList<>(fullContext.subList(fullContext.size() - retain, fullContext.size()));

        String summary = cachedBaselineSummary;
        if (summary == null || summary.isEmpty()) {
            summary = loadBaselineSummary();
        }
        if (summary != null && !summary.isEmpty()) {
            if (!baselineSaved) {
                baselineSaved = true;
                roundsAfterBaseline = 0;
            }
            ChatMessage compressedMsg = new ChatMessage("system", "[COMPRESSED_CONTEXT] " + summary);
            List<ChatMessage> result = new ArrayList<>(retained.size());
            result.add(compressedMsg);
            result.addAll(retained);
            return result;
        }

        List<ChatMessage> toCompress = new ArrayList<>(fullContext.subList(0, fullContext.size() - retain));
        String compressedText = buildCompressedText(toCompress);
        ChatMessage compressedMsg = new ChatMessage("system", "[COMPRESSED_CONTEXT] " + compressedText);

        List<ChatMessage> result = new ArrayList<>(retained.size());
        result.add(compressedMsg);
        result.addAll(retained);
        return result;
    }

    /**
     * 是否应该TriggerDeviationCorrection
     * @return 是否成功（true 表示成功）
     */
    public boolean shouldTriggerDeviationCorrection() {
        if (!config.isEnabled() || !baselineSaved) {
            return false;
        }
        roundsAfterBaseline++;
        saveRoundsAfterBaseline();
        int threshold = config.getContextDeviationThreshold();
        boolean shouldTrigger = roundsAfterBaseline >= threshold;
        if (shouldTrigger) {
            log.info("[Compression] Deviation correction trigger, roundsAfterBaseline={}", roundsAfterBaseline);
        }
        return shouldTrigger;
    }

    /**
     * 是否BaselineSaved
     * @return 是否成功（true 表示成功）
     */
    public boolean isBaselineSaved() {
        return baselineSaved;
    }

    /**
     * 保存Baseline
     * @param fullContext full上下文，不允许为 null
     */
    private void saveBaseline(List<ChatMessage> fullContext) {
        try {
            String json = Json.toJson(fullContext);
            MemoryEntry entry = MemoryEntry.builder()
                    .id(generateBaselineId())
                    .content(json)
                    .type(BASELINE_TYPE)
                    .importance(1.0)
                    .build();

            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager != null) {
                memoryManager.getStore().save(entry);
                memoryManager.close();
            }
            baselineSaved = true;
        } catch (Exception e) {
            log.error("[Compression] Save baseline snapshot failed", e);
            baselineSaved = false;
        }
    }

    /**
     * 加载Baseline
     * @return 结果列表，无数据时为空列表
     */
    public List<ChatMessage> loadBaseline() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return Collections.emptyList();
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(BASELINE_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                String json = entries.getFirst().getContent();
                List<ChatMessage> result = Json.fromJsonToList(json, ChatMessage.class);
                if (result != null) {
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("[Compression] Load baseline snapshot failed", e);
        }
        return Collections.emptyList();
    }

    /**
     * 保存BaselineSummary
     * @param summary 方法入参 summary
     */
    private void saveBaselineSummary(String summary) {
        try {
            MemoryEntry entry = MemoryEntry.builder()
                    .id("baseline-summary-" + System.currentTimeMillis())
                    .content(summary)
                    .type(BASELINE_SUMMARY_TYPE)
                    .importance(1.0)
                    .build();

            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager != null) {
                memoryManager.getStore().save(entry);
                memoryManager.close();
            }
        } catch (Exception e) {
            log.error("[Compression] Save baseline summary failed", e);
        }
    }

    /**
     * 加载BaselineSummary
     * @return 结果字符串
     */
    private String loadBaselineSummary() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return null;
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(BASELINE_SUMMARY_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                return entries.getFirst().getContent();
            }
        } catch (Exception e) {
            log.error("[Compression] Load baseline summary failed", e);
        }
        return null;
    }

    /** 保存RoundsAfterBaseline */
    private void saveRoundsAfterBaseline() {
        try {
            MemoryEntry entry = MemoryEntry.builder()
                    .id("rounds-counter")
                    .content(String.valueOf(roundsAfterBaseline))
                    .type(ROUNDS_COUNTER_TYPE)
                    .importance(1.0)
                    .build();

            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager != null) {
                memoryManager.getStore().save(entry);
                memoryManager.close();
            }
        } catch (Exception e) {
            log.error("[Compression] Save rounds counter failed", e);
        }
    }

    /**
     * 加载RoundsAfterBaseline
     * @return 结果数值
     */
    private int loadRoundsAfterBaseline() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return 0;
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(ROUNDS_COUNTER_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                String value = entries.getFirst().getContent();
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            log.error("[Compression] Load rounds counter failed", e);
        }
        return 0;
    }

    /**
     * SummarizeBaseline
     * @param client 客户端，不允许为 null
     * @param baseline 方法入参 baseline
     * @return 结果字符串
     */
    private String summarizeBaseline(ChatClient client, List<ChatMessage> baseline) {
        ChatClient freshClient = client.newChat();
        freshClient.system("You are a context compression assistant. "
                + " compress the following complete conversation history into a concise summary. "
                + " Strictly keep: key facts, user decisions, preferences, important commitments and core conclusions. "
                + " Ignore greetings and duplicate content. Output no more than 2000 characters.");
        String contextText = formatMessages(baseline);
        String summary = freshClient.chatSync(contextText);
        log.info("[Compression] Baseline summary generated, original={} rounds, summary={} chars",
                baseline.size(), summary != null ? summary.length() : 0);
        return summary != null ? summary : "";
    }

    /**
     * CorrectDeviation
     * @param client client
     * @param baselineSummary baselineSummary
     * @param currentContext currentContext
     */
    private List<ChatMessage> correctDeviation(ChatClient client,
                                                String baselineSummary,
                                                List<ChatMessage> currentContext) {
        ChatClient freshClient = client.newChat();
        freshClient.system("You are a context correction expert. "
                + " Your task is to merge [Baseline Summary] and [Current Context] into a corrected complete context. "
                + " Use the baseline summary as the core truth. "
                + " If current context contains information not in baseline, keep it. "
                + " If baseline contains important information missing in current context, add it back. "
                + " Output format: each line [role]: content, "
                + " must include original text of last " + config.getRetainMessages()
                + " uncompressed messages from current context, and one [COMPRESSED_CONTEXT] prefixed compression summary.");

        String prompt = "[Baseline Summary (Core Truth)]\n" + baselineSummary + "\n\n"
                + "[Current Context]\n" + formatMessages(currentContext) + "\n\n"
                + "Please output the corrected complete context:";

        String correctedText = freshClient.chatSync(prompt);
        return parseMessages(correctedText, currentContext);
    }

    /**
     * 构建CompressedText
     * @param messages 方法入参 messages
     * @return 结果字符串
     */
    private String buildCompressedText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ChatMessage msg : messages) {
            if (count++ > 0) {
                sb.append("\n");
            }
            sb.append("[").append(msg.getRole()).append("]: ").append(msg.getContent());
        }
        return sb.toString();
    }

    /**
     * 格式化Messages
     * @param messages 方法入参 messages
     * @return 结果字符串
     */
    private String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            sb.append("[").append(i + 1).append("][").append(msg.getRole())
                    .append("]: ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析Messages
     * @param correctedText corrected文本，不允许为 null
     * @param fallback 方法入参 fallback
     * @return 结果列表，无数据时为空列表
     */
    private List<ChatMessage> parseMessages(String correctedText, List<ChatMessage> fallback) {
        if (correctedText == null || correctedText.isBlank()) {
            return fallback;
        }

        List<ChatMessage> result = new ArrayList<>(); // [P3C 3.15 豁免] 文本按行解析，行数不可预判
        String[] lines = correctedText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf("]:");
            if (idx > 0 && line.startsWith("[")) {
                String roleAndContent = line.substring(idx + 2).trim();
                String role = line.substring(1, idx).trim();
                String cleanRole = role.replaceAll("\\d+", "").trim();
                if (cleanRole.isEmpty()) {
                    cleanRole = role;
                }
                result.add(new ChatMessage(cleanRole, roleAndContent));
            } else if (!line.startsWith("\u3010") && !line.startsWith("```")) {
                if (!result.isEmpty()) {
                    ChatMessage last = result.get(result.size() - 1);
                    result.set(result.size() - 1,
                            new ChatMessage(last.getRole(), last.getContent() + "\n" + line));
                }
            }
        }

        if (result.isEmpty()) {
            return fallback;
        }
        return result;
    }

    /**
     * 解析CompressionClient
     * @return Chat客户端 对象
     */
    private ChatClient resolveCompressionClient() {
        return compressionChatClient != null ? compressionChatClient : fallbackChatClient;
    }

    /**
     * 创建MemoryManager
     * @return MemoryManager 对象
     */
    private MemoryManager createMemoryManager() {
        try {
            MemoryConfig memConfig = MemoryConfig.builder()
                    .workspace(workspace)
                    .build();
            return new MemoryManager(memConfig);
        } catch (Exception e) {
            log.warn("[Compression] Create MemoryManager failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * GenerateBaselineId
     * @return 结果字符串
     */
    private static String generateBaselineId() {
        return "baseline-" + System.currentTimeMillis();
    }
}
