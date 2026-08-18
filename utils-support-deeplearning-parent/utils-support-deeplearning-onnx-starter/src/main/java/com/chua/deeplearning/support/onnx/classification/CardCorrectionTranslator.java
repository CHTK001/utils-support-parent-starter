package com.chua.deeplearning.support.onnx.classification;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.Size;import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片矫正检测（CenterNet，ORT 原生 + OpenCV）。
 *
 * <p>检测卡片四角点（如身份证、银行卡），用于图像矫正。
 * 模型 {@code cv/card_correction/card_detection.onnx} 由 jar
 * {@code utils-support-models-onnx-card-correction} 提供。输入 {@code [1,3,768,768]}，
 * 输出：{@code hm[1,1,192,192]} 热图、{@code wh[1,8,192,192]} 角点宽高、
 * {@code reg[1,2,192,192]} 中心偏移、{@code cls[1,4,192,192]} 角点类别。
 * stride=4，输出 4 个角点坐标。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CardCorrectionTranslator implements ITranslator<byte[], List<DetectionInfo>> {

    /** 输入尺寸 */
    /** Input_size */
    private static final int INPUT_SIZE = 768;
    /** 热力图尺寸 */
    /** Heat_size */
    private static final int HEAT_SIZE = 192;
    /** 角点数量 */
    /** Num_corners */
    private static final int NUM_CORNERS = 4;
    /** 步长 */
    /** Stride */
    private static final int STRIDE = 4;
    /** 置信度阈值 */
    /** Conf_threshold */
    private static final float CONF_THRESHOLD = 0.3f;

    /** 资源基础路径 */
    /** Resource_base */
    private static final String RESOURCE_BASE = "cv/card_correction/";
    /** 模型文件路径 */
    /** Model_file */
    private static final String MODEL_FILE = "card_detection.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
    /** 会话 */
    private OrtSession session;
    /** 源图像宽度 */
    /** SRC宽度 */
    private int srcWidth;
    /** 源图像高度 */
    /** SRC高度 */
    private int srcHeight;

    /**
     * 共享实例（避免多实例重复提取模型 / 创建 session）。
     */
    private static volatile CardCorrectionTranslator shared;

    /**
     * 获取共享实例。
     *
     * @return 共享实例
     */
    public static CardCorrectionTranslator shared() {
        if (shared == null) {
            synchronized (CardCorrectionTranslator.class) {
                if (shared == null) {
                    shared = new CardCorrectionTranslator();
                    try {
                        shared.prepare();
                    } catch (Exception e) {
                        throw new RuntimeException("[card-correction-detector] 模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        return shared;
    }

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        // 固定缓存目录（NativeLoader 按 taskId 全局去重，不能用每次新建的临时目录）
        Path modelDir = Path.of(System.getProperty("java.io.tmpdir"), "chua-models", "card-correction");
        if (!Files.isDirectory(modelDir) || !Files.exists(modelDir.resolve(MODEL_FILE))) {
            Files.createDirectories(modelDir);
            NativeLoader.of("card-correction-detector")
                    .from(CardCorrectionTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*.onnx")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("卡片矫正模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[CardCorrection] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return "card-correction-detector";
    }

    @Override
    public List<DetectionInfo> translate(byte[] imageData) {
        try {
            prepare();
            List<float[][]> quads = detectQuads(imageData);
            List<DetectionInfo> result = new ArrayList<>(quads.size());
            for (float[][] quad : quads) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (float[] p : quad) {
                    minX = Math.min(minX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxX = Math.max(maxX, p[0]);
                    maxY = Math.max(maxY, p[1]);
                }
                result.add(new DetectionInfo("card", 1f,
                        Math.max(0, minX), Math.max(0, minY),
                        Math.max(0, maxX - minX), Math.max(0, maxY - minY)));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("[card-correction-detector] 卡片矫正检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 卡片透视矫正：检测卡片四边形并拉平为水平矩形图。
     *
     * @param imageData 原图
     * @return 矫正后的卡片图（PNG），未检测到卡片返回 null
     */
    public byte[] correct(byte[] imageData) {
        try {
            prepare();
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                List<float[][]> quads = detectQuadsOn(src);
                if (quads.isEmpty()) {
                    return null;
                }
                float[][] quad = quads.get(0);
                // 4 角点按左上/右上/右下/左下排序（模型输出顺序：0 右下,1 左下,2 左上,3 右上）
                org.opencv.core.MatOfPoint2f srcPts = new org.opencv.core.MatOfPoint2f(
                        new org.opencv.core.Point(quad[2][0], quad[2][1]), // 左上
                        new org.opencv.core.Point(quad[3][0], quad[3][1]), // 右上
                        new org.opencv.core.Point(quad[0][0], quad[0][1]), // 右下
                        new org.opencv.core.Point(quad[1][0], quad[1][1])); // 左下
                double w = Math.max(
                        Math.sqrt(Math.pow(quad[3][0] - quad[2][0], 2) + Math.pow(quad[3][1] - quad[2][1], 2)),
                        Math.sqrt(Math.pow(quad[0][0] - quad[1][0], 2) + Math.pow(quad[0][1] - quad[1][1], 2)));
                double h = Math.max(
                        Math.sqrt(Math.pow(quad[1][0] - quad[2][0], 2) + Math.pow(quad[1][1] - quad[2][1], 2)),
                        Math.sqrt(Math.pow(quad[0][0] - quad[3][0], 2) + Math.pow(quad[0][1] - quad[3][1], 2)));
                int outW = Math.max(1, (int) Math.round(w));
                int outH = Math.max(1, (int) Math.round(h));
                org.opencv.core.MatOfPoint2f dstPts = new org.opencv.core.MatOfPoint2f(
                        new org.opencv.core.Point(0, 0),
                        new org.opencv.core.Point(outW - 1, 0),
                        new org.opencv.core.Point(outW - 1, outH - 1),
                        new org.opencv.core.Point(0, outH - 1));
                org.opencv.core.Mat perspective = Imgproc.getPerspectiveTransform(srcPts, dstPts);
                Mat corrected = new Mat();
                Imgproc.warpPerspective(src, corrected, perspective, new Size(outW, outH), Imgproc.INTER_LINEAR);
                byte[] result = ImageUtils.encode(corrected);
                corrected.release();
                perspective.release();
                srcPts.release();
                dstPts.release();
                return result;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[card-correction-detector] 卡片矫正失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检测图像中的卡片四边形（原图坐标）。
     *
     * @param imageData 原图
     * @return 四边形列表，每项 4×2（角点 [x,y]），未检测到返回空列表
     */
    public List<float[][]> detectQuads(byte[] imageData) {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图像");
        }
        try {
            return detectQuadsOn(src);
        } finally {
            src.release();
        }
    }

    /**
     * 对已解码的 Mat 检测卡片四边形（原图坐标）。
     */
    private List<float[][]> detectQuadsOn(Mat src) {
        try {
            srcWidth = src.cols();
            srcHeight = src.rows();
            Mat resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, Imgproc.INTER_LINEAR);

            float[] pixels = new float[3 * INPUT_SIZE * INPUT_SIZE];
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    double[] bgr = resized.get(y, x);
                    int idx = y * INPUT_SIZE + x;
                    pixels[idx] = (float) bgr[2] / 255.0f;
                    pixels[idx + INPUT_SIZE * INPUT_SIZE] = (float) bgr[1] / 255.0f;
                    pixels[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (float) bgr[0] / 255.0f;
                }
            }
            resized.release();

            long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
            try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("image", tensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[][] hm = toMat2D(result.get("hm").get().getValue());
                    float[][][] wh = toMat3D(result.get("wh").get().getValue());
                    float[][][] reg = toMat3D(result.get("reg").get().getValue());
                    return decodeCorners(hm, wh, reg);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("[card-correction-detector] 卡片矫正检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解码卡片四边形角点（原图坐标）。
     */
    private List<float[][]> decodeCorners(float[][] hm, float[][][] wh, float[][][] reg) {
        float scaleX = (float) srcWidth / INPUT_SIZE;
        float scaleY = (float) srcHeight / INPUT_SIZE;
        List<float[][]> result = new ArrayList<>();
        List<int[]> centers = findPeaks(hm, CONF_THRESHOLD);
        for (int[] c : centers) {
            int bestY = c[0];
            int bestX = c[1];
            float centerX = (bestX + reg[0][bestY][bestX]) * STRIDE;
            float centerY = (bestY + reg[1][bestY][bestX]) * STRIDE;
            float[][] corners = new float[NUM_CORNERS][2];
            for (int corner = 0; corner < NUM_CORNERS; corner++) {
                corners[corner][0] = (centerX + wh[corner * 2][bestY][bestX] * STRIDE) * scaleX;
                corners[corner][1] = (centerY + wh[corner * 2 + 1][bestY][bestX] * STRIDE) * scaleY;
            }
            result.add(corners);
        }
        return result;
    }

    /**
     * 热图峰值检测（局部极大值 + NMS 抑制，支持多卡片）。
     *
     * @param hm         热图 [H][W]
     * @param threshold  置信度阈值
     * @return 峰值 (y,x) 列表，按置信度降序
     */
    private List<int[]> findPeaks(float[][] hm, float threshold) {
        List<int[]> peaks = new ArrayList<>();
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < HEAT_SIZE; y++) {
            for (int x = 0; x < HEAT_SIZE; x++) {
                float v = hm[y][x];
                if (v >= threshold && isLocalMax(hm, x, y)) {
                    peaks.add(new int[]{y, x});
                    vals.add(v);
                }
            }
        }
        // 按置信度降序
        Integer[] idx = new Integer[peaks.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(vals.get(b), vals.get(a)));
        // NMS：抑制距离过近的峰（半径 8 像素）
        List<int[]> kept = new ArrayList<>();
        for (int i : idx) {
            int[] p = peaks.get(i);
            boolean dup = false;
            for (int[] k : kept) {
                if (Math.abs(k[0] - p[0]) <= 8 && Math.abs(k[1] - p[1]) <= 8) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                kept.add(p);
            }
        }
        return kept;
    }

    private boolean isLocalMax(float[][] hm, int x, int y) {
        float v = hm[y][x];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx >= 0 && nx < HEAT_SIZE && ny >= 0 && ny < HEAT_SIZE) {
                    if (hm[ny][nx] > v) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private float[][] toMat2D(Object value) {
        // 输入 [1, C, H, W]，单通道 C=1 → 返回 [H][W]
        float[][][][] arr4 = (float[][][][]) value;
        float[][][] arr = arr4[0];
        int h = arr[0].length;
        int w = arr[0][0].length;
        float[][] out = new float[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                out[i][j] = arr[0][i][j];
            }
        }
        return out;
    }

    private float[][][] toMat3D(Object value) {
        float[][][][] arr4 = (float[][][][]) value;
        return arr4[0];
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
