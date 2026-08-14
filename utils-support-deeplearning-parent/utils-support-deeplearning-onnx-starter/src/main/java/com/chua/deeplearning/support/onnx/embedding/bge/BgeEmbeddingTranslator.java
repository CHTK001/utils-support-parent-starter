package com.chua.deeplearning.support.onnx.embedding.bge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import com.chua.deeplearning.support.onnx.embedding.minilm.MiniLMTokenizer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * bge-small-zh-v1.5 Sentence Embedding Translator（文本 → 512 维句向量，中文 + 英文双语）。
 *
 * <p>底层 ONNX：{@code BAAI/bge-small-zh-v1.5} 的 {@code model.onnx}（fp16，约 71MB）。
 * 输入：
 * <ul>
 *   <li>{@code input_ids}     : [batch, seq] int64</li>
 *   <li>{@code attention_mask}: [batch, seq] int64</li>
 * </ul>
 * 输出：{@code last_hidden_state} [batch, seq, 512] float32。
 * </p>
 *
 * <p>本 Translator 把 last_hidden_state 做 mean-pooling（按 attention_mask
 * 取均值）→ L2 归一化 → 512 维 float[]，与 sentence-transformers 的默认
 * 句向量语义一致，可直接用于余弦相似度 / 向量检索。中文、英文均可嵌入。</p>
 *
 * <p>资源在 jar 内路径：{@code nlp/embedding/bge-small-zh-v1.5/} 下，
 * 由 {@link NativeLoader} 解压到 java.io.tmpdir 后加载。
 * tokenizer 复用 {@link MiniLMTokenizer}（BGE 与 MiniLM 同为 BERT WordPiece 家族）。
 * 单例模型 + 多线程安全（{@code OrtSession} 本身线程安全，但 batch 维度固定 1）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BgeEmbeddingTranslator {

    /**
     * 嵌入维度（512 维，与 bge-small-zh-v1.5 一致）
     */
    public static final int HIDDEN_SIZE = 512;

    /**
     * 默认最大序列长度（含 [CLS]/[SEP]）
     */
    public static final int DEFAULT_MAX_LEN = 128;

    /**
     * 模型资源目录（jar 内）
     */
    private static final String RESOURCE_BASE = "nlp/embedding/bge-small-zh-v1.5/";

    /**
     * 词表文件名
     */
    private static final String VOCAB_FILE = "vocab.txt";

    private MiniLMTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;

    /**
     * 懒加载模型：通过 ModelRegistry 从 classpath/jar 解析并解压 → 加载 tokenizer → 创建 ORT Session。
     */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }

        OnnxModelRegistrar registrar = new OnnxModelRegistrar();
        registrar.register(null);
        ModelRegistry.discoverAll();
        Path modelPath = ModelRegistry.resolveModelPath("bge-small-zh-embedding");
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IOException("BGE 模型路径无效: " + modelPath);
        }

        // vocab.txt 从 jar classpath 直接读取（ModelRegistry 只解压 .onnx）
        Path vocabPath = extractVocabFromClasspath();
        if (vocabPath == null) {
            throw new IOException("BGE vocab 缺失: " + RESOURCE_BASE + VOCAB_FILE);
        }

        this.tokenizer = MiniLMTokenizer.load(vocabPath);

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            log.info("[BGE] ONNX loaded: model={} vocab_size={} hidden={}",
                    modelPath, tokenizer.vocabSize(), HIDDEN_SIZE);
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for BGE: " + e.getMessage(), e);
        }
    }

    /**
     * 从 jar classpath 提取 vocab.txt 到临时目录。
     *
     * @return vocab.txt 临时路径，提取失败返回 null
     */
    private Path extractVocabFromClasspath() {
        try (java.io.InputStream in = BgeEmbeddingTranslator.class.getClassLoader()
                .getResourceAsStream(RESOURCE_BASE + VOCAB_FILE)) {
            if (in == null) {
                log.warn("[BGE] classpath 未找到 vocab: {}{}", RESOURCE_BASE, VOCAB_FILE);
                return null;
            }
            Path tmp = Files.createTempFile("bge-vocab-", ".txt");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            log.warn("[BGE] 提取 vocab 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算文本的 512 维句向量（已 L2 归一化）。
     *
     * @param text   输入文本
     * @param maxLen 最大序列长度（必须 ≥ 2，包含 [CLS]/[SEP]）
     * @return 长度 512 的 float 数组
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
        for (int i = 0; i < seqLen; i++) {
            inputIdsArr[i] = enc.inputIds[i];
            attMaskArr[i] = enc.attentionMask[i];
        }

        long[] shape = new long[]{1, seqLen};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor inputIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIdsArr), shape);
             OnnxTensor attentionMask = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attMaskArr), shape)) {
            inputs.put("input_ids", inputIds);
            inputs.put("attention_mask", attentionMask);

            try (OrtSession.Result result = session.run(inputs)) {
                Object raw = result.get(0).getValue();
                float[][] seqVec;
                if (raw instanceof float[][][]) {
                    float[][][] hidden = (float[][][]) raw;
                    if (hidden == null || hidden.length == 0 || hidden[0].length == 0) {
                        throw new IOException("BGE 输出为空");
                    }
                    seqVec = hidden[0];
                } else if (raw instanceof float[][]) {
                    seqVec = (float[][]) raw;
                } else {
                    throw new IOException("BGE 输出类型异常: " + raw.getClass());
                }
                return meanPool(seqVec, attMaskArr, seqLen);
            }
        }
    }

    /**
     * Mean-pooling：对 last_hidden_state 每个非 padding token 取均值，再 L2 归一化。
     *
     * @param seqVec       last_hidden_state [seq, 512]
     * @param attentionMask 每 token 的 attention mask
     * @param seqLen        序列长度
     * @return 512 维归一化句向量
     */
    private float[] meanPool(float[][] seqVec, long[] attentionMask, int seqLen) {
        float[] sum = new float[HIDDEN_SIZE];
        int count = 0;
        int actualSeq = Math.min(seqLen, seqVec.length);
        for (int i = 0; i < actualSeq; i++) {
            if (attentionMask[i] == 0L) {
                continue;
            }
            float[] tokenVec = seqVec[i];
            if (tokenVec == null) {
                continue;
            }
            int dim = Math.min(HIDDEN_SIZE, tokenVec.length);
            for (int j = 0; j < dim; j++) {
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