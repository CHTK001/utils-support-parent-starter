package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地引擎文本嵌入客户端抽象基类。
 * <p>
 * 统一实现 {@link EmbeddingClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
 * 已注册的 String→float[] 翻译器执行文本向量化，并提供该引擎的模型列表。
 * 子类只需指定引擎名称（如 "onnx"、"pytorch"、"llama"）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractLocalEmbeddingClient implements EmbeddingClient {

    /**
     * 引擎名称（provider）
     */
    protected final String engine;

    /**
     * 识别引擎实例
     */
    protected final IdentificationEngine identificationEngine;

    /**
     * 当前模型名称
     */
    protected String model;

    /**
     * 输出向量维度
     */
    protected Integer dimensions;

    /**
     * 构造本地嵌入客户端。
     *
     * @param engine  引擎名称，如 "onnx"、"pytorch"、"llama"
     * @param setting 客户端配置
     */
    protected AbstractLocalEmbeddingClient(String engine, EmbeddingClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
        this.dimensions = setting != null ? setting.getDimensions() : null;
    }

    @Override
    /** Provider */
    public EmbeddingClient provider(String provider) {
        return this;
    }

    @Override
    /** Model */
    public EmbeddingClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Dimensions */
    public EmbeddingClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    /**
     * 解析实际使用的模型名称。
     *
     * @return 模型名称
     */
    protected String resolveModel() {
        if (model != null && !model.isBlank()) {
            return model;
        }
        List<ModelDefinition> defs = models();
        if (defs.isEmpty()) {
            throw new IllegalStateException("引擎[" + engine + "]没有可用的嵌入模型");
        }
        return defs.get(0).getId();
    }

    @Override
    /** Embedding */
    public float[] embedding(String text) {
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(text);
        if (result instanceof float[] floats) {
            return floats;
        }
        if (result instanceof double[] doubles) {
            float[] out = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                out[i] = (float) doubles[i];
            }
            return out;
        }
        throw new IllegalStateException("模型输出不是向量: " + modelName + " -> " + result);
    }

    @Override
    /** EmbeddingBatch */
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
    /** EmbeddingWithResponse */
    public EmbeddingResponse embeddingWithResponse(String text) {
        float[] v = embedding(text);
        return EmbeddingResponse.builder()
                .embeddings(List.of(EmbeddingResponse.Embedding.builder()
                        .vector(v).index(0).dimensions(v != null ? v.length : 0).build()))
                .build();
    }

    @Override
    /** EmbeddingBatchWithResponse */
    public EmbeddingResponse embeddingBatchWithResponse(String[] texts) {
        float[][] vs = embeddingBatch(texts);
        AtomicInteger idx = new AtomicInteger(0);
        List<EmbeddingResponse.Embedding> embs = new ArrayList<>();
        for (float[] v : vs) {
            embs.add(EmbeddingResponse.Embedding.builder()
                    .vector(v).index(idx.getAndIncrement()).dimensions(v != null ? v.length : 0).build());
        }
        return EmbeddingResponse.builder().embeddings(embs).build();
    }

    @Override
    /** EmbeddingAsync */
    public CompletableFuture<float[]> embeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embedding(text));
    }

    @Override
    /** EmbeddingBatchAsync */
    public CompletableFuture<float[][]> embeddingBatchAsync(String[] texts) {
        return CompletableFuture.supplyAsync(() -> embeddingBatch(texts));
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
