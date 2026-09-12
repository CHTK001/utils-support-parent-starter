package com.chua.deeplearning.support.onnx.embedding.wemm;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
* wemm-嵌入 本地离线嵌入客户端（SPI 提供者="wemm"）。
*
* <p>腾讯微信视觉团队开发的多模态嵌入模型，文本分支支持
* 2B / 4B / 9B 三档。输出 L2 归一化嵌入向量，可直接用于
* 余弦相似度 / 向量检索 / 语义搜索。</p>
*
* <p>用法（与云端 EmbeddingClient 完全一致）：
* <pre>{@code
*   float[] v = EmbeddingClient.create("wemm", "")
*       .model("wemm-embedding-2b")
*       .embedding("你好世界");
*
*   EmbeddingClient client = EmbeddingClient.create("wemm", "")
*       .model("wemm-embedding-4b");
*   float[][] vs = client.embeddingBatch(new String[]{"doc1", "doc2"});
* }</pre>dding-4b");
*   float[][] vs = client.embeddingBatch(new String[]{"doc1", "doc2"});
* }</pre>
* </p>
*
* <p>资源位于 {@code nlp/embedding/wemm-embedding-{2b|4b|9b}/}，
* 由 jar {@code utils-support-models-onnx-wemm-embedding-*} 提供。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class WeMMEmbeddingClient implements EmbeddingClient {

    /** 设置 */
    private final EmbeddingClientSetting setting;
    /** 翻译器 */
    private volatile WeMMEmbeddingTranslator translator;
    /** 已解析的模型标识 */
    private volatile String resolvedModel;

    /**
    * 创建 wemm嵌入客户端 实例
    * @param setting setting
     */
    public WeMMEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
    }

    /**
    * 解析模型标识为资源基础路径
    *
    * @param model 模型
    * @return 创建translator的结果
     */
    private WeMMEmbeddingTranslator createTranslator(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("9b")) {
            return WeMMEmbeddingTranslator.embedding9b();
        }
        if (m.contains("4b")) {
            return WeMMEmbeddingTranslator.embedding4b();
        }
 // 默认: 2b
        return new WeMMEmbeddingTranslator();
    }

    @Override
    /** 提供者 */
    public EmbeddingClient provider(String provider) {
        setting.setProvider(provider);
        return this;
    }

    @Override
    /** 模型 */
    public EmbeddingClient model(String model) {
        setting.setModel(model);
        this.resolvedModel = null;
        return this;
    }

    @Override
    /** 维度 */
    public EmbeddingClient dimensions(int dimensions) {
        setting.setDimensions(dimensions);
        return this;
    }

    /**
    * 获取翻译器
    *
    * @return translator的结果
     */
    private WeMMEmbeddingTranslator translator() {
        String model = setting.getModel();
        String key = model == null || model.isBlank() ? "wemm-embedding-2b" : model;
        WeMMEmbeddingTranslator current = translator;
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
            translator = createTranslator(key);
            resolvedModel = key;
            log.info("[wemm-embedding] using model: {}", key);
            return translator;
        }
    }

    @Override
    /** 嵌入 */
    public float[] embedding(String text) {
        try {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text cannot be blank");
            }
            int dim = setting.getDimensions() != null && setting.getDimensions() > 0
                    ? setting.getDimensions() : 0;
            WeMMEmbeddingTranslator t = translator();
            if (dim > 0) {
                return t.embed(text, dim);
            }
            return t.translate(text);
        } catch (Exception e) {
            throw new RuntimeException("[wemm-embedding] embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    /** 嵌入batch */
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
        List<EmbeddingResponse.Embedding> embs = new ArrayList<>();
        for (float[] v : vs) {
            embs.add(EmbeddingResponse.Embedding.builder()
                    .vector(v).index(idx.getAndIncrement()).dimensions(v != null ? v.length : 0).build());
        }
        return EmbeddingResponse.builder().embeddings(embs).build();
    }

    @Override
    /** 嵌入异步 */
    public CompletableFuture<float[]> embeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embedding(text));
    }

    @Override
    /** 嵌入batch异步 */
    public CompletableFuture<float[][]> embeddingBatchAsync(String[] texts) {
        return CompletableFuture.supplyAsync(() -> embeddingBatch(texts));
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }
}
