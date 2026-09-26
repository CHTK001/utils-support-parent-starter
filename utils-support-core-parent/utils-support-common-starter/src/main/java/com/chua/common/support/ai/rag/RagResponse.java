package com.chua.common.support.ai.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * 规范构造器：对来源片段列表与扩展元数据做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 全部构造点传入的列表均非空且元素非空，列表使用 {@link List#copyOf}；
     * 元数据是任意对象映射，可能含 null 值，故采用可空安全写法。</p>
     *
     * @param answer   LLM 生成的回答
     * @param sources  命中的文档片段列表
     * @param metadata 扩展元数据，可为 null
     */
    public RagResponse {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources 不能为 null"));
        metadata = metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

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
         * 规范构造器：对扩展元数据做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。
         * 元数据直接来自向量库返回值，可能为 null 且可能含 null 值，
         * 故保留 null 语义并采用可空安全写法。</p>
         *
         * @param documentId 文档 ID
         * @param content    文档片段内容
         * @param score      相似度分数
         * @param isImage    来源是否为图片文件
         * @param metadata   扩展元数据，可为 null
         */
        public Source {
            metadata = metadata == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

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
