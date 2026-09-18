package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.embed.OllamaEmbedRequest;
import io.github.ollama4j.models.embed.OllamaEmbedResult;
import io.github.ollama4j.models.response.Model;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ollama 本地嵌入向量客户端（SPI provider="ollama"）。
 *
 * <p>基于 ollama4j 原生 API（{@code /api/embed}）实现文本向量化，
 * 支持单条与批量向量，模型列表通过 {@code listModels()} 动态获取。
 * 默认地址 {@code http://localhost:11434}，无需 API Key。
 *
 * <p>调用示例：
 * <pre>{@code
 *   float[] vector = EmbeddingClient.create("ollama", "")
 *       .model("nomic-embed-text")
 *       .embedding("要向量化的文本");
 *
 *   float[][] vectors = EmbeddingClient.create("ollama", "")
 *       .model("nomic-embed-text")
 *       .embeddingBatch(new String[]{"文本1", "文本2"});
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ollama")
public class OllamaEmbeddingClient implements EmbeddingClient {

    /**
    * 客户端 配置
    */
    private final EmbeddingClientSetting setting;

    /**
    * ollama4j 原生 客户端
    */
    private final Ollama ollama;

    /**
    * 当前 模型 名称
    */
    private String model;

    /**
    * 输出 向量 维度
    */
    private Integer dimensions;

    /**
    * 创建 Ollama 嵌入 客户端。
    *
    * @param setting 客户端 配置（provider 应为 "ollama"，apiKey 可为 空）
    */
    public OllamaEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
        this.ollama = OllamaSupport.client(setting != null ? setting.getBaseUrl() : null);
        this.model = setting != null ? setting.getModel() : null;
        this.dimensions = setting != null ? setting.getDimensions() : null;
    }

    @Override
    /** 提供者 */
    public EmbeddingClient provider(String provider) {
        return this;
    }

    @Override
    /** 模型 */
    public EmbeddingClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 维度 */
    public EmbeddingClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    @Override
    /** 嵌入 */
    public float[] embedding(String text) {
        OllamaEmbedResult result = doEmbed(List.of(text));
        List<List<Double>> embeddings = result.getEmbeddings();
        if (embeddings == null || embeddings.isEmpty()) {
            return new float[0];
        }
        return toFloats(embeddings.getFirst());
    }

    @Override
    /** 嵌入批量 */
    public float[][] embeddingBatch(String[] texts) {
        if (texts == null || texts.length == 0) {
            return new float[0][];
        }
        OllamaEmbedResult result = doEmbed(List.of(texts));
        List<List<Double>> embeddings = result.getEmbeddings();
        if (embeddings == null) {
            return new float[0][];
        }
        float[][] out = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            out[i] = toFloats(embeddings.get(i));
        }
        return out;
    }

    @Override
    /** 嵌入with响应 */
    public EmbeddingResponse embeddingWithResponse(String text) {
        float[] v = embedding(text);
        return EmbeddingResponse.builder()
                .embeddings(List.of(EmbeddingResponse.Embedding.builder()
                        .vector(v).index(0).dimensions(v != null ? v.length : 0).build()))
                .build();
    }

    @Override
    /** 嵌入batchwith响应 */
    public EmbeddingResponse embeddingBatchWithResponse(String[] texts) {
        float[][] vs = embeddingBatch(texts);
        AtomicInteger idx = new AtomicInteger(0);
        List<EmbeddingResponse.Embedding> embs = new ArrayList<>(vs.length);
        for (float[] v : vs) {
            embs.add(EmbeddingResponse.Embedding.builder()
                    .vector(v).index(idx.getAndIncrement()).dimensions(v != null ? v.length : 0).build());
        }
        return EmbeddingResponse.builder().embeddings(embs).build();
    }

    @Override
    /** 模型列表 */
    public List<ModelDefinition> models() {
        try {
            List<Model> raw = ollama.listModels();
            List<ModelDefinition> result = new ArrayList<>();
            if (raw != null) {
                for (Model m : raw) {
                    if (m == null || m.getName() == null || m.getName().isBlank()) {
                        continue;
                    }
                    result.add(ModelDefinition.builder()
                            .id(m.getName())
                            .name(m.getName())
                            .provider("ollama")
                            .description("本地 Ollama 模型")
                            .capabilities(List.of("text-embedding"))
                            .build());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[Ollama] 获取 模型列表 异常: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        // ollama4j 客户端无状态，无需释放
    }

    /**
    * 调用 ollama4j {@code /api/embed} 端点。
    *
    * @param texts 待 向量 化 文本 列表
    * @return 嵌入 结果
    */
    private OllamaEmbedResult doEmbed(List<String> texts) {
        String resolvedModel = model != null && !model.isBlank() ? model : "nomic-embed-text";
        OllamaEmbedRequest request = new OllamaEmbedRequest(resolvedModel, texts);
        if (dimensions != null) {
            request.setOptions(java.util.Map.of("dimensions", dimensions));
        }
        try {
            return ollama.embed(request);
        } catch (Exception e) {
            throw OllamaSupport.wrap("embed", e);
        }
    }

    /**
    * 转换 double 向量 为 float 向量。
    *
    * @param doubles 原始 向量
    * @return float 向量
    */
    private float[] toFloats(List<Double> doubles) {
        if (doubles == null) {
            return new float[0];
        }
        float[] out = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            out[i] = doubles.get(i) != null ? doubles.get(i).floatValue() : 0f;
        }
        return out;
    }
}
