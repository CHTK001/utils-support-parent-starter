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
 * all-MiniLM-L6-v2 Sentence Embedding Translator（文本 → 384 维句向量）。
 *
 * <p>底层 ONNX：{@code Xenova/all-MiniLM-L6-v2} 的 {@code model_quantized.onnx}（int8 量化，
 * opset 11，约 23MB）。输入：
 * <ul>
 *   <li>{@code input_ids}    : [batch, seq] int64</li>
 *   <li>{@code attention_mask}: [batch, seq] int64</li>
 *   <li>{@code token_type_ids}: [batch, seq] int64</li>
 * </ul>
 * 输出：{@code last_hidden_state} [batch, seq, 384] float32。
 * </p>
 *
 * <p>本 Translator 把 last_hidden_state 做 mean-pooling（按 attention_mask
 * 取均值）→ L2 归一化 → 384 维 float[]，与 sentence-transformers/all-MiniLM-L6-v2
 * 的默认句向量语义完全一致，可直接用于余弦相似度 / 向量检索。</p>
 *
 * <p>资源在 jar 内路径：{@code nlp/embedding/minilm/} 下，
 * 由 {@link NativeLoader} 解压到 java.io.tmpdir 后加载。
 * 单例模型 + 多线程安全（{@code OrtSession} 本身线程安全，但 batch 维度固定 1）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniLMEmbeddingTranslator {

    /**
     * 嵌入维度（384 维，与 all-MiniLM-L6-v2 一致）
     */
    public static final int HIDDEN_SIZE = 384;

    /**
     * 默认最大序列长度（含 [CLS]/[SEP]）
     */
    public static final int DEFAULT_MAX_LEN = 128;

    private static final String RESOURCE_BASE = "nlp/embedding/minilm/";
    private static final String MODEL_FILE = "model_quantized.onnx";
    private static final String VOCAB_FILE = "vocab.txt";

    private MiniLMTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }

        Path tmpDir = Files.createTempDirectory("minilm-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("minilm");
        Files.createDirectories(modelDir);

        boolean extracted = NativeLoader.of("minilm")
                .from(MiniLMEmbeddingTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();

        if (!extracted) {
            log.warn("[MiniLM] NativeLoader 报告未提取，尝试直接 lookup...");
        }

        Path modelPath = modelDir.resolve(MODEL_FILE);
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
            log.info("[MiniLM] ONNX loaded: model={} vocab_size={} hidden={}",
                    modelPath, tokenizer.vocabSize(), HIDDEN_SIZE);
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
        int[] enc = tokenizer.encodeOne(text, seqLen);

        long[] inputIdsArr = new long[seqLen];
        long[] attMaskArr = new long[seqLen];
        long[] tokenTypeArr = new long[seqLen];
        for (int i = 0; i < seqLen; i++) {
            inputIdsArr[i] = enc[i];
            attMaskArr[i] = enc[i + seqLen];
            tokenTypeArr[i] = enc[i + 2 * seqLen];
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
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                if (hidden == null || hidden.length == 0 || hidden[0].length == 0) {
                    throw new IOException("MiniLM 输出为空");
                }
                return meanPool(hidden[0], attMaskArr, seqLen);
            }
        }
    }

    /**
     * Mean-pooling：对 last_hidden_state 每个非 padding token 取均值，再 L2 归一化。
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
     * 关闭底层 ONNX Session。
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
