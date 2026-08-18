package com.chua.deeplearning.support.onnx.ocr.duguang;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读光 OCR 文字检测（DBNet 行检测，ORT 原生 + OpenCV）。
 *
 * <p>模型 {@code ocr/duguang/{small,large}/det_512.onnx} 由 jar
 * {@code utils-support-models-onnx-duguang-ocr} 提供。输入 {@code images [1,3,512,512]}
 * （OpenCV resize 到 512×512，减 ImageNet mean 再 /255），输出 {@code pred [1,1,512,512]}
 * 概率图。DB 后处理：阈值 0.2 二值化 → 轮廓 → 最小外接矩形 → unclip 1.5 →
 * 还原到原图坐标，输出四点多边形（{@link PredictRectangle#keypoints()} 存 4 个 [x,y]）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DuguangDetTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    /**
     * 概率图二值化阈值。
     */
    private static final float THRESHOLD = 0.2f;

    /**
     * 检测模型固定输入边长。
     */
    private static final int IMG_SIZE = 512;

    /**
     * ImageNet 均值（BGR 顺序，读光使用）。
     */
    private static final float[] MEAN = {123.68f, 116.78f, 103.94f};

    /**
     * 缩放最大边（防止超大图内存爆炸）。
     */
    private static final int MAX_SIDE = 4096;

    /**
     * jar 内模型资源目录（small / large 通用前缀）。
     */
    private final String resourceBase;

    /**
     * NativeLoader 缓存隔离名。
     */
    private final String modelName;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private int srcWidth;
    private int srcHeight;

    /**
     * 默认使用 small 检测模型。
     */
    public DuguangDetTranslator() {
        this("ocr/duguang/small/", "duguang-ocr-small");
    }

    /**
     * 指定资源目录构造。
     *
     * @param resourceBase 模型资源目录（jar 内路径）
     * @param modelName    模型名称
     */
    public DuguangDetTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("duguang-det-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("det");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(DuguangDetTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve("det_512.onnx");
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("读光 OCR 检测模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[DuguangOCR-det] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public synchronized List<PredictRectangle> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[" + modelName + "] 文字检测失败: " + e.getMessage(), e);
        }
    }

    private List<PredictRectangle> detect(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                srcWidth = src.cols();
                srcHeight = src.rows();
                int w = srcWidth;
                int h = srcHeight;
                float ratio = Math.min(1.0f, (float) MAX_SIDE / Math.max(w, h));
                if (ratio < 1.0f) {
                    w = Math.max(1, Math.round(w * ratio));
                    h = Math.max(1, Math.round(h * ratio));
                }
                // 统一 resize 到固定 512×512
                Mat resized = ImageUtils.resize(src, IMG_SIZE, IMG_SIZE, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * IMG_SIZE * IMG_SIZE];
                for (int y = 0; y < IMG_SIZE; y++) {
                    for (int x = 0; x < IMG_SIZE; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * IMG_SIZE + x;
                        // (v - mean)/255，BGR 顺序保持（读光用 BGR）
                        pixels[idx] = (((float) bgr[0]) - MEAN[0]) / 255.0f;
                        pixels[idx + IMG_SIZE * IMG_SIZE] = (((float) bgr[1]) - MEAN[1]) / 255.0f;
                        pixels[idx + 2 * IMG_SIZE * IMG_SIZE] = (((float) bgr[2]) - MEAN[2]) / 255.0f;
                    }
                }
                resized.release();

                long[] shape = {1, 3, IMG_SIZE, IMG_SIZE};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("images", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue();
                        float[][] probs;
                        if (out instanceof float[][][][]) {
                            probs = ((float[][][][]) out)[0][0];
                        } else if (out instanceof float[][][]) {
                            probs = ((float[][][]) out)[0];
                        } else if (out instanceof float[][]) {
                            probs = (float[][]) out;
                        } else {
                            throw new IllegalArgumentException("读光检测输出格式不识别: " + out.getClass());
                        }
                        // 概率图分辨率可能不等于输入（stride 缩放），按其实际尺寸还原
                        if (Boolean.getBoolean("duguang.debug")) {
                            float pmax = 0, psum = 0;
                            int pn = 0;
                            for (float[] row : probs) {
                                for (float v : row) {
                                    pmax = Math.max(pmax, v);
                                    psum += v;
                                    pn++;
                                }
                            }
                            log.info("[DuguangOCR-det] probMap {}x{} max={} mean={}", probs.length, probs[0].length, pmax, psum / Math.max(1, pn));
                        }
                        return boxesFromProbMap(probs, probs[0].length, probs.length);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[" + modelName + "] 文字检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 概率图 → 文本行多边形。
     *
     * @param probs 概率图 [H,W]
     * @param mapW  概率图宽
     * @param mapH  概率图高
     * @return 检测结果列表
     */
    private List<PredictRectangle> boxesFromProbMap(float[][] probs, int mapW, int mapH) {
        double scaleX = (double) srcWidth / mapW;
        double scaleY = (double) srcHeight / mapH;
        // 二值化
        Mat binary = new Mat(mapH, mapW, org.opencv.core.CvType.CV_8UC1);
        byte[] binData = new byte[mapH * mapW];
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                binData[y * mapW + x] = (probs[y][x] >= THRESHOLD) ? (byte) 255 : (byte) 0;
            }
        }
        binary.put(0, 0, binData);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        if (Boolean.getBoolean("duguang.debug")) {
            log.info("[DuguangOCR-det] contours={}", contours.size());
        }

        List<PredictRectangle> result = new ArrayList<>();
        int passedPts = 0, passedSide = 0, passedScore = 0, passedUnclip = 0;
        for (MatOfPoint contour : contours) {
            Point[] pts = contour.toArray();
            if (pts.length < 4) {
                continue;
            }
            passedPts++;
            MatOfPoint2f contour2f = new MatOfPoint2f(pts);
            org.opencv.core.RotatedRect rotatedRect = Imgproc.minAreaRect(contour2f);
            Point[] rect = new Point[4];
            rotatedRect.points(rect);
            contour2f.release();
            if (rect.length < 4) {
                contour2f.release();
                continue;
            }
            // 短边 < 3 丢弃（过小区域）
            double sside = minSide(rect);
            if (Boolean.getBoolean("duguang.debug") && passedSide == 0) {
                log.info("[DuguangOCR-det] rect pts={} sside={} contourLen={}", java.util.Arrays.toString(rect), sside, pts.length);
            }
            if (sside < 3) {
                contour2f.release();
                continue;
            }
            passedSide++;
            // 框内平均分
            float score = boxScore(probs, rect, mapW, mapH);
            if (score < 0.3f) {
                contour2f.release();
                continue;
            }
            passedScore++;
            // unclip 1.5 扩大
            Point[] expanded = unclip(rect, 1.5);
            if (expanded == null) {
                contour2f.release();
                continue;
            }
            passedUnclip++;
            double sside2 = minSide(expanded);
            if (sside2 < 5) {
                contour2f.release();
                continue;
            }
            // 还原到原图坐标
            List<float[]> keypoints = new ArrayList<>(4);
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = 0, maxY = 0;
            for (Point p : expanded) {
                float px = (float) Math.max(0, Math.min(srcWidth, Math.round(p.x * scaleX)));
                float py = (float) Math.max(0, Math.min(srcHeight, Math.round(p.y * scaleY)));
                keypoints.add(new float[]{px, py});
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
            result.add(new PredictRectangle(minX, minY, maxX - minX, maxY - minY,
                    score, 0, "text", keypoints));
            contour2f.release();
        }
        hierarchy.release();
        binary.release();
        if (Boolean.getBoolean("duguang.debug")) {
            log.info("[DuguangOCR-det] passedPts={} passedSide={} passedScore={} passedUnclip={} result={}", passedPts, passedSide, passedScore, passedUnclip, result.size());
        }
        return result;
    }

    /**
     * 多边形最短边。
     */
    private double minSide(Point[] pts) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < pts.length; i++) {
            Point a = pts[i];
            Point b = pts[(i + 1) % pts.length];
            min = Math.min(min, Math.hypot(b.x - a.x, b.y - a.y));
        }
        return min;
    }

    /**
     * 多边形内概率均值。
     */
    private float boxScore(float[][] probs, Point[] pts, int mapW, int mapH) {
        double xmin = clip(Math.floor(minX(pts)), 0, mapW - 1);
        double xmax = clip(Math.ceil(maxX(pts)), 0, mapW - 1);
        double ymin = clip(Math.floor(minY(pts)), 0, mapH - 1);
        double ymax = clip(Math.ceil(maxY(pts)), 0, mapH - 1);
        Mat mask = new Mat((int) (ymax - ymin + 1), (int) (xmax - xmin + 1), org.opencv.core.CvType.CV_8UC1, org.opencv.core.Scalar.all(0));
        Point[] shifted = new Point[pts.length];
        for (int i = 0; i < pts.length; i++) {
            shifted[i] = new Point(pts[i].x - xmin, pts[i].y - ymin);
        }
        MatOfPoint mop = new MatOfPoint(shifted);
        List<MatOfPoint> list = new ArrayList<>();
        list.add(mop);
        Imgproc.fillPoly(mask, list, new org.opencv.core.Scalar(1));
        double sum = 0;
        int count = 0;
        for (int y = (int) ymin; y <= (int) ymax; y++) {
            for (int x = (int) xmin; x <= (int) xmax; x++) {
                if (mask.get((int) (y - ymin), (int) (x - xmin))[0] > 0) {
                    sum += probs[y][x];
                    count++;
                }
            }
        }
        mask.release();
        mop.release();
        return count > 0 ? (float) (sum / count) : 0;
    }

    /**
     * 多边形扩张（unclip）。
     *
     * @param pts   原始四点多边形
     * @param ratio 扩张比例
     * @return 扩张后多边形，失败返回 null
     */
    private Point[] unclip(Point[] pts, double ratio) {
        double area = polygonArea(pts);
        double length = polygonLength(pts);
        if (length <= 0 || area <= 0) {
            return null;
        }
        double distance = area * ratio / length;
        // 沿每个顶点向外偏移 distance（近似 unclip）
        Point[] out = new Point[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Point prev = pts[(i - 1 + pts.length) % pts.length];
            Point next = pts[(i + 1) % pts.length];
            double dx1 = pts[i].x - prev.x;
            double dy1 = pts[i].y - prev.y;
            double dx2 = next.x - pts[i].x;
            double dy2 = next.y - pts[i].y;
            double n1 = Math.hypot(dx1, dy1);
            double n2 = Math.hypot(dx2, dy2);
            if (n1 == 0 || n2 == 0) {
                return null;
            }
            double ux = dx1 / n1 + dx2 / n2;
            double uy = dy1 / n1 + dy2 / n2;
            double un = Math.hypot(ux, uy);
            if (un == 0) {
                out[i] = new Point(pts[i].x, pts[i].y);
                continue;
            }
            out[i] = new Point(pts[i].x + ux / un * distance, pts[i].y + uy / un * distance);
        }
        return out;
    }

    private double polygonArea(Point[] pts) {
        double area = 0;
        for (int i = 0; i < pts.length; i++) {
            Point a = pts[i];
            Point b = pts[(i + 1) % pts.length];
            area += a.x * b.y - b.x * a.y;
        }
        return Math.abs(area) / 2;
    }

    private double polygonLength(Point[] pts) {
        double len = 0;
        for (int i = 0; i < pts.length; i++) {
            Point a = pts[i];
            Point b = pts[(i + 1) % pts.length];
            len += Math.hypot(b.x - a.x, b.y - a.y);
        }
        return len;
    }

    private double minX(Point[] pts) {
        double m = Double.MAX_VALUE;
        for (Point p : pts) {
            m = Math.min(m, p.x);
        }
        return m;
    }

    private double maxX(Point[] pts) {
        double m = Double.MIN_VALUE;
        for (Point p : pts) {
            m = Math.max(m, p.x);
        }
        return m;
    }

    private double minY(Point[] pts) {
        double m = Double.MAX_VALUE;
        for (Point p : pts) {
            m = Math.min(m, p.y);
        }
        return m;
    }

    private double maxY(Point[] pts) {
        double m = Double.MIN_VALUE;
        for (Point p : pts) {
            m = Math.max(m, p.y);
        }
        return m;
    }

    private double clip(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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