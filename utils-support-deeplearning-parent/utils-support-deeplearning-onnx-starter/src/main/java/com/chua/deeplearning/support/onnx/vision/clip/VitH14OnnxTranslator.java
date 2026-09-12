package com.chua.deeplearning.support.onnx.vision.clip;

import com.chua.common.support.utils.MathUtils;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import com.chua.common.support.utils.NativeLoader;

import ai.djl.modality.cv.Image;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
   * vit-H-14 ONNX 图像特征提取 Translator。
 *
 * <p>基于 Chinese-CLIP ViT-H-14 模型：输入图像，输出 1024 维图像特征向量。
   * 支持动态 批量 大小，适用于图像检索、图像匹配等场景。</p>
 *
 * <p>流程：图像 → resize(224x224) → normalize(CLIP) → CHW → ONNX 推理 → 特征向量。</p>
 *
 * <p>模型输入：pixel_values [batch, 3, 224, 224] float32</p>
 * <p>模型输出：image_features [batch, 1024] float32</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VitH14OnnxTranslator implements ITranslator<Image, float[]>, AutoCloseable {

    /** 模型名称 */
    private static final String NAME = "vit-h-14";

    /** 输入图像尺寸（vit-H 224） */
    private static final int IMAGE_SIZE = 224;

    /** CLIP 图像均值 */
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};

    /** CLIP 图像标准差 */
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};

    /** 输出特征维度 */
    private static final int FEATURE_DIM = 1024;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;

    /** ONNX 会话 */
    private OrtSession session;

    /** 是否已准备 */
    private volatile boolean prepared;

    /** 模型文件路径（可由外部指定） */
    private Path modelPath;

    /**
     * 构造图像特征提取器
     */
    public VitH14OnnxTranslator() {
    }

    /**
     * 构造图像特征提取器（指定模型路径）
     *
     * @param modelPath 模型路径
     */
    public VitH14OnnxTranslator(Path modelPath) {
        this.modelPath = modelPath;
    }

    /**
     * 准备模型（懒加载）
     *
     * @throws Exception 准备异常
     */
    private synchronized void prepare() throws Exception {
        if (prepared) {
            return;
        }

 // 加载 打开cv 原生库
        ImageUtils.load();

        // 解析模型路径
        Path resolvedPath = resolveModelPath();
        if (!Files.exists(resolvedPath)) {
            throw new IllegalStateException("ViT-H-14 ONNX 模型文件不存在: " + resolvedPath);
        }

        // 创建 ORT 环境和会话
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = ortEnv.createSession(resolvedPath.toString(), opts);

        log.info("[ViT-H-14] 模型加载完成: {}", resolvedPath);
        prepared = true;
    }

    /**
     * 解析模型路径
     *
     * @return 模型文件路径
     */
    private Path resolveModelPath() {
        // 1. 优先使用外部指定路径
        if (modelPath != null && Files.exists(modelPath)) {
            return modelPath;
        }

        // 2. 尝试从缓存目录获取
        String cacheRoot = cacheRoot();
        Path cached = Path.of(cacheRoot, "vision/clip/vit-h-14/vit-h-14.onnx");
        if (Files.exists(cached)) {
            return cached;
        }

 // 3. 尝试从 NAT加载 解压资源
        try {
            NativeLoader.of("vit-h-14-resources")
                    .from(VitH14OnnxTranslator.class.getClassLoader())
                    .basePath("vision/clip/vit-h-14/")
                    .toTarget(Path.of(cacheRoot))
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            if (Files.exists(cached)) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("[ViT-H-14] NativeLoader 解压失败: {}", e.getMessage());
        }

        // 4. 返回默认路径（可能不存在）
        return cached;
    }

    /**
     * 模型缓存根目录
     *
     * @return 缓存根目录
     */
    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    /**
      * 提取图像特征（itranslator 接口实现）
     *
     * @param input DJL 镜像 图像
     * @return 1024 维特征向量
     */
    @Override
    public float[] translate(Image input) {
        try {
            if (input == null) {
                throw new IllegalArgumentException("图像为空");
            }
            prepare();
            float[][] result = infer(new Image[]{input});
            return result[0];
        } catch (Exception e) {
            throw new RuntimeException("ViT-H-14 特征提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取单张图像特征
     *
     * @param image DJL 镜像 图像
     * @return 1024 维特征向量
     */
    public float[] extractFeature(Image image) {
        return translate(image);
    }

    /**
     * 批量提取图像特征
     *
     * @param images DJL 镜像 图像数组
     * @return 特征向量数组，每个元素为 1024 维特征
     */
    public float[][] extractFeatures(Image[] images) {
        try {
            if (images == null || images.length == 0) {
                throw new IllegalArgumentException("图像数据列表为空");
            }
            prepare();
            return infer(images);
        } catch (Exception e) {
            throw new RuntimeException("ViT-H-14 批量特征提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * ONNX 推理
     *
     * @param images DJL 镜像 图像数组
     * @return 特征向量数组
     * @throws Exception 推理异常
     */
    private float[][] infer(Image[] images) throws Exception {
        int batch = images.length;
        float[] pixels = preprocessBatch(images);
        long[] shape = {batch, 3, IMAGE_SIZE, IMAGE_SIZE};

        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
            java.util.Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put("pixel_values", tensor);

            try (OrtSession.Result result = session.run(inputs)) {
                // 动态获取输出名称
                String outputName = session.getOutputNames().iterator().next();
                OnnxTensor outputTensor = (OnnxTensor) result.get(outputName).get();
                float[] flat = outputTensor.getFloatBuffer().array();
                float[][] features = new float[batch][FEATURE_DIM];
                for (int i = 0; i < batch; i++) {
                    System.arraycopy(flat, i * FEATURE_DIM, features[i], 0, FEATURE_DIM);
                }
                return features;
            }
        }
    }

    /**
     * 批量图像预处理：resize → CHW → normalize
     *
     * @param images DJL 镜像 图像数组
     * @return 归一化像素 [批量, 3, 224, 224]
     * @throws Exception 预处理异常
     */
    private float[] preprocessBatch(Image[] images) throws Exception {
        int batch = images.length;
        float[] allPixels = new float[batch * 3 * IMAGE_SIZE * IMAGE_SIZE];

        for (int b = 0; b < batch; b++) {
            float[] pixels = preprocessImage(images[b]);
            System.arraycopy(pixels, 0, allPixels, b * pixels.length, pixels.length);
        }
        return allPixels;
    }

    /**
     * 单张图像预处理：resize → CHW → normalize
     *
     * <p>使用 OpenCV (ImageUtils) 缩放图像，输出 [3, 224, 224] 归一化像素（RGB 顺序）。</p>
     *
     * @param image DJL 镜像 图像
     * @return 归一化像素 [3, 224, 224]
     * @throws Exception 预处理异常
     */
    private float[] preprocessImage(Image image) throws Exception {
        float[] pixels = ImageUtils.toTensorResize(image, IMAGE_SIZE, MEAN, STD);
        return pixels;
    }

    /**
     * 计算两个特征向量的余弦相似度
     *
     * @param a 特征向量 A
     * @param b 特征向量 B
     * @return 余弦相似度 [-1, 1]
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        return MathUtils.cosineSimilarity(a, b);
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 获取特征维度
     *
     * @return 特征维度（1024）
     */
    public int featureDimension() {
        return FEATURE_DIM;
    }

    /**
     * 关闭资源
     */
    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        session = null;
        prepared = false;
    }
}
