package com.chua.deeplearning.support.onnx.face;

import com.chua.common.support.collection.wrap.Wraps;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import org.opencv.imgproc.Imgproc;

/**
 * SCRFD 2.5G BNKPS              Translator   
 *
 * <p>                            stride   8/16/32       score + bbox + kps   
 * SCRFD        距离2bbox                          score/bbox   </p>
 *
 * <p>模型输出 9 个 tensor：score×3 + bbox×3 + kps×3（每点 10 维 = 5 关键点 × 2 坐标），
 * 关键点用于 5 点仿射对齐（修复/超分/识别前处理）。</p>
 *
 * <p><b>预处理为 letterbox（等比缩放 + 居中补黑边）</b>，与 insightface 官方 SCRFD 推理
 * 口径一致。此前直接把原图拉伸成 640×640：框是粗定位不受影响，但纵横比失真会把脸横向
 * 压扁/拉宽，模型看到的是"变形脸"，5 关键点回归随之漂移——实测 1500×2250 竖图右眼偏
 * 12.3% 脸宽，且这些关键点会进对齐、登记裁剪、识别前处理链路。换 letterbox 后模型看到
 * 自然人脸比例；缩放几何经 {@code ctx} 附件传入 {@link #processOutput}，按
 * {@link LetterBoxMeta#normX}/{@link LetterBoxMeta#normY} 映射回原图归一化坐标，
 * 与 DjlModelTranslator.adaptOutput（归一化×原图宽高）严格互逆。</p>
 *
 * @author CH
 * @since 2026-04-23
 */
