package com.chua.common.support.ai.rag;

import java.util.List;
import java.util.Map;

/**
 * RAG 查询响应。
 *
 * @param answer   LLM 生成的回答
 * @param sources  命中的文档片段列表
 * @param metadata 扩展元数据
 * @author CH
 * @since 4.0.0.42
 */
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
     * @param isImage    来源是否为图片文件（前端据此渲染缩略图 / 预览）
     * @param metadata   扩展元数据（含 fileName / fileType / docId）
     */
    public record Source(
            String documentId,
            String content,
            double score,
            boolean isImage,
            Map<String, Object> metadata
    ) {
        /**
         * 兼容旧构造（不标记图片来源）。
         *
         * @param documentId 文档 ID
         * @param content    文档片段内容
         * @param score      相似度分数
         * @param metadata   扩展元数据
         * @return isImage=false 的 Source
         */
        public Source(String documentId, String content, double score, Map<String, Object> metadata) {
            this(documentId, content, score, false, metadata);
        }
    }
}
