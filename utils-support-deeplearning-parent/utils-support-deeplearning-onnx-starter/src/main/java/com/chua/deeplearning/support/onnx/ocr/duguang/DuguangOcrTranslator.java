package com.chua.deeplearning.support.onnx.ocr.duguang;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;import org.opencv.imgproc.Imgproc;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读光 OCR 中英文识别（DBNet 检测 + 行识别，ORT 原生 + OpenCV）。
 *
 * <p>模型由 jar {@code utils-support-models-onnx-duguang-ocr} 提供。流程：
 * <ol>
 *   <li>DBNet 检测文本行（{@link DuguangDetTranslator}）</li>
 *   <li>每行透视裁剪 → 保持宽高比缩放到高 32（宽 ≤804 右补零）</li>
 *   <li>按 300 宽、48 重叠切成 3 段 → {@code [3,3,32,300]} 输入</li>
 *   <li>CTC 解码去重 + 3 段重叠合并输出文本</li>
 * </ol>
 * small 识别输出 {@code (3,75,vocab)}，large 输出 {@code (1,201,vocab)}（内部拼 3 段）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DuguangOcrTranslator implements ITranslator<byte[], List<OcrResult>> {

    /**
     * 识别输入高度。
     */
    private static final int REC_HEIGHT = 32;

    /**
     * 识别最大宽度（右补零到该宽度）。
     */
    private static final int REC_WIDTH = 804;

    /**
     * chunk 宽度。
     */
    private static final int CHUNK_WIDTH = 300;

    /**
     * chunk 重叠宽度。
     */
    private static final int CHUNK_OVERLAP = 48;

    /**
     * chunk 数量。
     */
    private static final int CHUNK_COUNT = 3;

    /**
     * small 识别每 chunk 时间步。
     */
    private static final int SMALL_STEPS = 75;

    /**
     * large 识别每 chunk 时间步。
     */
    private static final int LARGE_CHUNK_STEPS = 67;

    /**
     * 单词表加载起始 id（0=blank，1=预留）。
     */
    private static final int VOCAB_START_ID = 2;

    /**
     * jar 内资源目录。
     */
    private final String resourceBase;

    /**
     * NativeLoader 缓存隔离名。
     */
    private final String modelName;

    /**
     * 是否 large 模型（影响解码分段时间步）。
     */
    private final boolean large;

    private final DuguangDetTranslator detTranslator;

    private OrtEnvironment ortEnv;
    private OrtSession recSession;
    private Map<Integer, String> vocab;
    private volatile boolean loaded;

    /**
     * 默认使用 small 模型。
     */
    public DuguangOcrTranslator() {
        this(false);
    }

    /**
     * 指定模型规模构造。
     *
     * @param large true 使用 large（更准更慢），false 使用 small
     */
    public DuguangOcrTranslator(boolean large) {
        this.large = large;
        this.resourceBase = large ? "ocr/duguang/large/" : "ocr/duguang/small/";
        this.modelName = large ? "duguang-ocr-large" : "duguang-ocr-small";
        this.detTranslator = new DuguangDetTranslator(resourceBase, modelName);
    }

    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("duguang-rec-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("rec");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName + "-rec")
                .from(DuguangOcrTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path recPath = modelDir.resolve("rec.onnx");
        if (!Files.isRegularFile(recPath)) {
            throw new IllegalArgumentException("读光 OCR 识别模型缺失: " + recPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.recSession = ortEnv.createSession(recPath.toString(), opts);
        this.vocab = loadVocab();
        loaded = true;
        log.info("[DuguangOCR-rec] ONNX loaded: {} vocab={}", recPath.getFileName(), vocab.size());
    }

    /**
     * 从 jar 加载单词表（common/vocab.txt）。
     */
    private Map<Integer, String> loadVocab() throws Exception {
        Map<Integer, String> map = new HashMap<>();
        String path = "ocr/duguang/common/vocab.txt";
        try (InputStream is = DuguangOcrTranslator.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("读光 OCR 词表缺失: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int id = VOCAB_START_ID;
                while ((line = reader.readLine()) != null) {
                    map.put(id++, line.endsWith("\n") ? line.substring(0, line.length() - 1) : line);
                }
            }
        }
        return map;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public synchronized List<OcrResult> translate(byte[] imageData) {
        try {
            prepare();
            return recognize(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[" + modelName + "] 文字识别失败: " + e.getMessage(), e);
        }
    }

    private List<OcrResult> recognize(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                List<PredictRectangle> boxes = detTranslator.translate(imageData);
                List<OcrResult> results = new ArrayList<>();
                for (PredictRectangle box : boxes) {
                    List<float[]> kp = box.keypoints();
                    if (kp == null || kp.size() < 4) {
                        continue;
                    }
                    Mat crop = perspectiveCrop(src, kp);
                    String text = recognizeLine(crop);
                    crop.release();
                    if (text.isEmpty()) {
                        continue;
                    }
                    results.add(new OcrResult(text, box.confidence(), box));
                }
                return results;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[" + modelName + "] 文字识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 四点透视裁剪文本行。
     *
     * @param src 原图
     * @param kp  四个角点 [x,y]
     * @return 裁剪后的行图像
     */
    private Mat perspectiveCrop(Mat src, List<float[]> kp) {
        Point[] corners = new Point[4];
        for (int i = 0; i < 4; i++) {
            float[] p = kp.get(i);
            corners[i] = new Point(p[0], p[1]);
        }
        // 排序：按中心角度（左上、右上、右下、左下）
        Point[] ordered = orderCorners(corners);
        double topWidth = Math.hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y);
        double bottomWidth = Math.hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y);
        double leftHeight = Math.hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y);
        double rightHeight = Math.hypot(ordered[2].x - ordered[1].x, ordered[2].y - ordered[1].y);
        int w = Math.max(1, (int) Math.round(Math.max(topWidth, bottomWidth)));
        int h = Math.max(1, (int) Math.round(Math.max(leftHeight, rightHeight)));

        Mat dst = new Mat(h, w, src.type());
        MatOfPoint2f srcPts = new MatOfPoint2f(ordered);
        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0), new Point(w - 1, 0), new Point(w - 1, h - 1), new Point(0, h - 1));
        Mat transform = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Imgproc.warpPerspective(src, dst, transform, new Size(w, h), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, Scalar.all(255));
        transform.release();
        srcPts.release();
        dstPts.release();
        return dst;
    }

    /**
     * 四角排序（左上、右上、右下、左下）。
     */
    private Point[] orderCorners(Point[] corners) {
        final double cx, cy;
        double sx = 0, sy = 0;
        for (Point p : corners) {
            sx += p.x;
            sy += p.y;
        }
        cx = sx / 4;
        cy = sy / 4;
        Point[] sorted = corners.clone();
        java.util.Arrays.sort(sorted, (a, b) -> {
            double thetaA = Math.atan2(a.y - cy, a.x - cx);
            double thetaB = Math.atan2(b.y - cy, b.x - cx);
            return Double.compare(thetaA, thetaB);
        });
        // 排序后是顺时针（从右上开始），调整为左上、右上、右下、左下
        return new Point[]{sorted[0], sorted[1], sorted[2], sorted[3]};
    }

    /**
     * 识别单行文字。
     *
     * @param crop 裁剪的行图像
     * @return 识别文本
     */
    private String recognizeLine(Mat crop) throws Exception {
        // 保持宽高比缩放到高 32，宽 ≤804，右侧补白
        int h = crop.rows();
        int w = crop.cols();
        if (h <= 0 || w <= 0) {
            return "";
        }
        double ratio = (double) w / h;
        int targetW = Math.min(REC_WIDTH, (int) Math.round(REC_HEIGHT * ratio));
        Mat resized = ImageUtils.resize(crop, targetW, REC_HEIGHT, Imgproc.INTER_LINEAR);
        Mat padded = Mat.zeros(REC_HEIGHT, REC_WIDTH, crop.type());
        resized.copyTo(padded.submat(0, REC_HEIGHT, 0, targetW));
        resized.release();

        // 3 chunk：300 宽、48 重叠
        float[] chunkPixels = new float[CHUNK_COUNT * CHUNK_WIDTH * REC_HEIGHT * 3];
        int totalPixels = REC_WIDTH * REC_HEIGHT;
        byte[] rowData = new byte[REC_WIDTH * 3];
        for (int c = 0; c < CHUNK_COUNT; c++) {
            int left = (CHUNK_WIDTH - CHUNK_OVERLAP) * c;
            for (int y = 0; y < REC_HEIGHT; y++) {
                padded.get(y, 0, rowData);
                int base = (c * REC_HEIGHT + y) * CHUNK_WIDTH;
                for (int x = 0; x < CHUNK_WIDTH; x++) {
                    int sx = left + x;
                    int pixelBase = base + x;
                    int bgrBase = sx * 3;
                    int b = rowData[bgrBase] & 0xFF;
                    int g = rowData[bgrBase + 1] & 0xFF;
                    int r = rowData[bgrBase + 2] & 0xFF;
                    chunkPixels[pixelBase] = b / 255.0f;
                    chunkPixels[pixelBase + CHUNK_COUNT * REC_HEIGHT * CHUNK_WIDTH] = g / 255.0f;
                    chunkPixels[pixelBase + 2 * CHUNK_COUNT * REC_HEIGHT * CHUNK_WIDTH] = r / 255.0f;
                }
            }
        }
        padded.release();

        long[] shape = {CHUNK_COUNT, 3, REC_HEIGHT, CHUNK_WIDTH};
        String[] texts = new String[CHUNK_COUNT];
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(chunkPixels), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(recSession.getInputInfo().keySet().iterator().next(), tensor);
            try (OrtSession.Result result = recSession.run(inputs)) {
                Object out = result.get(0).getValue();
                float[][][] logits;
                if (out instanceof float[][][][]) {
                    logits = ((float[][][][]) out)[0];
                } else if (out instanceof float[][][]) {
                    logits = (float[][][]) out;
                } else {
                    throw new IllegalArgumentException("读光识别输出格式不识别: " + out.getClass());
                }
                int batch = logits.length;
                int steps = logits[0].length;
                // large 输出 1 行拼接 3 段（每段 67 步）；small 输出 3 行（每行 75 步）
                if (large && batch == 1) {
                    for (int c = 0; c < CHUNK_COUNT; c++) {
                        float[][] seg = new float[LARGE_CHUNK_STEPS][];
                        System.arraycopy(logits[0], c * LARGE_CHUNK_STEPS, seg, 0, LARGE_CHUNK_STEPS);
                        texts[c] = decode(seg);
                    }
                } else {
                    for (int c = 0; c < Math.min(batch, CHUNK_COUNT); c++) {
                        texts[c] = decode(logits[c]);
                    }
                }
            }
        }
        return mergeTexts(texts);
    }

    /**
     * CTC 解码：softmax → argmax → 去重（相邻相同跳过，blank=0 跳过）。
     */
    private String decode(float[][] logits) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (float[] step : logits) {
            int best = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int i = 1; i < step.length; i++) {
                if (step[i] > bestScore) {
                    bestScore = step[i];
                    best = i;
                }
            }
            if (best != last && best != 0) {
                String ch = vocab.get(best);
                if (ch != null) {
                    sb.append(ch);
                }
            }
            last = best;
        }
        return sb.toString();
    }

    /**
     * 3 段文本重叠合并。
     */
    private String mergeTexts(String[] texts) {
        if (texts[0] == null || texts[0].isEmpty()) {
            return "";
        }
        String result = texts[0];
        for (int i = 1; i < texts.length; i++) {
            String s = texts[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            int bestOverlap = 0;
            int maxOverlap = Math.min(result.length(), s.length());
            for (int ov = maxOverlap; ov >= 0; ov--) {
                if (result.endsWith(s.substring(0, ov))) {
                    bestOverlap = ov;
                    break;
                }
            }
            result += s.substring(bestOverlap);
        }
        return result;
    }

    /**
     * 关闭底层 ONNX Session。
     */
    public synchronized void close() {
        try {
            if (recSession != null) {
                recSession.close();
            }
        } catch (Exception ignore) {
        }
        recSession = null;
        ortEnv = null;
        loaded = false;
    }
}