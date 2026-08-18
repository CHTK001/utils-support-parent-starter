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
 * all-MiniLM-L6-v2 本地离线嵌入客户端（SPI provider="minilm"）。
 *
 * <p>本地基于 Xenova/all-MiniLM-L6-v2 的 int8 量化 ONNX（23MB，~50ms/句，CPU 即可），
 * 文本 → 384 维 L2 归一化句向量。与 sentence-transformers/all-MiniLM-L6-v2 语义一致，
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
 * }</pre>
 * </p>
 *
 * <p>资源位于 {@code nlp/embedding/minilm/model_quantized.onnx} + 配套
 * {@code vocab.txt}，由 jar {@code utils-support-models-onnx-minilm-l6v2} 提供。</p>
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

    /** 设置 */
    /** 设置 */
    private final EmbeddingClientSetting setting;
    /** 翻译器 */
    /** Translator */
    private MiniLMEmbeddingTranslator translator;

    public MiniLMEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
    }

    @Override
    public EmbeddingClient provider(String provider) {
        setting.setProvider(provider);
        return this;
    }

    @Override
    public EmbeddingClient model(String model) {
        setting.setModel(model);
        return this;
    }

    @Override
    public EmbeddingClient dimensions(int dimensions) {
        setting.setDimensions(dimensions);
        return this;
    }

    private synchronized MiniLMEmbeddingTranslator translator() {
        if (translator == null) {
            translator = new MiniLMEmbeddingTranslator();
        }
        return translator;
    }

    @Override
    public float[] embedding(String text) {
        try {
            int maxLen = setting.getMaxLen() != null && setting.getMaxLen() > 0 ? setting.getMaxLen() : DEFAULT_MAX_LEN;
            return translator().embed(text, maxLen);
        } catch (Exception e) {
            throw new RuntimeException("[minilm-embedding] embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
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
    public CompletableFuture<float[]> embeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embedding(text));
    }

    @Override
    public CompletableFuture<float[][]> embeddingBatchAsync(String[] texts) {
        return CompletableFuture.supplyAsync(() -> embeddingBatch(texts));
    }

    @Override
    public synchronized void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }
}
