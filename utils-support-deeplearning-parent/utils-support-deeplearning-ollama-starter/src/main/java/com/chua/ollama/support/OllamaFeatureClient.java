package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.embed.OllamaEmbedRequest;
import io.github.ollama4j.models.embed.OllamaEmbedResult;
import io.github.ollama4j.models.response.Model;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Ollama 本地特征提取客户端（SPI provider="ollama"）。
 *
 * <p>基于 ollama4j 原生 API（{@code /api/embed}）提取文本特征向量，
 * 与 {@link OllamaEmbeddingClient} 共用底层嵌入能力，
 * 面向 {@code FeatureClient} 门面（特征 检索 / 去重 场景）。
 * 默认地址 {@code http://localhost:11434}，无需 API Key。
 *
 * <p>调用示例：
 * <pre>{@code
 *   float[] vector = FeatureClient.create("ollama", "")
 *       .model("nomic-embed-text")
 *       .extract("要提取特征的文本");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ollama")
public class OllamaFeatureClient implements FeatureClient {

    /**
     * 客户端 配置
     */
    private final FeatureClientSetting setting;

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
     * 创建 Ollama 特征 客户端。
     *
     * @param setting 客户端 配置（provider 应为 "ollama"，apiKey 可为 空）
     */
    public OllamaFeatureClient(FeatureClientSetting setting) {
        this.setting = setting;
        this.ollama = OllamaSupport.client(setting != null ? setting.getBaseUrl() : null);
        this.model = setting != null ? setting.getModel() : null;
        this.dimensions = setting != null ? setting.getDimensions() : null;
    }

    @Override
    /** 提供者 */
    public FeatureClient provider(String provider) {
        return this;
    }

    @Override
    /** 模型 */
    public FeatureClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 维度 */
    public FeatureClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    @Override
    /** 特征提取 */
    public float[] extract(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        String resolvedModel = model != null && !model.isBlank() ? model : "nomic-embed-text";
        OllamaEmbedRequest request = new OllamaEmbedRequest(resolvedModel, List.of(text));
        if (dimensions != null) {
            request.setOptions(java.util.Map.of("dimensions", dimensions));
        }
        try {
            OllamaEmbedResult result = ollama.embed(request);
            List<List<Double>> embeddings = result.getEmbeddings();
            if (embeddings == null || embeddings.isEmpty()) {
                return new float[0];
            }
            List<Double> doubles = embeddings.getFirst();
            float[] out = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) {
                out[i] = doubles.get(i) != null ? doubles.get(i).floatValue() : 0f;
            }
            return out;
        } catch (Exception e) {
            throw OllamaSupport.wrap("embed(feature)", e);
        }
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
                            .capabilities(List.of("text-feature"))
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
}
