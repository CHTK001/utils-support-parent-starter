package com.chua.deeplearning.support.onnx.ocr.extractor;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
* PP-ocrv6 文字检测（DB 算法，ORT 原生 + 打开cv）。
*
* <p>模型 {@code ocr/PP-OCRv6/tiny/det_infer/inference.onnx} 由 jar
* {@code utils-support-models-onnx-paddleocrv6-tiny} 提供。输入 {@code x [1,3,H,W]}
* （打开cv resize 到 32 的倍数），输出 {@code fetch_name_0 [1,1,H,W]} 概率图。
* DB 后处理：阈值二值化 → 连通域轮廓 → 最小外接矩形 → {@link DetectionInfo}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class PpOcrDetTranslator implements ITranslator<byte[], List<DetectionInfo>> {

    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    /** 阈值 */
    private static final float THRESHOLD = 0.3f;
    /** 最大边长 */
    /** 最大_side */
    private static final int MAX_SIDE = 960;

        /** 外部阈值覆盖（-1 表示未配置，使用内置默认值）。 */
    private float thresholdOverride = -1f;

    /**
    * 取生效阈值。
    *
    * @param def def
    * @return eff阈值的结果
     */
    private float effThreshold(float def) {
        return thresholdOverride > 0 ? thresholdOverride : def;
    }

    /**
    * 创建 Translator（支持外部阈值覆盖）。
    *
    * @param configuration 检测配置（可空）
     */
    public PpOcrDetTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this();
        if (null != configuration) {
            float t = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0) {
                this.thresholdOverride = t;
            }
        }
    }

