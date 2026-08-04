package com.chua.common.support.ai.rag;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * RAG 查询响应。
 *
 * @param answer   LLM 生成的回答
 * @param sources  命中的文档片段列表
 * @param metadata 扩展元数据
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public record RagResponse(
        String answer,
        List<Source> sources,
        Map<String, Object> metadata
) {

    /**
     * 命中的文档片段。
     *
     * @param documentId 文档 ID
     * @param content    文档片段内容
     * @param score      相似度分数
     * @param metadata   扩展元数据
     */
    public record Source(
            String documentId,
            String content,
            double score,
            Map<String, Object> metadata
    ) {}
}
