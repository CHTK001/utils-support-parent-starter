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
 * hprof 堆转储分析结果的 AI 总结生成器。
 *
 * <p>基于解析得到的 {@link HprofParser.Result} + {@link HprofAnalyzer.HprofAnalysis}
 * 拼装出一段紧凑、结构化的提示词，交给 {@link ChatClient} 用通俗中文生成简短总结：
 * 最主要的内存占用来源是什么、它们为何被保留，以及最优先的一条处理建议。
 * 总结文本会被原样放入 HTML 报告的 "AI 总结" 区块。</p>
 *
 * <p>用法：</p>
 *
 * <pre>{@code
 * ChatClient client = ChatClient.create("openai", "sk-xxx");
 * String summary = HprofAiSummarizer.of(client)
 *         .summarize(result);
 * }</pre>
 *
 * <p>未配置 {@link ChatClient} 时，{@link #summarize} 返回
 * {@code null}，HTML 渲染器随之省略 AI 区块，因此离线场景下报告可优雅降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofAiSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
    * 作为模型角色注入的系统提示词。
    */
    private static final String SYSTEM_PROMPT =
            "你是一名 JVM 内存分析专家。根据给出的堆转储统计（算法判定 + 结论 + 排行 + GC 根），"
                    + "用中文写一段不超过 300 字的总结：指出最主要的内存占用来源、保留原因判断、"
                    + "以及一条最优先的处理建议。语言要直接、可执行，避免空泛套话。";

    /**
    * 用于生成总结的 ChatClient（可能为 null）。
    */
    private final ChatClient chatClient;

    /**
    * 模型名覆盖（可选）。
    */
    private String model;

    /**
     * 构造方法，创建 HprofAiSummarizer 实例。
     *
     * @param chatClient chat客户端，不允许为 null
     */
    private HprofAiSummarizer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
    * 创建使用指定客户端的总结器。
    *
    * @param chatClient AI 客户端，为 null 时禁用总结
    * @return 总结器
    */
    public static HprofAiSummarizer of(ChatClient chatClient) {
        return new HprofAiSummarizer(chatClient);
    }

    /**
    * 依据客户端配置创建总结器。
    *
    * @param setting 客户端配置
    * @return 总结器；配置为 null 时返回禁用态的总结器
    */
    public static HprofAiSummarizer of(ChatClientSetting setting) {
        if (setting == null) {
            return new HprofAiSummarizer(null);
        }
        return new HprofAiSummarizer(ChatClient.create(setting));
    }

    /**
    * 设置总结调用使用的模型名。
    *
    * @param model 模型名，传 null 表示清除
    * @return 当前实例
    */
    public HprofAiSummarizer model(String model) {
        this.model = model;
        return this;
    }

    /**
    * 是否会真正产出 AI 总结。
    *
    * @return 已配置客户端时返回 true
    */
    public boolean isEnabled() {
        return chatClient != null;
    }

    /**
    * 为一次解析结果生成 AI 总结。
    *
    * @param result 解析结果
    * @return 总结文本；未配置客户端时返回 null
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
    * 由分析结论 + 解析结果拼装出紧凑的提示词。
    *
    * @param analysis 分析结论
    * @param result   解析结果
    * @return 提示词字符串
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
            // 序列化出现任何问题时，退化为纯文本提示词
            return String.join("\n",
                    "总保留内存 " + HprofObject.formatSize(result.totalRetainedBytes()),
                    "对象数 " + result.totalObjectCount(),
                    "结论: " + String.join(" ", analysis.conclusions));
        }
    }
}