public class ScrfdFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 输入尺寸
     */
    private static final int INPUT_SIZE = 640;
    /**
     * 步长数组
     */

    /**
     * 外部阈值覆盖（-1 表示未配置，使用内置默认值）。
     */
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
    private static final int[] STRIDES = {8, 16, 32}; // STRIDES
    /**
     * 锚框数量
     */
    private static final int NUM_ANCHORS = 2;
    /**
     * 分数阈值
     */
    private static final float SCORE_THRESHOLD = 0.70f;
    /**
     * NMS 阈值
     */
    private static final double NMS_THRESHOLD = 0.40d;

    /** ctx 附件键；processInput 记下 letterbox 几何参数供 processOutput 用于坐标逆映射 */
    private static final String ATTACHMENT_KEY = "scrfdLetterBox";

    @Override
    /**
     * 处理输入
     *
     * <p>letterbox：把原图按 {@code min(640/w, 640/h)} 等比缩放后居中贴到 640×640 黑底画布，
     * 两侧/上下空缺留黑（与 insightface 官方 SCRFD 推理一致）。缩放几何记入 ctx 附件，
     * processOutput 用它把模型画布坐标还原成原图归一化坐标。</p>
     */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        int origW = src.getWidth();
        int origH = src.getHeight();
        // 等比缩放：最长边贴满 640，另一边按比例缩（>640 缩小、<640 放大）
        float scale = Math.min(INPUT_SIZE / (float) origW, INPUT_SIZE / (float) origH);
        int newW = Math.max(1, Math.round(origW * scale));
        int newH = Math.max(1, Math.round(origH * scale));
        // 居中放置；黑边由 TYPE_3BYTE_BGR 画布默认零初始化提供（官方 SCRFD 同样补黑）
        int left = (INPUT_SIZE - newW) / 2;
        int top = (INPUT_SIZE - newH) / 2;
        BufferedImage resized = ImageUtils.resize(src, newW, newH, Imgproc.INTER_LINEAR);
        BufferedImage canvas = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(resized, left, top, null);
        } finally {
            graphics.dispose();
        }
        ctx.setAttachment(ATTACHMENT_KEY, new LetterBoxMeta(left, top, newW, newH));
        int[] rgb = canvas.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (int i = 0; i < rgb.length; i++) {
            int pixel = rgb[i];
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            data[i] = (r - 127.5f) / 128f;
            data[i + INPUT_SIZE * INPUT_SIZE] = (g - 127.5f) / 128f;
            data[i + 2 * INPUT_SIZE * INPUT_SIZE] = (b - 127.5f) / 128f;
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
     *
     * <p>从 ctx 附件读 letterbox 几何参数，传给 decodeStride 完成画布→原图归一化坐标的还原。
     * 拿不到附件时回退 {@link LetterBoxMeta#legacy()}，即旧拉伸口径。</p>
     */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 9) {
            return empty();
        }
        LetterBoxMeta meta = resolveMeta(ctx);
        List<Candidate> candidates = Wraps.list();
        for (int i = 0; i < STRIDES.length; i++) {
            decodeStride(candidates, squeezeBatch(list.get(i)),
                    squeezeBatch(list.get(i + STRIDES.length)),
                    squeezeBatch(list.get(i + STRIDES.length * 2)),
                    STRIDES[i], meta);
        }
        candidates.sort((l, r) -> Double.compare(r.score(), l.score()));
        List<String> names = Wraps.list();
        List<Double> probs = Wraps.list();
        List<BoundingBox> boxes = Wraps.list();
        for (Candidate c : candidates) {
            boolean keep = true;
            for (BoundingBox b : boxes) {
                if (b.getIoU(c.rectangle()) > NMS_THRESHOLD) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            names.add("face");
            probs.add(c.score());
            boxes.add(c.landmark());
        }
        return new DetectedObjects(names, probs, boxes);
    }

    /**
     * 解码Stride
     *
     * @param candidates candidates
     * @param scoreArray scorearray
     * @param bboxArray bboxarray
     * @param kpsArray kpsarray
     * @param stride stride
     * @param meta    letterbox 几何（用于画布→原图归一化坐标的还原）
     */
    private void decodeStride(List<Candidate> candidates, NDArray scoreArray, NDArray bboxArray, NDArray kpsArray, int stride, LetterBoxMeta meta) {
        float[] scores = scoreArray.toFloatArray();
        float[] boxes = bboxArray.toFloatArray();
        float[] kps = kpsArray == null ? null : kpsArray.toFloatArray();
        int featureSize = INPUT_SIZE / stride;
        int totalAnchors = featureSize * featureSize * NUM_ANCHORS;
        int scoreLength = Math.min(totalAnchors, scores.length);
        int boxLength = Math.min(totalAnchors, boxes.length / 4);
        int kpsLength = kps == null ? 0 : Math.min(totalAnchors, kps.length / 10);
        int limit = Math.min(scoreLength, boxLength);
        for (int idx = 0; idx < limit; idx++) {
            float score = scores[idx];
            if (score < effThreshold(SCORE_THRESHOLD)) {
                continue;
            }
            int location = idx / NUM_ANCHORS;
            int y = location / featureSize;
            int x = location % featureSize;
            float l = boxes[idx * 4] * stride, t = boxes[idx * 4 + 1] * stride, r = boxes[idx * 4 + 2] * stride, b = boxes[idx * 4 + 3] * stride;
            // 官方 insightface SCRFD 解码约定：anchor 中心 = 网格左上角 (x*stride, y*stride)，不加半格偏移。
            // 实测（onnxruntime 直跑同模型对照）：加 +stride/2 会使框与 5 点整体平移 4-16 输入像素，
            // 在非等比图上放大成原图数十像素的系统性偏移（眼睛点落到下眼睑/脸颊）。
            float cx = x * stride, cy = y * stride;
            float x1 = Math.max(meta.left(), clamp(cx - l, 0f, INPUT_SIZE - 1f));
            float y1 = Math.max(meta.top(), clamp(cy - t, 0f, INPUT_SIZE - 1f));
            float x2 = Math.min(meta.right(), clamp(cx + r, 0f, INPUT_SIZE - 1f));
            float y2 = Math.min(meta.bottom(), clamp(cy + b, 0f, INPUT_SIZE - 1f));
            // 黑边上的伪框与内容区完全不相交；丢弃，避免映射成原图零尺寸框
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            List<Point> points = Wraps.list();
            if (kps != null && idx < kpsLength) {
                for (int p = 0; p < 5; p++) {
                    points.add(new Point(
                            meta.normX(clamp(cx + kps[idx * 10 + p * 2] * stride, 0f, INPUT_SIZE - 1f)),
                            meta.normY(clamp(cy + kps[idx * 10 + p * 2 + 1] * stride, 0f, INPUT_SIZE - 1f))));
                }
            }
            float nX1 = meta.normX(x1);
            float nY1 = meta.normY(y1);
            float nW = meta.normX(x2) - nX1;
            float nH = meta.normY(y2) - nY1;
            candidates.add(new Candidate(new Landmark(nX1, nY1, nW, nH, points), score));
        }
    }

    /**
     * squeezebatch
     *
     * @param array array
     * @return squeezeBatch的结果
     */
    private NDArray squeezeBatch(NDArray array) {
        return array;
    }

    /**
     * Clamp
     *
     * @param value 值
     * @param min 最小
     * @param max 最大
     * @return clamp的结果
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 空
     *
     * @return 空的结果
     */
    private DetectedObjects empty() {
        return new DetectedObjects(Wraps.list(), Wraps.list(), Wraps.list());
    }

    @Override
    /**
     * 获取Batchifier
     */
    public Batchifier getBatchifier() { return null; }

    /**
     * Candidate
     *
     * @param landmark landmark
     * @param score score
     * @return Candidate的结果
     */
    private record Candidate(Landmark landmark, double score) {
        /**
         * rectangle。
         * @return rectangle的结果
         */
        private Rectangle rectangle() { return landmark; }
    }

    /**
     * letterbox 几何参数。
     *
     * <p>画布坐标→原图归一化坐标：normX=(canvasX-left)/newW、normY=(canvasY-top)/newH。
     * 内容边界恰好映射 [0,1]，与 DjlModelTranslator.adaptOutput（归一化×原图宽高）严格互逆，
     * 端到端等价于 (canvasX-left)/scale，无累计误差。缺省值 left=top=0、newW=newH=640 退化为
     * 旧拉伸口径。</p>
     *
     * @param left  内容在画布中的左偏移
     * @param top   内容在画布中的上偏移
     * @param newW  内容宽度（像素）
     * @param newH  内容高度（像素）
     */
    private record LetterBoxMeta(int left, int top, int newW, int newH) {
        /** 内容右沿 x（左闭右开 +1，clamp 右端开放） */
        private int right() { return left + newW; }
        /** 内容下沿 y */
        private int bottom() { return top + newH; }
        /** 画布 X → 原图归一化 X [0,1] */
        private float normX(float canvasX) {
            return clamp01((canvasX - left) / newW);
        }
        /** 画布 Y → 原图归一化 Y [0,1] */
        private float normY(float canvasY) {
            return clamp01((canvasY - top) / newH);
        }
        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
        /** 缺省实例 = 旧拉伸口径（640 全幅直除） */
        private static LetterBoxMeta legacy() {
            return new LetterBoxMeta(0, 0, INPUT_SIZE, INPUT_SIZE);
        }
    }

    /** 从 ctx 附件取 LetterBoxMeta；拿不到即旧口径 */
    private static LetterBoxMeta resolveMeta(TranslatorContext ctx) {
        Object v = ctx.getAttachment(ATTACHMENT_KEY);
        return v instanceof LetterBoxMeta meta ? meta : LetterBoxMeta.legacy();
    }

    /**
     * 默认构造。
     */
    public ScrfdFaceDetectorTranslator() {
    }

    /**
     * 创建 Translator（支持外部阈值覆盖）。
     *
     * @param configuration 检测配置（可空）
     */
    public ScrfdFaceDetectorTranslator(DetectionConfiguration configuration) {
        this();
        if (null != configuration) {
            float t = configuration.optFloat(DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0) {
                this.thresholdOverride = t;
            }
        }
    }

}