package com.chua.deeplearning.support.onnx.ocr.extractor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PP-OCRv6 文字检测（DB 算法，ORT 原生 + OpenCV）。
 *
 * <p>模型 {@code ocr/PP-OCRv6/tiny/det_infer/inference.onnx} 由 jar
 * {@code utils-support-models-onnx-paddleocrv6-tiny} 提供。输入 {@code x [1,3,H,W]}
 * （OpenCV resize 到 32 的倍数），输出 {@code fetch_name_0 [1,1,H,W]} 概率图。
 * DB 后处理：阈值二值化 → 连通域轮廓 → 最小外接矩形 → {@link DetectionInfo}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PpOcrDetTranslator implements ITranslator<byte[], List<DetectionInfo>> {

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final float THRESHOLD = 0.3f;
    private static final int MAX_SIDE = 960;

    private static final String MODEL_FILE = "inference.onnx";

    /**
     * 模型资源目录（tiny / medium 通用）。
     */
    private final String resourceBase;

    /**
     * 模型名称（用于 NativeLoader 缓存隔离）。
     */
    private final String modelName;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private int srcWidth;
    private int srcHeight;

    /**
     * 默认使用 PP-OCRv6 tiny 资源。
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
        log.info("[PaddleOCRv6-det] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public List<DetectionInfo> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-det] 文字检测失败: " + e.getMessage(), e);
        }
    }

    private List<DetectionInfo> detect(byte[] imageData) {
        try {
            nu.pattern.OpenCV.loadLocally();
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
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

                Mat resized = new Mat();
                Imgproc.resize(src, resized, new Size(w, h), 0, 0, Imgproc.INTER_LINEAR);

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
                        return boxesFromProbMap(probs, w, h);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-det] 文字检测失败: " + e.getMessage(), e);
        }
    }

    private List<DetectionInfo> boxesFromProbMap(float[][] probs, int mapW, int mapH) {
        float scaleX = (float) srcWidth / mapW;
        float scaleY = (float) srcHeight / mapH;
        // 概率图 → 二值图
        Mat binary = new Mat(mapH, mapW, org.opencv.core.CvType.CV_8UC1);
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (probs[y][x] >= THRESHOLD) {
                    binary.put(y, x, (byte) 255);
                }
            }
        }
        // 形态学膨胀
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
        Mat dilated = new Mat();
        Imgproc.dilate(binary, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        List<DetectionInfo> result = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.width < 3 || rect.height < 3 || rect.area() < 9) {
                continue;
            }
            float x1 = rect.x * scaleX;
            float y1 = rect.y * scaleY;
            float x2 = (rect.x + rect.width) * scaleX;
            float y2 = (rect.y + rect.height) * scaleY;
            // 置信度：区域内概率均值
            double sum = 0;
            int count = 0;
            for (int y = rect.y; y < rect.y + rect.height; y += 2) {
                for (int x = rect.x; x < rect.x + rect.width; x += 2) {
                    sum += probs[y][x];
                    count++;
                }
            }
            float conf = count > 0 ? (float) (sum / count) : 0;
            result.add(new DetectionInfo("text", conf,
                    Math.max(0, x1), Math.max(0, y1), Math.max(0, x2 - x1), Math.max(0, y2 - y1)));
        }
        kernel.release();
        dilated.release();
        hierarchy.release();
        binary.release();
        return result;
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
