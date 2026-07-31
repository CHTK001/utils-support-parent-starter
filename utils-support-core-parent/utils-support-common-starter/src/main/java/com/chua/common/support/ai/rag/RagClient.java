package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.function.Consumer;

/**
 * RAG 客户端接口。
 * <p>
 * 提供检索增强生成（RAG）的统一抽象，支持文档管理和语义查询。
 * 通过 SPI 机制按 provider 名称扩展不同实现，调用方通过工厂方法获取实例。
 * </p>
 *
 * <p>链式构建示例：
 * <pre>{@code
 *   // 构建 RAG 客户端
 *   RagClient client = RagClient.create("memory", setting)
 *       .topK(10)
 *       .similarityThreshold(0.5)
 *       .system("你是一名技术文档助手，基于提供的文档回答问题");
 *
 *   // 上传文档
 *   client.uploadDocument("guide.pdf", pdfBytes);
 *
 *   // 查询
 *   RagResponse response = client.query("如何配置数据源？");
 *   System.out.println(response.answer());
 *
 *   // 流式查询
 *   client.queryStream("如何配置数据源？", answer -> System.out.print(answer));
 *
 *   client.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RagClient extends AutoCloseable {

    /**
     * 创建指定 provider 的 RagClient。
     *
     * @param provider 实现提供者名称，如 "memory"
     * @param setting  客户端配置
     * @return RagClient 实例
     */
    static RagClient create(String provider, RagClientSetting setting) {
        return ServiceProvider.of(RagClient.class)
                .getNewExtension(provider, setting);
    }

    // ==================== 链式配置 ====================

    /**
     * 设置 Top-K 检索数量。
     *
     * @param topK 返回结果数量
     * @return 当前客户端实例，支持链式调用
     */
    default RagClient topK(int topK) {
        return this;
    }

    /**
     * 设置相似度阈值。
     *
     * @param threshold 相似度阈值 (0~1)，低于此值的结果被过滤
     * @return 当前客户端实例，支持链式调用
     */
    default RagClient similarityThreshold(double threshold) {
        return this;
    }

    /**
     * 设置系统提示词。
     * <p>
     * 用于 RAG 查询时注入到 prompt 中的系统角色设定。
     * </p>
     *
     * @param system 系统提示词内容
     * @return 当前客户端实例，支持链式调用
     */
    default RagClient system(String system) {
        return this;
    }

    /**
     * 设置温度参数。
     *
     * @param temperature 温度值，取值范围 [0.0, 2.0]
     * @return 当前客户端实例，支持链式调用
     */
    default RagClient temperature(double temperature) {
        return this;
    }

    /**
     * 设置最大输出 Token 数。
     *
     * @param maxTokens 最大 Token 数量
     * @return 当前客户端实例，支持链式调用
     */
    default RagClient maxTokens(int maxTokens) {
        return this;
    }

    // ==================== 查询 ====================

    /**
     * RAG 查询。
     *
     * @param query 用户查询文本
     * @return 查询响应（包含回答和命中文档）
     */
    RagResponse query(String query);

    /**
     * RAG 查询（自定义参数）。
     *
     * @param query               用户查询文本
     * @param topK                检索数量
     * @param similarityThreshold 相似度阈值
     * @return 查询响应
     */
    RagResponse query(String query, int topK, double similarityThreshold);

    /**
     * RAG 流式查询。
     *
     * @param query    用户查询文本
     * @param consumer 流式回答回调
     */
    void queryStream(String query, Consumer<String> consumer);

    /**
     * RAG 流式查询（自定义参数）。
     *
     * @param query               用户查询文本
     * @param topK                检索数量
     * @param similarityThreshold 相似度阈值
     * @param consumer            流式回答回调
     */
    void queryStream(String query, int topK, double similarityThreshold, Consumer<String> consumer);

    // ==================== 文档管理 ====================

    /**
     * 上传文档。
     *
     * @param fileName 文件名
     * @param data     文件字节数据
     * @return 文档元数据
     */
    RagDocument uploadDocument(String fileName, byte[] data);

    /**
     * 删除文档。
     *
     * @param docId 文档 ID
     * @return 是否成功
     */
    boolean deleteDocument(String docId);

    /**
     * 分页查询文档列表。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 文档列表
     */
    List<RagDocument> listDocuments(int page, int pageSize);

    /**
     * 获取文档总数。
     *
     * @return 文档数量
     */
    int documentCount();

    /**
     * 重新索引所有 READY 状态的文档。
     *
     * @return 重新索引的文档数量
     */
    int reindex();

    /**
     * 读取文档原始内容。
     *
     * @param docId 文档 ID
     * @return 文档内容，不存在则返回 null
     */
    String readDocumentContent(String docId);

    // ==================== 设置注入 ====================

    /**
     * 获取当前配置。
     *
     * @return 客户端配置
     */
    RagClientSetting getSetting();

    /**
     * 运行时替换 ChatClient。
     *
     * @param chatClient 新的对话客户端
     */
    void setChatClient(ChatClient chatClient);

    /**
     * 运行时替换 EmbeddingClient。
     *
     * @param embeddingClient 新的嵌入向量客户端
     */
    void setEmbeddingClient(EmbeddingClient embeddingClient);

    @Override
    default void close() {}
}
