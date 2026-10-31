package com.chua.deeplearning.support.onnx.embedding.embeddinggemma;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * embeddinggemma-2 文本只（text-only）文本嵌入 Translator（String → float[768]）。
 *
 * <p>模型来自 onnx-community/embeddinggemma-2-ONNX（Apache 2.0），270M 文本分支（q4 量化），
 * 文本分支接收三个系列张量：</p>
 * <ul>
 *   <li>{@code input_ids}（int64，形状 [1, seq]）—— 分词器输出的标记</li>
 *   <li>{@code attention_mask}（int64，形状 [1, seq]）—— 必须与 {@code input_ids} 完全同长度，
 *       否会触发 attention bias 的 BroadcastIterator InvalidArgument</li>
 *   <li>{@code image_features} / {@code video_features} / {@code audio_features}（float32）
 *       占位张量，rank-2 形状 [0, 512] 零张量；rank-3（[{1, 0, 512]}) 不 valid 会报 InvalidArgument</li>
 * </ul>
 *
 * <p>输出为 {@code sentence_embedding}（float32 [1, 768]），已 L2 归一化（norm ≈ 1.0000006），
 * 无需再归一化即可直用于余弦/向量检索。序列长度常温任意（1/6/512/8192/9000+ 均通过验证），
 * 只要求 {@code attention_mask} 与 {@code input_ids} 的 shape 精确一致。</p>
 *
 * <p>资源加载：<br>
 * <ul>
 *   <li>模型 + tokenizer.json 由模型注册表（ModelRegistry）注入绝对路径（下载缓存或外部目录）</li>
 *   <li>由 {@link #setModelPath(String)} 反射注入（支持模型文件或模型目录）</li>
 * </ul></p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class EmbeddingGemmaTranslator implements ITranslator<String, float[]>, AutoCloseable {

    /**
     * 模型标识
     */
    public static final String MODEL_ID = "embeddinggemma-2-embedding";

    /**
     * 输出维度（768 维，L2 归一化句向量）
     */
    public static final int DIM = 768;

    /**
     * 分词器最大序列长度（Tokenizer 截断上限）
     */
    private static final int DEFAULT_MAX_LEN = 8192;

    /**
     * 三模态占位张量的嵌入维度
     */
    private static final int FEATURE_DIM = 512;

    /**
     * ONNX 模型文件名
     */
    private static final String MODEL_FILE = "model_q4.onnx";

    /**
     * 分词器文件
     */
    private static final String TOKENIZER_FILE = "tokenizer.json";

    /**
     * 获取三模态占位张量buffer（float32，rank-2，[0, 512]）：
     * 网络输入用零张量，0 元素 buffer 即可填充 rank-2 tensor
     * （shape [0, 512] 不传任何元素值，空 buffer 与 ONNX Runtime
     * 尺寸检查兼容，采用了填充 fifo 的非空 0 元素 buffer 会报 InvalidArgument）。
     *
     * @return 零长度 FloatBuffer
     */
    private static FloatBuffer emptyFeatureBuffer() {
        return FloatBuffer.allocate(0);
    }

    /**
     * 模型标识 / 翻译器名称
     */
    private final String name;

    /**
     * 本地模型目录（下载 url 缓存 / 外置目录或绝对路径目录）
     */
    private volatile Path localModelDir;

    /**
     * 注入的模型路径（可能是 model_q4.onnx 文件，也可能是包含 .onnx 的目录）
     */
    private volatile String modelFileOrDir;

    /**
     * ONNX 运行时环境
     */
    private OrtEnvironment ortEnv;

    /**
     * ONNX 会话
     */
    private OrtSession session;

    /**
     * HuggingFace 分词器
     */
    private HuggingFaceTokenizer tokenizer;

    /**
     * 是否已加载
     */
    private volatile boolean loaded;

    /**
     * 创建 embeddinggemma-2 文本嵌入 Translator（模型标识 {@code embeddinggemma-2-embedding}）
     */
    public EmbeddingGemmaTranslator() {
        this(MODEL_ID);
    }

    /**
     * 创建指定标识的 embeddinggemma-2 文本嵌入 Translator。
     *
     * @param name 模型标识
     */
    public EmbeddingGemmaTranslator(String name) {
        this.name = name;
    }

    /**
     * 创建 embeddinggemma-2 文本嵌入 Translator 实例。
     *
     * @return translator 实例
     */
    public static EmbeddingGemmaTranslator create() {
        return new EmbeddingGemmaTranslator();
    }

    /**
     * 设置本地模型目录（downloadurl 缓存由 模型registry 注入）。
     *
     * @param dir 包含 model_q4.onnx + model_q4.onnx_data + tokenizer.json 的目录
     */
    public void setModelPath(Path dir) {
        this.localModelDir = dir;
        this.loaded = false;
    }

    /**
     * 设置本地模型路径（字符串形式，由 模型registry 反射注入）。
     *
     * @param path 模型文件（model_q4.onnx）或包含模型文件的目录路径
     */
    public void setModelPath(String path) {
        this.modelFileOrDir = path;
        this.localModelDir = null;
        this.loaded = false;
    }

    /**
     * Prepare
     * @return 返回值
     * @throws Exception 异常
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path modelDir = resolveModelDir();
        if (modelDir == null || !Files.isRegularFile(modelDir.resolve(MODEL_FILE))) {
            throw new IllegalStateException(
                    name + " 模型资源未就绪（缺少 " + MODEL_FILE + " + tokenizer.json）： " + modelDir);
        }
        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelDir.resolve(MODEL_FILE).toString(), opts);
            log.info("[{}] ONNX loaded: {}", name, modelDir.resolve(MODEL_FILE));

            Path tkPath = modelDir.resolve(TOKENIZER_FILE);
            if (Files.isRegularFile(tkPath)) {
                this.tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tkPath)
                        .optMaxLength(DEFAULT_MAX_LEN)
                        .optTruncation(true)
                        .optPadding(false)
                        .build();
            }
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for " + name + ": " + e.getMessage(), e);
        }
        loaded = true;
    }

    /**
     * 解析模型目录：优先注入路径（文件取父目录，目录取自身），再解析本地目录，最后回注册表兜底。
     *
     * @return 模型目录
     */
    private Path resolveModelDir() {
        if (modelFileOrDir != null && !modelFileOrDir.isBlank()) {
            Path p = Path.of(modelFileOrDir);
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
            if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".onnx")) {
                Path parent = p.getParent();
                if (parent != null && Files.isRegularFile(parent.resolve(MODEL_FILE))) {
                    return parent.toAbsolutePath().normalize();
                }
            }
        }
        if (localModelDir != null && Files.isRegularFile(localModelDir.resolve(MODEL_FILE))) {
            return localModelDir.toAbsolutePath().normalize();
        }
        return null;
    }

    /**
     * 名称
     * @return 模型标识
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Translate
     * @param input 文本
     * @return float[] 句向量（768 维）
     */
    @Override
    public float[] translate(String input) {
        if (input == null) {
            throw new IllegalArgumentException(input + " must not be null");
        }
        try {
            prepare();
            if (tokenizer == null) {
                throw new IllegalStateException(name + " 分词器未就绪");
            }
            return embed(input);
        } catch (Exception e) {
            throw new RuntimeException("[" + name + "] embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文本的嵌入向量。
     *
     * <p>{@code sentence_embedding} 已是 L2 归一化的句向量，直接返回；
     * 不保留 [1, 0, 512] rank-3 的中间张量。</p>
     *
     * @param text 文本
     * @return float[768] 归一化句向量
     * @throws Exception 异常
     */
    protected float[] embed(String text) throws Exception {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        prepare();

        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        // 容错：mask 必须与 ids 同长度（tokenizer padding 不一致时补全同长，全 1）
        if (mask.length != ids.length) {
            mask = new long[ids.length];
            Arrays.fill(mask, 1);
        }
        int seqLen = Math.min(ids.length, DEFAULT_MAX_LEN);
        long[] idsTrim = new long[seqLen];
        long[] maskTrim = new long[seqLen];
        System.arraycopy(ids, 0, idsTrim, 0, seqLen);
        System.arraycopy(mask, 0, maskTrim, 0, seqLen);
        long[] shape = new long[]{1, seqLen};

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(idsTrim), shape);
             OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(maskTrim), shape);
             OnnxTensor imageFeatureTensor = OnnxTensor.createTensor(ortEnv, emptyFeatureBuffer(),
                     new long[]{0, FEATURE_DIM});
             OnnxTensor videoFeatureTensor = OnnxTensor.createTensor(ortEnv, emptyFeatureBuffer(),
                     new long[]{0, FEATURE_DIM});
             OnnxTensor audioFeatureTensor = OnnxTensor.createTensor(ortEnv, emptyFeatureBuffer(),
                     new long[]{0, FEATURE_DIM});) {

            Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            inputs.put("image_features", imageFeatureTensor);
            inputs.put("video_features", videoFeatureTensor);
            inputs.put("audio_features", audioFeatureTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : result) {
                    String outputName = entry.getKey();
                    if (!"sentence_embedding".equals(outputName)) {
                        continue;
                    }
                    Object value = entry.getValue().getValue();
                    if (value instanceof float[][] pooled) {
                        return pooled[0];
                    }
                    if (value instanceof float[][][] pooled3) {
                        return pooled3[0][0];
                    }
                    throw new IOException(name + " 输出格式不识别: " + value.getClass());
                }
                throw new IOException(name + " 输出张量 sentence_embedding 缺失");
            }
        }
    }

    /**
     * 关闭底层 ONNX 会话与分词器资源。
     */
    @Override
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception e) {
            log.debug("[{}] 关闭模型会话失败: {}", name, e.getMessage());
        }
        session = null;
        ortEnv = null;
        tokenizer = null;
        loaded = false;
    }
}
