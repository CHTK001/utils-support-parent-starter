package com.chua.common.support.ai.embedding;

import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 嵌入向量客户端接口。
 * <p>
 * 提供统一的文本向量化调用抽象，支持同步、异步和批量三种调用方式。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 * </p>
 *
 * <p>链式配置示例：
 * <pre>{@code
 *   float[] vector = EmbeddingClient.create("openai", "sk-xxx")
 *       .model("text-embedding-3-small")
 *       .dimensions(256)
 *       .embedding("要向量化的文本");
 * }</pre>
 *
 * <p>批量调用示例：
 * <pre>{@code
 *   EmbeddingClient client = EmbeddingClient.create("openai", "sk-xxx")
 *       .model("text-embedding-3-small");
 *   float[][] vectors = client.embeddingBatch(new String[]{"文本1", "文本2", "文本3"});
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public interface EmbeddingClient extends AutoCloseable {

    /**
     * 创建指定 provider 的 AI 嵌入向量客户端。
     * <p>
     * 通过 SPI 查找并实例化与 provider 名称匹配的 {@link EmbeddingClient} 实现。
     * </p>
     *
     * @param provider AI 服务商名称，如 "openai"、"deepseek" 等
     * @param apiKey   API 密钥
     * @return EmbeddingClient 实例
     */
    static EmbeddingClient create(String provider, String apiKey) {
        return ServiceProvider.of(EmbeddingClient.class)
                .getNewExtension(provider, EmbeddingClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
     * 通过完整配置创建 AI 嵌入向量客户端。
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl 等
     * @return EmbeddingClient 实例
     */
    static EmbeddingClient create(EmbeddingClientSetting setting) {
        return ServiceProvider.of(EmbeddingClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 和自定义地址的 AI 嵌入向量客户端。
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 地址
     * @return EmbeddingClient 实例
     */
    static EmbeddingClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(EmbeddingClient.class)
                .getNewExtension(provider, EmbeddingClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    // ==================== 链式配置 ====================

    /**
     * 设置 AI 服务商。
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default EmbeddingClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称，如 "text-embedding-3-small"
     * @return 当前客户端实例，支持链式调用
     */
    default EmbeddingClient model(String model) {
        return this;
    }

    /**
     * 设置输出向量维度。
     * <p>
     * 部分模型支持通过此参数指定输出向量的维度数。
     * 例如 text-embedding-3-small 支持 256、512、1536 等。
     * </p>
     *
     * @param dimensions 向量维度
     * @return 当前客户端实例，支持链式调用
     */
    default EmbeddingClient dimensions(int dimensions) {
        return this;
    }

    // ==================== 核心方法 ====================

    /**
     * 单文本向量化。
     * <p>
     * 将单条文本转换为嵌入向量。
     * </p>
     *
     * @param text 待向量化的文本
     * @return 浮点数向量
     */
    float[] embedding(String text);

    /**
     * 单文本向量化（返回完整响应对象）。
     * <p>
     * 将单条文本转换为嵌入向量，返回包含向量和用量信息的响应对象。
     * </p>
     *
     * @param text 待向量化的文本
     * @return 包含向量和用量信息的完整响应
     */
    default EmbeddingResponse embeddingWithResponse(String text) {
        float[] vector = embedding(text);
        return EmbeddingResponse.builder()
                .embeddings(List.of(EmbeddingResponse.Embedding.builder()
                        .vector(vector)
                        .index(0)
                        .dimensions(vector != null ? vector.length : 0)
                        .build()))
                .build();
    }

    /**
     * 批量文本向量化。
     * <p>
     * 将多条文本批量转换为嵌入向量，性能优于逐条调用。
     * </p>
     *
     * @param texts 待向量化的文本列表
     * @return 浮点数向量数组，顺序与输入一致
     */
    float[][] embeddingBatch(String[] texts);

    /**
     * 批量文本向量化（返回完整响应对象）。
     * <p>
     * 将多条文本批量转换为嵌入向量，返回包含向量和用量信息的响应对象。
     * </p>
     *
     * @param texts 待向量化的文本列表
     * @return 包含向量和用量信息的完整响应
     */
    default EmbeddingResponse embeddingBatchWithResponse(String[] texts) {
        float[][] vectors = embeddingBatch(texts);
        AtomicInteger idx = new AtomicInteger(0);
        List<EmbeddingResponse.Embedding> embeddings = new ArrayList<>();
        for (float[] v : vectors) {
            embeddings.add(EmbeddingResponse.Embedding.builder()
                    .vector(v)
                    .index(idx.getAndIncrement())
                    .dimensions(v != null ? v.length : 0)
                    .build());
        }
        return EmbeddingResponse.builder()
                .embeddings(embeddings)
                .build();
    }

    /**
     * 异步单文本向量化。
     * <p>
     * 通过 CompletableFuture 异步执行单文本向量化。
     * </p>
     *
     * @param text 待向量化的文本
     * @return 异步任务，完成时返回浮点数向量
     */
    default CompletableFuture<float[]> embeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embedding(text));
    }

    /**
     * 异步批量文本向量化。
     * <p>
     * 通过 CompletableFuture 异步执行批量文本向量化。
     * </p>
     *
     * @param texts 待向量化的文本列表
     * @return 异步任务，完成时返回浮点数向量数组
     */
    default CompletableFuture<float[][]> embeddingBatchAsync(String[] texts) {
        return CompletableFuture.supplyAsync(() -> embeddingBatch(texts));
    }

    /**
     * 关闭客户端，释放底层资源。
     */
    @Override
    default void close() {
    }
}