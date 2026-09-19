package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.vector.VectorStorage;
import lombok.Builder;
import lombok.Data;

import javax.annotation.Nonnull;

/**
 * RAG 客户端配置。
 * <p>
 * 封装 RAG 客户端所需的全部依赖，通过 Builder 模式构建。
 * 所有必填参数在构建时注入，可选参数提供默认值。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class RagClientSetting {

    /**
     * LLM 对话客户端，用于生成回答。
     */
    @Nonnull
    /** Chat客户端 */
    private ChatClient chatClient;

    /**
     * 嵌入向量客户端，用于文本向量化。
     */
    @Nonnull
    /** Embedding客户端 */
    private EmbeddingClient embeddingClient;

    /**
     * 文本提取器（PDF/Word/Excel → 文本），为 null 时纯文本文件直接读取。
     */
    private TextExtractor textExtractor;

    /**
     * 文本分块器，将长文本拆分为适合向量化的小片段。
     */
    @Nonnull
    /** 文本splitter */
    private TextSplitter textSplitter;

    /**
     * 向量存储后端。
     */
    @Nonnull
    /** Vector存储 */
    private VectorStorage vectorStorage;

    /**
     * 文件上传根目录。
     */
    @Nonnull
    /** 上传目录 */
    private String uploadDir;

    /**
     * 默认 Top-K 检索数量。
     */
    @Builder.Default
    /** 顶部K */
    private int topK = 5;

    /**
     * 相似度阈值 (0~1)，低于此值的检索结果被过滤。
     */
    @Builder.Default
    /** Similarity阈值 */
    private double similarityThreshold = 0.1;

    /**
     * RAG 问答系统提示词，置于检索上下文之前。
     */
    private String systemPrompt;
}
