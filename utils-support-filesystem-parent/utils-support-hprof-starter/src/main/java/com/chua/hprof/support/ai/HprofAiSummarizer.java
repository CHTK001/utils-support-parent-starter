package com.chua.hprof.support.ai;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * AI summary generator for hprof heap-dump analysis.
 *
 * <p>Builds a compact, structured prompt from the parsed
 * {@link HprofParser.Result} + {@link HprofAnalyzer.HprofAnalysis} and asks
 * a {@link ChatClient} to produce a short plain-language summary in Chinese:
 * what the dominant memory holders are, why they are retained, and the
 * top action to take. The summary is meant to be dropped verbatim into
 * the HTML report's "AI 总结" block.</p>
 *
 * <p>Usage:</p>
 *
 * <pre>{@code
 * ChatClient client = ChatClient.create("openai", "sk-xxx");
 * String summary = HprofAiSummarizer.of(client)
 *         .summarize(result);
 * }</pre>
 *
 * <p>When no {@link ChatClient} is configured, {@link #summarize} returns
 * {@code null} and the HTML renderer simply omits the AI block, so the
 * report degrades gracefully offline.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofAiSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
    * System prompt injected as the model's role.
    */
    private static final String SYSTEM_PROMPT =
            "你是一名 JVM 内存分析专家。根据给出的堆转储统计（算法判定 + 结论 + 排行 + GC 根），"
                    + "用中文写一段不超过 300 字的总结：指出最主要的内存占用来源、保留原因判断、"
                    + "以及一条最优先的处理建议。语言要直接、可执行，避免空泛套话。";

    /**
    * The ChatClient used to produce the summary (may be null).
    */
    private final ChatClient chatClient;

    /**
    * Model name override (optional).
    */
    private String model;

    private HprofAiSummarizer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
    * Create a summarizer that uses the given client.
    *
    * @param chatClient AI client, null disables summary
    * @return the summarizer
    */
    public static HprofAiSummarizer of(ChatClient chatClient) {
        return new HprofAiSummarizer(chatClient);
    }

    /**
    * Create a summarizer from a client setting.
    *
    * @param setting client setting
    * @return the summarizer, or a disabled one when the setting is null
    */
    public static HprofAiSummarizer of(ChatClientSetting setting) {
        if (setting == null) {
            return new HprofAiSummarizer(null);
        }
        return new HprofAiSummarizer(ChatClient.create(setting));
    }

    /**
    * Set the model name for the summary call.
    *
    * @param model model name, null clears
    * @return this
    */
    public HprofAiSummarizer model(String model) {
        this.model = model;
        return this;
    }

    /**
    * Whether an AI summary will actually be produced.
    *
    * @return true when a client is configured
    */
    public boolean isEnabled() {
        return chatClient != null;
    }

    /**
    * Produce the AI summary for a parsed result.
    *
    * @param result parsed result
    * @return summary text, or null when no client is configured
    */
    public String summarize(HprofParser.Result result) {
        if (chatClient == null || result == null) {
            return null;
        }
        HprofAnalysis analysis = HprofAnalyzer.analyze(result);
        String prompt = buildPrompt(analysis, result);
        ChatClient call = model != null ? chatClient.model(model) : chatClient;
        return call.system(SYSTEM_PROMPT).temperature(0.2).chatSync(prompt);
    }

    /**
    * Build the compact prompt from analysis + result.
    *
    * @param analysis analysis
    * @param result   parsed result
    * @return the prompt string
    */
    private static String buildPrompt(HprofAnalysis analysis, HprofParser.Result result) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("total_retained", HprofObject.formatSize(result.totalRetainedBytes()));
            payload.put("total_objects", result.totalObjectCount());

            List<Map<String, Object>> top = new java.util.ArrayList<>();
            for (HprofObject o : result.topRetained().subList(0, Math.min(10, result.topRetained().size()))) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("class", o.getClassName());
                m.put("instances", o.getInstanceCount());
                m.put("retained", HprofObject.formatSize(o.getRetainedSize()));
                m.put("gc_root", o.getGcRoot() != null ? o.getGcRoot() : "unknown");
                top.add(m);
            }
            payload.put("top_retained", top);

            payload.put("findings", analysis.findingDetails.stream()
                    .map(f -> f.severity() + ": " + f.title()).toList());
            payload.put("conclusions", analysis.conclusions);
            if (analysis.gcRootsByKind != null) {
                payload.put("gc_roots", analysis.gcRootsByKind);
            }
            return "堆转储统计（JSON）：\n" + MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // fall back to a plain-text prompt on any serialization problem
            return String.join("\n",
                    "总保留内存 " + HprofObject.formatSize(result.totalRetainedBytes()),
                    "对象数 " + result.totalObjectCount(),
                    "结论: " + String.join(" ", analysis.conclusions));
        }
    }
}
