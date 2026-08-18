package com.chua.common.support.ai.memory;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.lang.datasource.engine.Engine;
import lombok.Builder;
import lombok.Data;

/**
 * 记忆体配置
 *
 * <p>控制记忆体的核心行为参数，包括存储路径、文本限制、总结用的 ChatClient 和备份策略。
 *
 * @author CH
 * @since 2026/07/16
 */
@Data
@Builder
public class MemoryConfig {

    /**
     * 存储类型：{@code file}（默认）| {@code engine}。
     * <p>为 {@code engine} 时必须提供 {@link #engine}。</p>
     */
    @Builder.Default
    /** Store类型 */
    private String storeType = "file";

    /**
     * Engine 实例（{@link #storeType}={@code engine} 时使用，亦可直接注入 {@link EngineMemoryStore}）。
     */
    private Engine engine;

    /**
     * 工作间目录路径
     *
     * <p>记忆体文件存储的根目录，默认为当前目录下的 .agent/memory/。
     * 该目录下按 session 分子目录存储记忆条目。
     */
    @Builder.Default
    /** Workspace */
    private String workspace = ".agent/memory";

    /**
     * 单条记忆最大字符数
     *
     * <p>超过此长度的记忆内容将被截断。防止单条记忆过大影响检索效率。
     * 默认 2000 字符。
     */
    @Builder.Default
    /** 最大值内容长度 */
    private int maxContentLength = 2000;

    /**
     * 最大记忆条数
     *
     * <p>记忆体中保留的最大条目数量。超出时按时间淘汰最旧的条目。
     * 默认 500 条。
     */
    @Builder.Default
    /** 最大值entries */
    private int maxEntries = 500;

    /**
     * 总结用的 ChatClient
     *
     * <p>用于将对话内容提炼为高质量记忆条目。
     * 建议使用能力较强的模型（如 gpt-4o、claude-sonnet）以获得更好的总结质量。
     * 未设置时使用原始对话内容作为记忆（不做 AI 总结）。
     */
    private ChatClient summarizerClient;

    /**
     * 总结用的模型名称
     *
     * <p>指定总结时使用的模型。如未设置则使用 summarizerClient 的默认模型。
     */
    private String summarizerModel;

    /**
     * 总结 prompt 模板
     *
     * <p>用于指导 ChatClient 如何总结对话内容。
     * 模板中使用 {content} 作为对话内容的占位符。
     * 为 null 时使用内置默认模板。
     */
    private String summarizerPrompt;

    /**
     * 是否启用自动记忆
     *
     * <p>为 true 时，每次对话结束后自动调用 summarizerClient 生成记忆。
     * 默认为 true。
     */
    @Builder.Default
    /** Autosummarize */
    private boolean autoSummarize = true;
}
