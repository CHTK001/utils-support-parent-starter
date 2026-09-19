package com.chua.deeplearning.support.onnx.embedding.minilm;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全部-minilm-L6-v2 本地离线嵌入客户端（SPI 提供者="minilm"）。
 *
 * <p>本地基于 Xenova/all-MiniLM-L6-v2 的 int8 量化 ONNX（23MB，~50ms/句，CPU 即可），
 * 文本 → 384 维 L2 归一化句向量。与 sentence-transformers/全部-minilm-L6-v2 语义一致，
 * 可直接用于余弦相似度 / 向量检索 / 聚类。</p>
 *
 * <p>用法（与云端 EmbeddingClient 完全一致）：
 * <pre>{@code
 *   float[] v = EmbeddingClient.create("minilm", "")
 *       .model("minilm")
 *       .embedding("你好世界");
 *
 *   EmbeddingClient client = EmbeddingClient.create("minilm", "")
 *       .model("minilm");
 *   float[][] vs = client.embeddingBatch(new String[]{"doc1", "doc2"});
 * }</pre>   float[][] vs = client.embeddingBatch(new String[]{"doc1", "doc2"});
 * }</pre>
 * </p>
 *
 * <p>资源位于 {@code nlp/embedding/minilm/model_quantized.onnx}（int8 量化版）或
 * {@code nlp/embedding/minilm-fp32/model.onnx}（fp32 未量化版），由 jar
 * {@code utils-support-models-onnx-minilm-int8} 或 {@code utils-support-models-onnx-minilm-fp32} 提供。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniLMEmbeddingClient implements EmbeddingClient {

    /**
     * 默认最大序列长度（包含 [CLS]/[SEP]）
     */
    private static final int DEFAULT_MAX_LEN = 128;

    /**
     * 设置
    */
    private final EmbeddingClientSetting setting;
    /**
     * 翻译器
    */
    private volatile MiniLMEmbeddingTranslator translator;
    /**
     * 已解析的模型标识
    */
    private volatile String resolvedModel;

    /**
     * 创建 minilm嵌入客户端 实例
     * @param setting setting
     */
    public MiniLMEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
    }

    @Override
    /**
     * 提供者
    */
    public EmbeddingClient provider(String provider) {
        setting.setProvider(provider);
        return this;
    }

    @Override
    /**
     * 模型
    */
    public EmbeddingClient model(String model) {
        setting.setModel(model);
        this.resolvedModel = null;
        return this;
    }

    @Override
    /**
     * 维度
    */
    public EmbeddingClient dimensions(int dimensions) {
        setting.setDimensions(dimensions);
        return this;
    }

    /**
     * Translator
     *
     * @return translator的结果
     */
    private MiniLMEmbeddingTranslator translator() {
        String model = setting.getModel();
        String key = model == null || model.isBlank() || "minilm".equalsIgnoreCase(model)
                || "minilm-int8".equalsIgnoreCase(model) ? "int8" : "fp32";
        MiniLMEmbeddingTranslator current = translator;
        if (current != null && key.equals(resolvedModel)) {
            return current;
        }
        synchronized (this) {
            if (translator != null && key.equals(resolvedModel)) {
                return translator;
            }
            if (translator != null) {
                translator.close();
            }
            translator = "fp32".equals(key)
                    ? MiniLMEmbeddingTranslator.fp32()
                    : MiniLMEmbeddingTranslator.int8();
            resolvedModel = key;
            log.info("[minilm-embedding] 使用 {} 版本模型: {}", key, translator.getClass().getSimpleName());
            return translator;
        }
    }

    @Override
    /**
     * 嵌入
    */
    public float[] embedding(String text) {
        try {
            int maxLen = setting.getMaxLen() != null && setting.getMaxLen() > 0 ? setting.getMaxLen() : DEFAULT_MAX_LEN;
            return translator().embed(text, maxLen);
        } catch (Exception e) {
            throw new RuntimeException("[minilm-embedding] embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    /**
     * 嵌入batch
    */
    public float[][] embeddingBatch(String[] texts) {
        if (texts == null || texts.length == 0) {
            return new float[0][];
        }
        float[][] result = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            result[i] = embedding(texts[i]);
        }
        return result;
    }

    @Override
    /**
     * 嵌入with响应
    */
    public EmbeddingResponse embeddingWithResponse(String text) {
        float[] v = embedding(text);
        return EmbeddingResponse.builder()
                .embeddings(List.of(EmbeddingResponse.Embedding.builder()
                        .vector(v)
                        .index(0)
                        .dimensions(v != null ? v.length : 0)
                        .build()))
                .build();
    }

    @Override
    /**
     * 嵌入batchwith响应
    */
    public EmbeddingResponse embeddingBatchWithResponse(String[] texts) {
        float[][] vs = embeddingBatch(texts);
        AtomicInteger idx = new AtomicInteger(0);
        List<EmbeddingResponse.Embedding> embs = new ArrayList<>();
        for (float[] v : vs) {
            embs.add(EmbeddingResponse.Embedding.builder()
                    .vector(v)
                    .index(idx.getAndIncrement())
                    .dimensions(v != null ? v.length : 0)
                    .build());
        }
        return EmbeddingResponse.builder()
                .embeddings(embs)
                .build();
    }

    @Override
    /**
     * 嵌入异步
    */
    public CompletableFuture<float[]> embeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embedding(text));
    }

    @Override
    /**
     * 嵌入batch异步
    */
    public CompletableFuture<float[][]> embeddingBatchAsync(String[] texts) {
        return CompletableFuture.supplyAsync(() -> embeddingBatch(texts));
    }

    @Override
    /**
     * 关闭
    */
    public synchronized void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }
}
