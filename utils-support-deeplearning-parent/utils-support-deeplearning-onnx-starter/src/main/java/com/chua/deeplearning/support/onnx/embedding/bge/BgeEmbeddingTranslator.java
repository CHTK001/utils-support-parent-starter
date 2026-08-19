package com.chua.deeplearning.support.onnx.embedding.bge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
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
 * BGE 系列文本嵌入 Translator（BGE-small / BGE-M3 通用）。
 *
 * <p>BGE（BAAI General Embedding）是中文语义向量模型，输入
 * {@code input_ids + attention_mask}（int64），输出 {@code embedding}（已池化句向量）。
 * 与 sentence-transformers 语义一致，可直接用于余弦相似度 / 向量检索。</p>
 *
 * <p>资源加载：若模型在 jar 内（如 bge-small-zh），由 {@link NativeLoader} 解压到临时目录；
 * 若为自动下载模型（bge-m3），模型路径由调用方传入（registry 下载后本地路径）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BgeEmbeddingTranslator {

    /**
     * 默认最大序列长度（含 [CLS]/[SEP]）
     */
    public static final int DEFAULT_MAX_LEN = 512;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;

    /**
     * 加载 jar 内打包的 BGE 模型。
     *
     * @param basePath   jar 内资源目录（如 nlp/embedding/bge-small-zh-v1.5/）
     * @param modelFile  模型文件名
     * @param tokenizerFile tokenizer 文件名
     * @return tokenizer（HuggingFace tokenizer 由 DJL 加载）
     * @throws Exception 加载异常
     */
    public synchronized Path extractFromClasspath(String basePath, String modelFile, String tokenizerFile) throws Exception {
        if (session != null) {
            return null;
        }
        Path tmpDir = Files.createTempDirectory("bge-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("bge");
        Files.createDirectories(modelDir);

        NativeLoader.of("bge")
                .from(BgeEmbeddingTranslator.class.getClassLoader())
                .basePath(basePath)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();

        Path modelPath = modelDir.resolve(modelFile);
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("BGE 模型缺失: " + modelPath);
        }
        createSession(modelPath.toString());
        return modelDir;
    }

    /**
     * 从本地路径加载 BGE 模型（自动下载模型用）。
     *
     * @param modelPath 本地模型文件路径
     * @throws Exception 加载异常
     */
    public synchronized void loadLocal(String modelPath) throws Exception {
        if (session != null) {
            return;
        }
        Path path = Path.of(modelPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("BGE 模型缺失: " + modelPath);
        }
        createSession(modelPath);
    }

    private void createSession(String modelPath) throws IOException {
        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath, opts);
            log.info("[BGE] ONNX loaded: {}", modelPath);
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for BGE: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文本句向量（已池化）。
     *
     * @param inputIds      token IDs
     * @param attentionMask attention mask
     * @return 句向量 float[]
     */
    public float[] embed(long[] inputIds, long[] attentionMask) throws Exception {
        if (session == null) {
            throw new IllegalStateException("BGE 模型未初始化");
        }
        int seqLen = inputIds.length;
        long[] shape = new long[]{1, seqLen};
        long[] typeIds = new long[seqLen];
        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), shape);
             OnnxTensor attMaskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), shape);
             OnnxTensor typeIdsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(typeIds), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attMaskTensor);
            inputs.put("token_type_ids", typeIdsTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                // Try sentence_embedding output first, fallback to last_hidden_state with CLS pooling
                float[][] pooled = null;
                float[][][] hidden = null;
                for (Map.Entry<String, OnnxValue> entry : result) {
                    String name = entry.getKey();
                    if ("sentence_embedding".equals(name)) {
                        pooled = (float[][]) entry.getValue().getValue();
                        break;
                    }
                    Object val = entry.getValue().getValue();
                    if (val instanceof float[][]) {
                        pooled = (float[][]) val;
                    } else if (val instanceof float[][][]) {
                        hidden = (float[][][]) val;
                    }
                }
                if (pooled != null) {
                    return pooled[0];
                }
                if (hidden != null) {
                    return hidden[0][0]; // CLS token
                }
                throw new IOException("BGE 输出格式不识别");
            }
        }
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
    }
}
