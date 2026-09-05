package com.chua.deeplearning.support.onnx.ueba;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * IP 异常流量检测 AutoEncoder Translator。
 *
 * <p>底层模型为通过 Python/PyTorch 训练的 AutoEncoder，导出为 ONNX
 * （opset ≥ 14, fp32, batch=1），输入输出均为单条 IP 聚合特征向量：</p>
 * <ul>
 *   <li>输入：{@code features} [1, inputDim] float32，特征顺序与
 *       {@code ueba-config.yaml} 中 {@code features} 定义严格一致</li>
 *   <li>输出：{@code reconstruction} [1, inputDim] float32，重建后的特征向量</li>
 * </ul>
 * <p>重建误差 = MSE(features, reconstruction)，误差越大代表该 IP 的访问行为越偏离
 * 训练时的正常模式，由 UEBA 引擎据此判定异常等级。</p>
 *
 * <p>模型加载优先级：</p>
 * <ol>
 *   <li>构造参数指定的显式模型路径 {@code explicitPath}</li>
 *   <li>系统属性 {@code ueba.model.dir} 指向的目录下的模型文件</li>
 *   <li>classpath 资源 {@code models/ueba/autoencoder_ip.onnx}</li>
 * </ol>
 * <p>模型缺失时 {@link #isAvailable()} 返回 {@code false}，UEBA 引擎将自动回退到
 * 规则评分，不会中断整体分析流程。{@code OrtSession} 本身线程安全，可多线程并发推理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AutoEncoderIpTranslator {

    /** 系统属性名：UEBA 模型所在目录 */
    public static final String MODEL_DIR_PROPERTY = "ueba.model.dir";

    /** 默认模型文件名 */
    public static final String DEFAULT_MODEL_FILE = "autoencoder_ip.onnx";

    /** classpath 资源基础路径 */
    private static final String RESOURCE_BASE = "models/ueba/";

    /** ONNX Runtime 单次推理使用的最多线程数 */
    private static final int MAX_INTRA_OP_THREADS = 8;

    /** 输入特征维度，由配置的 features 数量决定 */
    private final int inputDim;

    /** 模型文件名 */
    private final String modelFile;

    /** 显式模型路径（可选，优先级最高） */
    private final String explicitPath;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;

    /** 推理会话 */
    private OrtSession session;

    /**
     * 构造 AutoEncoder Translator。
     *
     * @param inputDim 输入特征维度，必须大于 0，与训练模型输入维度一致
     */
    public AutoEncoderIpTranslator(int inputDim) {
        this(inputDim, DEFAULT_MODEL_FILE, null);
    }

    /**
     * 构造 AutoEncoder Translator。
     *
     * @param inputDim  输入特征维度，必须大于 0，与训练模型输入维度一致
     * @param modelFile 模型文件名，不能为 null 或空字符串
     */
    public AutoEncoderIpTranslator(int inputDim, String modelFile) {
        this(inputDim, modelFile, null);
    }

    /**
     * 构造 AutoEncoder Translator。
     *
     * @param inputDim     输入特征维度，必须大于 0，与训练模型输入维度一致
     * @param modelFile    模型文件名，不能为 null 或空字符串
     * @param explicitPath 显式模型文件路径，允许为 null（null 时按目录扫描与 classpath 回退）
     */
    public AutoEncoderIpTranslator(int inputDim, String modelFile, String explicitPath) {
        if (inputDim <= 0) {
            throw new IllegalArgumentException("inputDim 必须大于 0, 实际: " + inputDim);
        }
        if (modelFile == null || modelFile.isBlank()) {
            throw new IllegalArgumentException("modelFile 不能为 null 或空字符串");
        }
        this.inputDim = inputDim;
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
            throw new IOException("AutoEncoder 模型未找到 (" + modelFile
                    + ")。请设置 -D" + MODEL_DIR_PROPERTY + "=<dir> 或打包资源到 " + RESOURCE_BASE);
        }
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(MAX_INTRA_OP_THREADS, Runtime.getRuntime().availableProcessors()));
        session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[UEBA-AutoEncoder] model loaded: {} (inputDim={})", modelPath, inputDim);
    }

    /**
     * 按优先级解析模型文件路径。
     *
     * @return 模型文件路径；未找到时返回 null
     * @throws IOException 当临时目录创建失败或 classpath 资源提取失败时
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
            log.warn("[UEBA-AutoEncoder] classpath 资源提取失败: {}", e.getMessage());
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
            log.debug("[UEBA-AutoEncoder] 模型不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 计算输入特征向量的重建误差。
     *
     * @param features IP 聚合特征向量，长度必须等于 inputDim，顺序与配置定义一致
     * @return 重建误差（MSE），非负，越大代表越异常
     * @throws Exception 当模型不可用、特征维度不匹配或推理失败时
     */
    public double reconstructionError(float[] features) throws Exception {
        prepare();
        if (features == null || features.length != inputDim) {
            throw new IllegalArgumentException("特征维度不匹配: 期望 " + inputDim + ", 实际 "
                    + (features == null ? 0 : features.length));
        }
        long[] shape = new long[]{1L, inputDim};
        String inputName = session.getInputNames().iterator().next();
        try (OnnxTensor input = OnnxTensor.createTensor(ortEnv, features, shape);
             OrtSession.Result result = session.run(Map.of(inputName, input))) {
            float[] reconstructed = readOutput(result, inputDim);
            return mse(features, reconstructed);
        }
    }

    /**
     * 从推理结果中读取重建向量。
     *
     * @param result 推理结果
     * @param dim    期望输出维度
     * @return 重建后的特征向量
     * @throws IOException 当输出类型不支持或维度不匹配时
     */
    private float[] readOutput(OrtSession.Result result, int dim) throws IOException {
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
     * 计算两个等长向量的均方误差。
     *
     * @param x 原始特征向量，长度必须大于 0
     * @param y 重建特征向量，长度必须等于 x 的长度
     * @return 均方误差值，非负
     */
    private static double mse(float[] x, float[] y) {
        double sum = 0.0d;
        for (int i = 0; i < x.length; i++) {
            double diff = x[i] - y[i];
            sum += diff * diff;
        }
        return sum / x.length;
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
            log.debug("[UEBA-AutoEncoder] session 关闭异常忽略");
        }
        session = null;
        ortEnv = null;
    }
}
