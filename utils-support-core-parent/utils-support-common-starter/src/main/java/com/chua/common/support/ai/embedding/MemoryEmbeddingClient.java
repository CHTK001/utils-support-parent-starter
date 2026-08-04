package com.chua.common.support.ai.embedding;

import com.chua.common.support.spi.annotations.Spi;
import org.jspecify.annotations.NullUnmarked;

/**
 * 内存嵌入向量客户端，用于测试和演示。
 * <p>
 * 基于文本哈希生成伪向量，无需外部服务。
 * 仅用于功能验证，不适合生产环境。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Spi("memory")
public class MemoryEmbeddingClient implements EmbeddingClient {

    private static final int DEFAULT_DIMENSIONS = 1536;

    private int dimensions = DEFAULT_DIMENSIONS;

    @Override
    public float[] embedding(String text) {
        return generatePseudoVector(text, dimensions);
    }

    @Override
    public float[][] embeddingBatch(String[] texts) {
        float[][] results = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            results[i] = generatePseudoVector(texts[i], dimensions);
        }
        return results;
    }

    @Override
    public EmbeddingClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    private float[] generatePseudoVector(String text, int dim) {
        float[] vector = new float[dim];
        int hash = text != null ? text.hashCode() : 0;
        for (int i = 0; i < dim; i++) {
            hash = hash * 31 + i;
            vector[i] = (float) Math.sin(hash) * 0.5f + 0.5f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }
}
