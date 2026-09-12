package com.chua.deeplearning.support.onnx.embedding.bge;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
   * BGE 文本嵌入客户端（SPI 提供者="bge"，离线/自动下载通用）。
 *
 * <p>底层为 BGE 系列（bge-small-zh / bge-m3）ONNX，输入
 * {@code input_ids + attention_mask}，输出已池化句向量。中英文通用，可直接用于
 * 余弦相似度 / 向量检索。</p>
 *
 * <p>离线版（jar 内，如 bge-small-zh）由 {@link HuggingFaceTokenizer} + ORT 加载；
 * 自动下载版（bge-m3）传入本地模型路径。两种都无需联网。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BgeEmbeddingClient implements EmbeddingClient {

    /**
     * 默认最大序列长度
     */
    private static final int DEFAULT_MAX_LEN = 512;

    /** 设置 */
    private final EmbeddingClientSetting setting;
    /** 翻译器 */
    /** Translator */
    private BgeEmbeddingTranslator translator;
    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;
    /** 是否已加载 */
    /** 加载 */
    private boolean loaded;

    /**
      * jar 内打包的资源目录（离线版），空 表示自动下载版
     */
    private String embeddedBase;
    /** 嵌入式模型名称 */
    /** Embedded模型 */
    private final String embeddedModel;
    /** 嵌入式分词器名称 */
    /** Embeddedtokenizer */
    private final String embeddedTokenizer;

    /**
     * 本地模型目录（自动下载版经 registry 解析后传入）
     */
    private Path localModelRoot;
    /** 嵌入式本地目录 */
    /** Embedded本地目录 */
    private Path embeddedLocalDir;
    /** 模型路径 */
    private Path modelPath;

    /**
      * 创建 bge嵌入客户端 实例
     * @param setting setting
     */
    public BgeEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
        this.embeddedModel = "model.onnx";
        this.embeddedTokenizer = "tokenizer.json";
        this.embeddedBase = resolveEmbeddedBase(setting.getModel());
        log.info("[BGE] init model='{}' embeddedBase='{}'", setting.getModel(), this.embeddedBase);
    }

    /**
     * 解析embeddedbase
     *
     * @param model 模型
     * @return resolveEmbeddedBase的结果
     */
    private String resolveEmbeddedBase(String model) {
        if (model == null) {
            return null;
        }
        String m = model.toLowerCase();
        if (m.contains("bge-large-zh") || m.contains("bge_large_zh")) {
            return "nlp/embedding/bge-large-zh-v1.5/";
        }
        if (m.contains("bge-small-zh") || m.contains("bge_small_zh")) {
            return "nlp/embedding/bge-small-zh-v1.5/";
        }
        if (m.contains("bge-small-en") || m.contains("bge_small_en")) {
            return "nlp/embedding/bge-small-en-v1.5/";
        }
        return null;
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
        this.embeddedBase = resolveEmbeddedBase(model);
        resetLoaded();
        return this;
    }

    @Override
    /** 维度 */
    public EmbeddingClient dimensions(int dimensions) {
        setting.setDimensions(dimensions);
        return this;
    }

    /** 重置加载 */
    private void resetLoaded() {
        loaded = false;
        translator = null;
        tokenizer = null;
        embeddedLocalDir = null;
    }

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        translateModel();
        loaded = true;
    }

    /**
     * 将 registry 解析到的模型路径适配为本地可加载形式。
     */
    private void translateModel() throws Exception {
        if (embeddedBase != null) {
            translator = new BgeEmbeddingTranslator();
            Path dir = translator.extractFromClasspath(embeddedBase, embeddedModel, embeddedTokenizer);
            embeddedLocalDir = dir;
            modelPath = dir.resolve(embeddedModel);
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(dir.resolve("tokenizer.json"))
                    .optPadding(true)
                    .optMaxLength(DEFAULT_MAX_LEN)
                    .build();
        } else if (localModelRoot != null && Files.isDirectory(localModelRoot)) {
            translator = new BgeEmbeddingTranslator();
            Path dir = localModelRoot;
            Path model = dir.resolve("model.onnx");
            if (Files.isRegularFile(model)) {
                translator.loadLocal(model.toString());
            } else {
                translator.loadLocal(dir.toString());
            }
            modelPath = model;
            Path tk = dir.resolve("tokenizer.json");
            if (Files.isRegularFile(tk)) {
                tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tk)
                        .optPadding(true)
                        .optMaxLength(DEFAULT_MAX_LEN)
                        .build();
            }
        } else {
            throw new IllegalStateException("BGE 模型资源未就绪: model=" + setting.getModel()
                    + " localModelRoot=" + (localModelRoot == null ? "null" : localModelRoot));
        }
    }

    /**
     * 设置本地模型目录（自动下载版）。
     *
     * @param path 模型文件或目录
     */
    public void setLocalModel(Path path) {
        this.localModelRoot = path;
        resetLoaded();
    }

    @Override
    /** 嵌入 */
    public float[] embedding(String text) {
        try {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本不能为空");
            }
            prepare();
            var encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            int seqLen = Math.min(ids.length, DEFAULT_MAX_LEN);
            long[] idsTrim = new long[seqLen];
            long[] maskTrim = new long[seqLen];
            System.arraycopy(ids, 0, idsTrim, 0, seqLen);
            System.arraycopy(mask, 0, maskTrim, 0, seqLen);
            return translator.embed(idsTrim, maskTrim);
        } catch (Exception e) {
            throw new RuntimeException("[bge-embedding] embedding failed: " + e.getMessage(), e);
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
        tokenizer = null;
        loaded = false;
    }
}
