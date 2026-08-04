package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryEntry;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Context compression service - two phase strategy.
 *
 * <p>Phase 1: When message count reaches threshold, save full context as baseline snapshot,
 * then execute regular compression (keep last N messages).</p>
 * <p>Phase 2: After baseline established, every N rounds: load baseline snapshot,
 * summarize it with fresh ChatClient session, then correct deviation with current context.</p>
 *
 * <p>轻量复用入口见 {@link com.chua.common.support.ai.context.ContextCompressor}，
 * Agent 与普通 ChatClient 均可使用，不绑定工具/计划。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class AgentContextCompressionService implements AgentContextCompressionConsumer {

    private static final String BASELINE_TYPE = "context_baseline";

    private static final String BASELINE_SUMMARY_TYPE = "context_baseline_summary";

    private static final String ROUNDS_COUNTER_TYPE = "context_rounds_counter";

    private final AgentCompressionConfig config;

    private final ChatClient compressionChatClient;

    private final ChatClient fallbackChatClient;

    private final String workspace;

    private boolean baselineSaved = false;

    private int roundsAfterBaseline = 0;

    private String cachedBaselineSummary = null;

    public AgentContextCompressionService(AgentCompressionConfig config,
                                          ChatClient compressionChatClient,
                                          ChatClient fallbackChatClient,
                                          String workspace) {
        this.config = config != null ? config : AgentCompressionConfig.builder().build();
        this.compressionChatClient = compressionChatClient != null ? compressionChatClient : fallbackChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.workspace = workspace;
    }

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
            List<ChatMessage> result = new ArrayList<>();
            result.add(compressedMsg);
            result.addAll(retained);
            return result;
        }

        List<ChatMessage> toCompress = new ArrayList<>(fullContext.subList(0, fullContext.size() - retain));
        String compressedText = buildCompressedText(toCompress);
        ChatMessage compressedMsg = new ChatMessage("system", "[COMPRESSED_CONTEXT] " + compressedText);

        List<ChatMessage> result = new ArrayList<>();
        result.add(compressedMsg);
        result.addAll(retained);
        return result;
    }

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

    public boolean isBaselineSaved() {
        return baselineSaved;
    }

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

    public List<ChatMessage> loadBaseline() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return Collections.emptyList();
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(BASELINE_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                String json = entries.get(0).getContent();
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

    private String loadBaselineSummary() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return null;
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(BASELINE_SUMMARY_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                return entries.get(0).getContent();
            }
        } catch (Exception e) {
            log.error("[Compression] Load baseline summary failed", e);
        }
        return null;
    }

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

    private int loadRoundsAfterBaseline() {
        try {
            MemoryManager memoryManager = createMemoryManager();
            if (memoryManager == null) {
                return 0;
            }
            List<MemoryEntry> entries = memoryManager.getStore().listByType(ROUNDS_COUNTER_TYPE, 1);
            memoryManager.close();
            if (entries != null && !entries.isEmpty()) {
                String value = entries.get(0).getContent();
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

    private String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            sb.append("[").append(i + 1).append("][").append(msg.getRole())
                    .append("]: ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private List<ChatMessage> parseMessages(String correctedText, List<ChatMessage> fallback) {
        if (correctedText == null || correctedText.isBlank()) {
            return fallback;
        }

        List<ChatMessage> result = new ArrayList<>();
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

    private ChatClient resolveCompressionClient() {
        return compressionChatClient != null ? compressionChatClient : fallbackChatClient;
    }

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

    private static String generateBaselineId() {
        return "baseline-" + System.currentTimeMillis();
    }
}