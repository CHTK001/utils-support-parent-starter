package com.chua.deeplearning.support.onnx.ueba;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
* 用户操作行为序列分析 LSTM/GRU + Attention Translator。
*
* <p>底层模型为通过 Python/PyTorch 训练的序列分类模型（Embedding + GRU/LSTM +
* Attention + Dense），导出为 ONNX（opset ≥ 14, fp32, 批量=1）：</p>
* <ul>
*   <li>输入（按位置约定，顺序不可颠倒）：
*     <ol>
*       <li>{@code sequence_ids}    [1, seqLen] int64，类别特征（如 path_id）编码序列</li>
*       <li>{@code sequence_numeric} [1, seqLen, numNumeric] float32，归一化数值特征序列</li>
*     </ol>
*   </li>
*   <li>输出：{@code logits} [1, numClasses] float32，softmax 后取 argmax 得到类别</li>
* </ul>
*
* <p>模型加载优先级与 {@link AutoEncoderIpTranslator} 一致：显式路径 → 系统属性
* {@code ueba.model.dir} 目录 → classpath 资源 {@code models/ueba/lstm_attention_behavior.onnx}。
* 模型缺失时 {@link #isAvailable()} 返回 {@code false}，UEBA 引擎回退到规则评分。</p>
*
* <p>{@code OrtSession} 本身线程安全，可多线程并发推理。序列不足 seqLen 时由调用方
* 做左端零填充，本类不做内部填充。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class LstmAttentionBehaviorTranslator {

    /** 系统属性名：UEBA 模型所在目录 */
    public static final String MODEL_DIR_PROPERTY = "ueba.model.dir";

    /** 默认模型文件名 */
    public static final String DEFAULT_MODEL_FILE = "lstm_attention_behavior.onnx";

    /** 类路径 资源基础路径 */
    private static final String RESOURCE_BASE = "models/ueba/";

    /** ONNX Runtime 单次推理使用的最多线程数 */
    private static final int MAX_INTRA_OP_THREADS = 8;

    /** 序列长度（时间步数量） */
    private final int seqLen;

    /** 每步数值特征数量 */
    private final int numNumeric;

    /** 类别数量 */
    private final int numClasses;

    /** 模型文件名 */
    private final String modelFile;

    /** 显式模型路径（可选，优先级最高） */
    private final String explicitPath;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;

    /** 推理会话 */
    private OrtSession session;

    /**
    * 行为预测结果。
    *
    * @param classIndex   预测类别下标，范围 [0, num类)
    * @param probabilities softmax 后的各类别概率，长度等于 num类，和为 1
    * @return 预测的结果
     */
    public record Prediction(int classIndex, float[] probabilities) {
    }

    /**
    * 构造序列行为 Translator。
    *
    * @param seqLen     序列长度，必须大于 0，与训练模型输入一致
    * @param numNumeric 每步数值特征数量，必须大于 0，与训练模型输入一致
    * @param numClasses 类别数量，必须大于 0，与训练模型输出一致
     */
    public LstmAttentionBehaviorTranslator(int seqLen, int numNumeric, int numClasses) {
        this(seqLen, numNumeric, numClasses, DEFAULT_MODEL_FILE, null);
    }

    /**
    * 构造序列行为 Translator。
    *
    * @param seqLen       序列长度，必须大于 0，与训练模型输入一致
    * @param numNumeric   每步数值特征数量，必须大于 0，与训练模型输入一致
    * @param numClasses   类别数量，必须大于 0，与训练模型输出一致
    * @param modelFile    模型文件名，不能为 空 或空字符串
    * @param explicitPath 显式模型文件路径，允许为 空
     */
    public LstmAttentionBehaviorTranslator(int seqLen, int numNumeric, int numClasses,
                                           String modelFile, String explicitPath) {
        if (seqLen <= 0) {
            throw new IllegalArgumentException("seqLen 必须大于 0, 实际: " + seqLen);
        }
        if (numNumeric <= 0) {
            throw new IllegalArgumentException("numNumeric 必须大于 0, 实际: " + numNumeric);
        }
        if (numClasses <= 0) {
            throw new IllegalArgumentException("numClasses 必须大于 0, 实际: " + numClasses);
        }
        if (modelFile == null || modelFile.isBlank()) {
            throw new IllegalArgumentException("modelFile 不能为 null 或空字符串");
        }
        this.seqLen = seqLen;
        this.numNumeric = numNumeric;
        this.numClasses = numClasses;
        this.modelFile = modelFile;
        this.explicitPath = explicitPath;
    }

    /**
    * 初始化并加载 ONNX 模型，线程安全且只加载一次。
    *
    * @throws IOException 当模型文件不存在或创建 ONNX Runtime 会话失败时
     */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path modelPath = resolveModelPath();
        if (modelPath == null) {
            throw new IOException("LSTM/GRU 模型未找到 (" + modelFile
                    + ")。请设置 -D" + MODEL_DIR_PROPERTY + "=<dir> 或打包资源到 " + RESOURCE_BASE);
        }
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(MAX_INTRA_OP_THREADS, Runtime.getRuntime().availableProcessors()));
        session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[UEBA-LstmAttention] model loaded: {} (seqLen={}, numNumeric={}, numClasses={})",
                modelPath, seqLen, numNumeric, numClasses);
    }

    /**
    * 按优先级解析模型文件路径。
    *
    * @return 模型文件路径；未找到时返回 空
    * @throws IOException 当临时目录创建失败或 类路径 资源提取失败时
     */
    private Path resolveModelPath() throws IOException {
        if (explicitPath != null) {
            Path p = Paths.get(explicitPath);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String dir = System.getProperty(MODEL_DIR_PROPERTY);
        if (dir != null && !dir.isBlank()) {
            Path p = Paths.get(dir).resolve(modelFile);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        Path targetDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "ueba-onnx-" + Integer.toHexString(System.identityHashCode(this)));
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(modelFile);
        if (Files.isRegularFile(target)) {
            return target;
        }
        boolean onClasspath = getClass().getClassLoader().getResource(RESOURCE_BASE + modelFile) != null;
        if (!onClasspath) {
            return null;
        }
        try {
            NativeLoader.of("ueba-" + modelFile)
                    .from(getClass().getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(targetDir)
                    .glob(modelFile)
                    .withMd5(true)
                    .extractOnly(true)
                    .cacheable(true)
                    .load();
        } catch (Exception e) {
            log.warn("[UEBA-LstmAttention] classpath 资源提取失败: {}", e.getMessage());
            return null;
        }
        return Files.isRegularFile(target) ? target : null;
    }

    /**
    * 判断模型是否可用（可加载、可推理）。
    *
    * @return true 表示模型已就绪
     */
    public boolean isAvailable() {
        try {
            prepare();
            return session != null;
        } catch (Exception e) {
            log.debug("[UEBA-LstmAttention] 模型不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
    * 对行为序列进行预测。
    *
    * @param sequenceIds     类别特征编码序列，长度必须等于 seqlen（不足时左端零填充由调用方完成）
    * @param sequenceNumeric 数值特征序列，长度必须等于 seqlen，每个元素长度必须等于 numnumeric
    * @return 预测结果，包含类别下标与各类别概率
    * @throws Exception 当模型不可用、输入维度不匹配或推理失败时
     */
    public Prediction predict(int[] sequenceIds, float[][] sequenceNumeric) throws Exception {
        prepare();
        if (sequenceIds == null || sequenceIds.length != seqLen) {
            throw new IllegalArgumentException("sequenceIds 长度不匹配: 期望 " + seqLen + ", 实际 "
                    + (sequenceIds == null ? 0 : sequenceIds.length));
        }
        if (sequenceNumeric == null || sequenceNumeric.length != seqLen) {
            throw new IllegalArgumentException("sequenceNumeric 长度不匹配: 期望 " + seqLen + ", 实际 "
                    + (sequenceNumeric == null ? 0 : sequenceNumeric.length));
        }
        long[] ids = new long[seqLen];
        float[] numeric = new float[seqLen * numNumeric];
        for (int i = 0; i < seqLen; i++) {
            ids[i] = sequenceIds[i];
            float[] step = sequenceNumeric[i];
            if (step == null || step.length != numNumeric) {
                throw new IllegalArgumentException("sequenceNumeric[" + i + "] 长度不匹配: 期望 "
                        + numNumeric + ", 实际 " + (step == null ? 0 : step.length));
            }
            System.arraycopy(step, 0, numeric, i * numNumeric, numNumeric);
        }
        long[] seqShape = new long[]{1L, seqLen};
        long[] numShape = new long[]{1L, seqLen, numNumeric};
        Map<String, OnnxTensor> inputs = new HashMap<>(4);
        String[] inputNames = session.getInputInfo().keySet().toArray(new String[0]);
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), seqShape);
             OnnxTensor numericTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(numeric), numShape)) {
            putInputs(inputs, inputNames, idsTensor, numericTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[] logits = readLogits(result, numClasses);
                float[] probabilities = softmax(logits);
                int argmax = argmax(probabilities);
                return new Prediction(argmax, probabilities);
            }
        }
    }

    /**
    * 将输入张量按模型输入名装配，兼容单输入（仅 标识 序列）与双输入（标识 + 数值）两种导出。
    *
    * @param inputs        输入张量 映射，会被写入
    * @param inputNames    模型输入名列表，顺序与导出一致
    * @param idsTensor     标识 序列张量
    * @param numericTensor 数值序列张量
     */
    private void putInputs(Map<String, OnnxTensor> inputs, String[] inputNames,
                           OnnxTensor idsTensor, OnnxTensor numericTensor) {
        if (inputNames.length <= 1) {
            inputs.put(inputNames[0], idsTensor);
            return;
        }
        String idsName = null;
        String numericName = null;
        for (String name : inputNames) {
            String lower = name.toLowerCase();
            if (lower.contains("id") || lower.contains("token") || lower.contains("seq")) {
                idsName = name;
            } else {
                numericName = name;
            }
        }
        if (idsName == null) {
            idsName = inputNames[0];
        }
        if (numericName == null) {
            numericName = inputNames[inputNames.length - 1];
        }
        inputs.put(idsName, idsTensor);
        inputs.put(numericName, numericTensor);
    }

    /**
    * 从推理结果中读取 logits 向量。
    *
    * @param result 推理结果
    * @param dim    类别数量
    * @return logits 向量
    * @throws Exception 当输出读取失败、类型不支持或维度不匹配时
     */
    private float[] readLogits(OrtSession.Result result, int dim) throws Exception {
        Object value = result.get(0).getValue();
        if (value instanceof float[][] matrix) {
            float[] vector = matrix[0];
            if (vector.length != dim) {
                throw new IOException("输出维度不匹配: " + vector.length + " != " + dim);
            }
            return vector;
        }
        if (value instanceof float[] vector) {
            if (vector.length != dim) {
                throw new IOException("输出维度不匹配: " + vector.length + " != " + dim);
            }
            return vector;
        }
        throw new IOException("不支持的输出类型: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
    * 计算 softmax 概率分布。
    *
    * @param logits 原始 logits 向量，长度必须大于 0
    * @return 归一化概率向量，长度与 logits 一致，元素和约为 1
     */
    private static float[] softmax(float[] logits) {
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > max) {
                max = logits[i];
            }
        }
        float sum = 0.0f;
        float[] exp = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = (float) Math.exp(logits[i] - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] = exp[i] / sum;
        }
        return exp;
    }

    /**
    * 计算概率向量的最大下标。
    *
    * @param probabilities 概率向量，长度必须大于 0
    * @return 最大概率对应的下标
     */
    private static int argmax(float[] probabilities) {
        int maxIdx = 0;
        float maxVal = probabilities[0];
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > maxVal) {
                maxVal = probabilities[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /**
    * 释放底层 ONNX Runtime 会话与环境。
     */
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
            log.debug("[UEBA-LstmAttention] session 关闭异常忽略");
        }
        session = null;
        ortEnv = null;
    }
}