/**
* 获取 DB 二值化阈值，子类可覆盖（如 medium 模型用 0.25）。
*
* @return 阈值
     */
    protected float getThreshold() {
        return effectiveThreshold(THRESHOLD);
    }

    /**
    * 取生效阈值（外部覆盖优先）。
    *
    * @param modelDefault 模型默认
    * @return effective阈值的结果
     */
    protected float effectiveThreshold(float modelDefault) {
        return thresholdOverride > 0 ? thresholdOverride : modelDefault;
    }

    /** 模型文件路径 */
    /** 模型_文件 */
    private static final String MODEL_FILE = "inference.onnx";

    /**
    * 模型资源目录（tiny / medium 通用）。
     */
    private final String resourceBase;

    /**
    * 模型名称（用于 NAT加载 缓存隔离）。
     */
    private final String modelName;

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;
    /** 源图像宽度 */
    /** SRC宽度 */
    private int srcWidth;
    /** 源图像高度 */
    /** SRC高度 */
    private int srcHeight;

    /**
    * 默认使用 PP-ocrv6 tiny 资源。
     */
    public PpOcrDetTranslator() {
        this("ocr/PP-OCRv6/tiny/det_infer/", "paddleocrv6-det");
    }

    /**
    * 指定资源目录构造。
    *
    * @param resourceBase 模型资源目录（jar 内路径）
    * @param modelName    模型名称
     */
    public PpOcrDetTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("paddleocrv6-det-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("det");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(PpOcrDetTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("OCR 检测模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        // 预热：首次推理初始化 ORT 内部状态，避免首次调用结果异常
        warmup();
        log.info("[PaddleOCRv6-det] ONNX loaded: {}", modelPath.getFileName());
    }

    /**
    * 预热推理（全零小图），确保后续调用稳定。
     */
    private void warmup() throws Exception {
        try {
            int w = 960;
            int h = 608;
            float[] pixels = new float[3 * h * w];
            long[] shape = {1, 3, h, w};
            try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("x", tensor);
                try (OrtSession.Result ignored = session.run(inputs)) {
                    // warmup 完成
                }
                try (OnnxTensor tensor2 = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs2 = new HashMap<>();
                    inputs2.put("x", tensor2);
                    try (OrtSession.Result ignored = session.run(inputs2)) {
                        // 二次 warmup 确保状态就绪
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[PaddleOCRv6-det] warmup 跳过: {}", e.getMessage());
        }
    }

    @Override
    /** 名称 */
    public String name() {
        return modelName;
    }

    @Override
    /** Translate */
    public synchronized List<DetectionInfo> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-det] 文字检测失败: " + e.getMessage(), e);
        }
    }

    /**
    * Detect
    *
    * @param imageData 镜像数据
    * @return detect的结果
     */
    private List<DetectionInfo> detect(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                // 深色背景自动反色（白底黑字提升检测/识别率）
                Mat gray = new Mat();
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
                if (Core.mean(gray).val[0] < 128) {
                    Core.bitwise_not(src, src);
                }
                gray.release();
                srcWidth = src.cols();
                srcHeight = src.rows();
                // 限制最长边，保持比例
                int w = srcWidth;
                int h = srcHeight;
                float ratio = Math.min(1.0f, (float) MAX_SIDE / Math.max(w, h));
                if (ratio < 1.0f) {
                    w = Math.max(1, Math.round(w * ratio));
                    h = Math.max(1, Math.round(h * ratio));
                }
                // 对齐到 32 的倍数
                w = Math.max(32, (w / 32) * 32);
                h = Math.max(32, (h / 32) * 32);

                Mat resized = ImageUtils.resize(src, w, h, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * h * w];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * w + x;
                        // 归一化 (v/255 - mean) / std，BGR→RGB
                        pixels[idx] = (((float) bgr[2] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + h * w] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * h * w] = (((float) bgr[0] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                resized.release();

                long[] shape = {1, 3, h, w};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue();
                        float[][][] probMap;
                        if (out instanceof float[][][][]) {
                            probMap = ((float[][][][]) out)[0];
                        } else if (out instanceof float[][][]) {
                            probMap = (float[][][]) out;
                        } else {
                            throw new IllegalArgumentException("检测输出格式不识别: " + out.getClass());
                        }
                        float[][] probs;
                        if (probMap.length == 1) {
                            probs = probMap[0];
                        } else {
                            // 取最大通道
                            int c = probMap.length;
                            probs = new float[probMap[0].length][probMap[0][0].length];
                            for (int i = 0; i < probs.length; i++) {
                                for (int j = 0; j < probs[0].length; j++) {
                                    float best = Float.NEGATIVE_INFINITY;
                                    for (int k = 0; k < c; k++) {
                                        best = Math.max(best, probMap[k][i][j]);
                                    }
                                    probs[i][j] = best;
                                }
                            }
                        }
                        log.debug("[PaddleOCRv6-det] probMap dims: len={} h={} w={}", probMap.length,
                                probs.length, probs[0].length);
                        float pmax = 0;
                        for (float[] row : probs) {
                            for (float v : row) {
                                pmax = Math.max(pmax, v);
                            }
                        }
                        log.debug("[PaddleOCRv6-det] probMax={}", pmax);
                        // 用概率图实际维度（stride 缩放后），而非输入宽高
                        return boxesFromProbMap(probs, probs[0].length, probs.length);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-det] 文字检测失败: " + e.getMessage(), e);
        }
    }

    /**
    * boxes从prob映射
    *
    * @param probs probs
    * @param mapW 映射w
    * @param mapH 映射h
    * @return boxes从prob映射的结果
     */
    private List<DetectionInfo> boxesFromProbMap(float[][] probs, int mapW, int mapH) {
        float scaleX = (float) srcWidth / mapW;
        float scaleY = (float) srcHeight / mapH;
 // 概率图 → 二值图（一次性写入 byte 数组，避免逐像素 放入 的边界问题）
        Mat binary = new Mat(mapH, mapW, org.opencv.core.CvType.CV_8UC1);
        byte[] binData = new byte[mapH * mapW];
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                binData[y * mapW + x] = (probs[y][x] >= getThreshold()) ? (byte) 255 : (byte) 0;
            }
        }
        binary.put(0, 0, binData);
        // 形态学膨胀
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
        Mat dilated = new Mat();
        Imgproc.dilate(binary, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        List<DetectionInfo> result = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            // 轴对齐框（保持原有检测精度）
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.width < 5 || rect.height < 5 || rect.area() < 25) {
                continue;
            }
            // 旋转框角度 + 尺寸（统一为长边=rw, 短边=rh）
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            RotatedRect rotatedRect = Imgproc.minAreaRect(contour2f);
            contour2f.release();
            float rw = (float)(rotatedRect.size.width * scaleX);
            float rh = (float)(rotatedRect.size.height * scaleY);
            float angle;
            if (rw >= rh) {
                angle = (float) rotatedRect.angle;
            } else {
                // 长边在 height 方向，角度需补偿 90°，同时交换 rw/rh
                angle = (float) rotatedRect.angle + 90;
                float tmp = rw; rw = rh; rh = tmp;
            }
            if (angle > 90) {
                angle -= 180;
            }
            if (angle < -90) {
                angle += 180;
            }
            float x1 = rect.x * scaleX;
            float y1 = rect.y * scaleY;
            float x2 = (rect.x + rect.width) * scaleX;
            float y2 = (rect.y + rect.height) * scaleY;
            // 置信度：旋转框掩码内概率均值（排除轴对齐框中的背景干扰）
            float conf = 0;
            Mat mask = new Mat(rect.height, rect.width, org.opencv.core.CvType.CV_8UC1, new org.opencv.core.Scalar(0));
            Point[] rotPts = new Point[4];
            rotatedRect.points(rotPts);
            for (int i = 0; i < 4; i++) {
                rotPts[i] = new Point(rotPts[i].x - rect.x, rotPts[i].y - rect.y);
            }
            MatOfPoint maskPts = new MatOfPoint(rotPts);
            Imgproc.fillConvexPoly(mask, maskPts, new Scalar(255));
            maskPts.release();
            double sum = 0;
            int count = 0;
            for (int y = 0; y < rect.height; y += 2) {
                for (int x = 0; x < rect.width; x += 2) {
                    if (mask.get(y, x)[0] > 0) {
                        sum += probs[rect.y + y][rect.x + x];
                        count++;
                    }
                }
            }
            mask.release();
            conf = count > 0 ? (float) (sum / count) : 0;
            result.add(new DetectionInfo("text", conf,
                    Math.max(0, x1), Math.max(0, y1), Math.max(0, x2 - x1), Math.max(0, y2 - y1), angle,
                    rw, rh,
                    (float)(rotatedRect.center.x * scaleX), (float)(rotatedRect.center.y * scaleY)));
        }
        kernel.release();
        dilated.release();
        hierarchy.release();
        binary.release();
        return result;
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
    }
    /**
    * 子类注入外部阈值覆盖。
    *
    * @param value 值
     */
    protected void applyThresholdOverride(float value) {
        if (value > 0) {
            this.thresholdOverride = value;
        }
    }

}
