package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;


/**
* 向量知识库客户端接口。
* <p>
* 提供文档的向量索引、检索和管理能力，是 RAG 系统的核心存储抽象。
* 支持文本和向量两种检索方式，通过 SPI 机制扩展不同向量存储后端。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface KnowledgeClient extends AutoCloseable {

    /**
    * 知识库文档。
    *
    * @param id       文档唯一标识
    * @param content  文档内容
    * @param metadata 扩展元数据
    * @param embedding 向量（为 null 时由 VectorService 计算）
    */
    record Document(String id, String content, Map<String, Object> metadata, float[] embedding) {}

    /**
    * 检索结果。
    *
    * @param documentId 文档 ID
    * @param document   文档内容
    * @param score      相似度分数
    */
    record QueryResult(String documentId, Document document, double score) {}

    /**
    * SPI 工厂接口。
    */
    interface Factory {
        /**
        * 组装 KnowledgeClient 实例。
        *
        * @param vectorService  向量服务
        * @param vectorStorage  向量存储
        * @param textSplitter   文本分块器（为 null 则不分块）
        * @return KnowledgeClient 实例
        */
        KnowledgeClient assemble(VectorService vectorService, VectorStorage vectorStorage, TextSplitter textSplitter);
    }

    /**
    * 通过 SPI 组装 KnowledgeClient。
    *
    * @param vectorService  向量服务
    * @param vectorStorage  向量存储
    * @param textSplitter   文本分块器
    * @return KnowledgeClient 实例
    */
    static KnowledgeClient assemble(VectorService vectorService, VectorStorage vectorStorage, TextSplitter textSplitter) {
        return ServiceProvider.of(Factory.class).getDefault().assemble(vectorService, vectorStorage, textSplitter);
    }

    /**
    * 索引文档。
    *
    * @param document 待索引的文档
    */
    void upsert(Document document);

    /**
    * 索引文档（便捷方法）。
    *
    * @param documentId 文档 ID
    * @param content    文档内容
    * @param metadata   扩展元数据
    */
    default void upsert(String documentId, String content, Map<String, Object> metadata) {
        upsert(new Document(documentId, content, metadata, null));
    }

    /**
    * 索引文档（带预分块）。
    *
    * @param documentId 文档 ID
    * @param content    文档内容
    * @param metadata   扩展元数据
    * @param chunks     预分块结果
    */
    void upsert(String documentId, String content, Map<String, Object> metadata, List<TextChunk> chunks);

    /**
    * 删除指定文档的所有分块。
    *
    * @param documentId 文档 ID
    * @return 是否成功
    */
    boolean remove(String documentId);

    /**
    * 清空知识库中的所有文档。
    */
    void clear();

    /**
    * 文本检索。
    *
    * @param queryText 查询文本
    * @param topK      返回结果数量
    * @return 按相似度排序的检索结果
    */
    List<QueryResult> search(String queryText, int topK);

    /**
    * 向量检索。
    *
    * @param vector 查询向量
    * @param topK   返回结果数量
    * @return 按相似度排序的检索结果
    */
    List<QueryResult> search(float[] vector, int topK);

    @Override
    /** 关闭 */
    default void close() {}
}
