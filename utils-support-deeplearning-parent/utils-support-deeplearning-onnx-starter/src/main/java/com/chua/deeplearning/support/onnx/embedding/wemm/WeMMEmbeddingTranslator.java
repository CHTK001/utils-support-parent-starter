package com.chua.deeplearning.support.onnx.embedding.wemm;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * wemm-嵌入 多模态文本嵌入 Translator（文本 → Matryoshka 嵌入向量）。
 *
 * <p>WeMM-Embedding 是腾讯微信视觉团队开发的多模态嵌入模型系列，
 * 支持 2B / 4B / 9B 三种规格。文本分支仅接收 {@code input_ids}（int64），
 * 输出 {@code sentence_embedding}（已池化、L2 归一化的固定维度向量）。</p>
 *
 * <p>与 BGE 的区别：
 * <ul>
 *   <li>使用 Qwen3 分词器（非 BERT），输入仅 {@code input_ids} 单一张量</li>
 *   <li>输出为已池化句向量，无需 CLS/mean-pooling</li>
 *   <li>支持 Matryoshka 截断：取前 {@code d} 维后重新 L2 归一化</li>
 * </ul>
 * </p>
 *
 * <p>资源加载：模型 + tokenizer.json 由 models jar
 * （utils-support-onnx-wemm）提供，
 * 由 {@link NativeLoader} 解压到临时目录后加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WeMMEmbeddingTranslator implements ITranslator<String, float[]> {

    /** 默认最大序列长度 */
    private static final int DEFAULT_MAX_LEN = 512;

    /** 翻译器名称 */
    private final String name;
    /** 资源基础路径（jar 内） */
    private final String resourceBase;
    /** ONNX 模型文件名 */
    private final String modelFile;
    /** 分词器文件名 */
    private final String tokenizerFile;
    /** 默认输出维度 */
    private final int defaultDim;
    /** 本地模型目录（downloadurl 缓存注入） */
    private volatile Path localModelDir;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;
    /** 分词器 */
    private HuggingFaceTokenizer tokenizer;
    /** 是否已加载 */
    private volatile boolean loaded;

    /**
     * 创建 wemm-嵌入-2B 文本嵌入 Translator（默认 2048 维）。
     */
    public WeMMEmbeddingTranslator() {
        this("wemm-embedding-2b", "nlp/embedding/wemm-embedding-2b/", "model.onnx", "tokenizer.json", 2048);
    }

    /**
     * 创建指定规格的 wemm-嵌入 Translator。
     *
     * @param name         模型标识
     * @param resourceBase jar 内资源目录
     * @param modelFile    模型文件名
     * @param tokenizerFile 分词器文件名
     * @param defaultDim   默认输出维度
     */
    public WeMMEmbeddingTranslator(String name, String resourceBase,
                                    String modelFile, String tokenizerFile, int defaultDim) {
        this.name = name;
        this.resourceBase = resourceBase;
        this.modelFile = modelFile;
        this.tokenizerFile = tokenizerFile;
        this.defaultDim = defaultDim;
    }

    /**
     * 创建 wemm-嵌入-4B 文本嵌入 Translator（默认 2560 维）。
     * @return embedding4b的结果
     */
    public static WeMMEmbeddingTranslator embedding4b() {
        return new WeMMEmbeddingTranslator(
                "wemm-embedding-4b", "nlp/embedding/wemm-embedding-4b/", "model.onnx", "tokenizer.json", 2560);
    }

    /**
     * 创建 wemm-嵌入-9B 文本嵌入 Translator（默认 4096 维）。
     * @return embedding9b的结果
     */
    public static WeMMEmbeddingTranslator embedding9b() {
        return new WeMMEmbeddingTranslator(
                "wemm-embedding-9b", "nlp/embedding/wemm-embedding-9b/", "model.onnx", "tokenizer.json", 4096);
    }

    /**
     * 设置本地模型目录（downloadurl 缓存由 模型registry 注入）。
     *
     * @param dir 包含 模型.onnx + 模型.onnx_数据 + tokenizer.json 的目录
     */
    public void setModelPath(Path dir) {
        this.localModelDir = dir;
        this.loaded = false;
    }

    /**
     * 设置本地模型路径（字符串形式，由 模型registry 反射注入）。
     *
     * @param path 模型文件或目录路径
     */
    public void setModelPath(String path) {
        this.localModelDir = Path.of(path);
        this.loaded = false;
    }

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }

 // 1. 优先使用本地模型目录（downloadurl 缓存）
        Path modelDir;
        if (localModelDir != null && Files.isDirectory(localModelDir)) {
            modelDir = localModelDir;
        } else {
            // 2. jar 内嵌资源
            modelDir = Files.createTempDirectory("wemm-onnx-");
            modelDir.toFile().deleteOnExit();
            modelDir = modelDir.resolve(name);
            Files.createDirectories(modelDir);
            NativeLoader.of("wemm-" + name)
                    .from(WeMMEmbeddingTranslator.class.getClassLoader())
                    .basePath(resourceBase)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }

        Path modelPath = modelDir.resolve(modelFile);
        Path tkPath = modelDir.resolve(tokenizerFile);
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException(name + " 模型缺失: " + modelPath);
        }
        if (!Files.isRegularFile(tkPath)) {
            log.warn("[{}] tokenizer 文件缺失: {}，将使用模型目录中的 tokenizer.json（若存在）", name, tkPath);
        }

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            log.info("[{}] ONNX loaded: {}", name, modelPath);

            if (Files.isRegularFile(tkPath)) {
                this.tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tkPath)
                        .optPadding(true)
                        .optMaxLength(DEFAULT_MAX_LEN)
                        .build();
            }
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for " + name + ": " + e.getMessage(), e);
        }
        loaded = true;
    }

    /**
     * 计算文本的嵌入向量（Matryoshka 截断至指定维度，L2 归一化）。
     *
     * @param text 输入文本
     * @return 嵌入向量 float[]
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Translate
     * @param input 输入
     * @return float[]
     */
    @Override
    public float[] translate(String input) {
        try {
            prepare();
            if (tokenizer == null) {
                throw new IllegalStateException(name + " 分词器未就绪");
            }
            return embed(input, defaultDim);
        } catch (Exception e) {
            throw new RuntimeException("[" + name + "] embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文本嵌入向量，并 Matryoshka 截断至指定维度。
     *
     * @param text  输入文本
     * @param dim   目标维度（必须 ≤ 模型原始维度）
     * @return 截断并 L2 归一化后的 float[dim]
     */
    public float[] embed(String text, int dim) throws Exception {
        if (dim <= 0 || dim > defaultDim) {
            throw new IllegalArgumentException("dim must be in (0, " + defaultDim + "], got " + dim);
        }
        prepare();
        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        int seqLen = Math.min(ids.length, DEFAULT_MAX_LEN);
        long[] idsTrim = new long[seqLen];
        System.arraycopy(ids, 0, idsTrim, 0, seqLen);

        long[] shape = new long[]{1, seqLen};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        float[] fullVec;
        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(idsTrim), shape)) {
            inputs.put("input_ids", inputIdsTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                Object val = result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                if (val instanceof float[][]) {
                    fullVec = ((float[][]) val)[0];
                } else if (val instanceof float[][][]) {
 // [批量, seq, dim] → 取 最后一个 hidden 状态
                    float[][][] hidden = (float[][][]) val;
                    fullVec = hidden[0][hidden[0].length - 1];
                } else {
                    throw new IOException(name + " 输出格式不识别: " + val.getClass());
                }
            }
        }

        if (dim >= fullVec.length) {
            return fullVec;
        }
        // Matryoshka 截断：取前 dim 维后重新 L2 归一化
        float[] truncated = new float[dim];
        System.arraycopy(fullVec, 0, truncated, 0, dim);
        float norm = 0f;
        for (int i = 0; i < dim; i++) {
            norm += truncated[i] * truncated[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            float inv = 1f / norm;
            for (int i = 0; i < dim; i++) {
                truncated[i] *= inv;
            }
        }
        return truncated;
    }

    /**
     * 关闭底层 ONNX 会话。
     */
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        ortEnv = null;
        tokenizer = null;
        loaded = false;
    }
}
