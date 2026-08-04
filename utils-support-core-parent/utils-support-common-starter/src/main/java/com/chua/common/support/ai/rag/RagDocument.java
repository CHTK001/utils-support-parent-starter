package com.chua.common.support.ai.rag;

import java.util.Map;

/**
 * RAG 文档元数据。
 *
 * @param id            文档唯一标识
 * @param fileName      文件名
 * @param fileType      文件扩展名（pdf/docx/xlsx/txt 等）
 * @param fileSize      文件大小（字节）
 * @param chunkCount    分块数量
 * @param status        处理状态：PROCESSING / READY / FAILED
 * @param errorMessage  处理失败时的错误信息
 * @param createTime    创建时间（Unix 毫秒时间戳）
 * @param metadata      扩展元数据
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public record RagDocument(
        String id,
        String fileName,
        String fileType,
        long fileSize,
        int chunkCount,
        String status,
        String errorMessage,
        long createTime,
        Map<String, Object> metadata
) {
    /**
     * 创建处理中的文档记录。
     */
    public static RagDocument processing(String id, String fileName, String fileType, long fileSize) {
        return new RagDocument(id, fileName, fileType, fileSize, 0, "PROCESSING", null, System.currentTimeMillis(), Map.of());
    }

    /**
     * 返回带错误状态的文档记录。
     */
    public RagDocument withError(String errorMessage) {
        return new RagDocument(id, fileName, fileType, fileSize, chunkCount, "FAILED", errorMessage, createTime, metadata);
    }

    /**
     * 返回带分块数的文档记录（状态置为 READY）。
     */
    public RagDocument withChunkCount(int chunkCount) {
        return new RagDocument(id, fileName, fileType, fileSize, chunkCount, "READY", null, createTime, metadata);
    }

    /**
     * 返回带指定状态的文档记录。
     */
    public RagDocument withStatus(String status) {
        return new RagDocument(id, fileName, fileType, fileSize, chunkCount, status, errorMessage, createTime, metadata);
    }
}