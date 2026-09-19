package com.chua.deeplearning.support.onnx.embedding.minilm;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 全部-minilm-L6-v2 Sentence 嵌入 Translator（文本 → 384 维句向量）。
 *
 * <p>底层 ONNX：{@code Xenova/all-MiniLM-L6-v2}，支持两种精度：
 * <ul>
 *   <li><b>int8 量化版</b>（默认）：{@code model_quantized.onnx}，约 22MB，速度更快</li>
 *   <li><b>fp32 未量化版</b>：{@code model.onnx}，约 90MB，精度更高</li>
 * </ul>
 *
 * <p>输入：
 * <ul>
 *   <li>{@code input_ids}    : [batch, seq] int64</li>
 *   <li>{@code attention_mask}: [batch, seq] int64</li>
 *   <li>{@code token_type_ids}: [batch, seq] int64</li>
 * </ul>
 * 输出：{@code last_hidden_state} [批量, seq, 384] float32。
 * </p>
 *
 * <p>本 Translator 把 last_hidden_state 做 mean-pooling（按 attention_mask
 * 取均值）→ L2 归一化 → 384 维 float[]，与 sentence-transformers/全部-minilm-L6-v2
 * 的默认句向量语义完全一致，可直接用于余弦相似度 / 向量检索。</p>
 *
 * <p>资源在 jar 内路径：{@code nlp/embedding/minilm/}（int8）或
 * {@code nlp/embedding/minilm-fp32/}（fp32），由 {@link NativeLoader} 解压到
 * Java.io.tmpdir 后加载。单例模型 + 多线程安全（{@code OrtSession} 本身线程安全）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniLMEmbeddingTranslator {

    /**
     * 嵌入维度（384 维，与 全部-minilm-L6-v2 一致）
     */
    public static final int HIDDEN_SIZE = 384;

    /**
     * 默认最大序列长度（含 [CLS]/[SEP]）
     */
    public static final int DEFAULT_MAX_LEN = 128;

    /** 资源基础路径 - int8 量化版（默认） */
    private static final String RESOURCE_BASE_INT8 = "nlp/embedding/minilm/";
    /** 资源基础路径 - fp32 未量化版 */
    private static final String RESOURCE_BASE_FP32 = "nlp/embedding/minilm-fp32/";
    /** int8 模型文件路径 */
    private static final String MODEL_FILE_INT8 = "model_quantized.onnx";
    /** fp32 模型文件路径 */
    private static final String MODEL_FILE_FP32 = "model.onnx";
    /** 词表文件路径（两个版本共用） */
    private static final String VOCAB_FILE = "vocab.txt";

    /** 分词器 */
    private MiniLMTokenizer tokenizer;
    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;
    /** 当前使用的资源基础路径 */
    private final String resourceBase;
    /** 当前使用的模型文件名 */
    private final String modelFile;

    /**
     * 创建 int8 量化版 Translator（默认，速度快，~22MB）
     */
    public MiniLMEmbeddingTranslator() {
        this(RESOURCE_BASE_INT8, MODEL_FILE_INT8);
    }

    /**
     * 创建指定精度的 Translator。
     *
     * @param resourceBase 资源基础路径（如 {@code nlp/embedding/minilm/}）
     * @param modelFile    模型文件名（如 {@code model_quantized.onnx}）
     */
    public MiniLMEmbeddingTranslator(String resourceBase, String modelFile) {
        this.resourceBase = resourceBase;
        this.modelFile = modelFile;
    }

    /**
     * 创建 fp32 未量化版 Translator（精度更高，~90MB）
     * @return fp32的结果
     */
    public static MiniLMEmbeddingTranslator fp32() {
        return new MiniLMEmbeddingTranslator(RESOURCE_BASE_FP32, MODEL_FILE_FP32);
    }

    /**
     * 创建 int8 量化版 Translator（默认，速度快，~22MB）
     * @return int8的结果
     */
    public static MiniLMEmbeddingTranslator int8() {
        return new MiniLMEmbeddingTranslator(RESOURCE_BASE_INT8, MODEL_FILE_INT8);
    }

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }

        Path tmpDir = Files.createTempDirectory("minilm-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("minilm");
        Files.createDirectories(modelDir);

        String loaderName = resourceBase.contains("fp32") ? "minilm-fp32" : "minilm-int8";
        NativeLoader.of(loaderName)
                .from(MiniLMEmbeddingTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .cacheable(false)
                .load();

        Path modelPath = modelDir.resolve(modelFile);
        Path vocabPath = modelDir.resolve(VOCAB_FILE);
        if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(vocabPath)) {
            throw new IOException("MiniLM 资源缺失: model=" + modelPath + " vocab=" + vocabPath);
        }

        this.tokenizer = MiniLMTokenizer.load(vocabPath);

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            log.info("[MiniLM] ONNX loaded: model={} vocab_size={} hidden={} type={}",
                    modelPath, tokenizer.vocabSize(), HIDDEN_SIZE,
                    modelFile.contains("quantized") ? "int8" : "fp32");
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for MiniLM: " + e.getMessage(), e);
        }
    }

    /**
    * 计算文本的 384 维句向量（已 L2 归一化）。
    *
    * @param text    输入文本
    * @param maxLen  最大序列长度（必须 ≥ 2，包含 [CLS]/[SEP]）
    * @return 长度 384 的 float 数组
    */
    public float[] embed(String text, int maxLen) throws Exception {
        if (maxLen < 2) {
            throw new IllegalArgumentException("maxLen 必须 >= 2");
        }
        prepare();

        int seqLen = Math.min(maxLen, DEFAULT_MAX_LEN);
        MiniLMTokenizer.EncodeResult enc = tokenizer.encode(text, seqLen);

        long[] inputIdsArr = new long[seqLen];
        long[] attMaskArr = new long[seqLen];
        long[] tokenTypeArr = new long[seqLen];
        for (int i = 0; i < seqLen; i++) {
            inputIdsArr[i] = enc.inputIds[i];
            attMaskArr[i] = enc.attentionMask[i];
            tokenTypeArr[i] = enc.tokenTypeIds[i];
        }

        long[] shape = new long[]{1, seqLen};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor inputIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIdsArr), shape);
             OnnxTensor attentionMask = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attMaskArr), shape);
             OnnxTensor tokenTypeIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokenTypeArr), shape)) {
            inputs.put("input_ids", inputIds);
            inputs.put("attention_mask", attentionMask);
            inputs.put("token_type_ids", tokenTypeIds);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                if (hidden == null || hidden.length == 0 || hidden[0].length == 0) {
                    throw new IOException("MiniLM 输出为空");
                }
                return meanPool(hidden[0], attMaskArr, seqLen);
            }
        }
    }

    /**
     * Mean-游泳池：对 最后一个_hidden_状态 每个非 padding 令牌 取均值，再 L2 归一化。
     * @param seqVec seqvec
     * @param attentionMask attentionmask
     * @param seqLen seqlen
     * @return mean游泳池的结果
     */
    private float[] meanPool(float[][] seqVec, long[] attentionMask, int seqLen) {
        float[] sum = new float[HIDDEN_SIZE];
        int count = 0;
        for (int i = 0; i < seqLen; i++) {
            if (attentionMask[i] == 0L) {
                continue;
            }
            float[] tokenVec = seqVec[i];
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                sum[j] += tokenVec[j];
            }
            count++;
        }
        if (count == 0) {
            return sum;
        }
        float inv = 1.0f / count;
        float norm = 0.0f;
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            sum[j] *= inv;
            norm += sum[j] * sum[j];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0.0f) {
            float invNorm = 1.0f / norm;
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                sum[j] *= invNorm;
            }
        }
        return sum;
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
    }
}
