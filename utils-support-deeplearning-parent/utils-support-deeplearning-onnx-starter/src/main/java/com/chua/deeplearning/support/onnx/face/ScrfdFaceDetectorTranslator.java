package com.chua.deeplearning.support.onnx.face;

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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
 * SCRFD 2.5G BNKPS              Translator   
 *
 * <p>                            stride   8/16/32       score + bbox + kps   
   * SCRFD        距离2bbox                          score/bbox   </p>
 *
 * <p>模型输出 9 个 tensor：score×3 + bbox×3 + kps×3（每点 10 维 = 5 关键点 × 2 坐标），
 * 关键点用于 5 点仿射对齐（修复/超分/识别前处理）。</p>
 *
 * @author CH
 * @since 2026-04-23
 */
public class ScrfdFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 640;
    /** 步长数组 */
    /** Strides */

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
    private static final int[] STRIDES = {8, 16, 32}; // STRIDES
    /** 锚框数量 */
    /** Num_锚栓 */
    private static final int NUM_ANCHORS = 2;
    /** 分数阈值 */
    /** Score_阈值 */
    private static final float SCORE_THRESHOLD = 0.70f;
    /** NMS 阈值 */
    /** Nms_阈值 */
    private static final double NMS_THRESHOLD = 0.40d;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        int[] rgb = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
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
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 9) {
            return empty();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < STRIDES.length; i++) {
            decodeStride(candidates, squeezeBatch(list.get(i)), squeezeBatch(list.get(i + STRIDES.length)), squeezeBatch(list.get(i + STRIDES.length * 2)), STRIDES[i]);
        }
        candidates.sort((l, r) -> Double.compare(r.score(), l.score()));
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean keep = true;
            for (BoundingBox b : boxes) {
                if (b.getIoU(c.rectangle()) > NMS_THRESHOLD) { keep = false; break; }
                if (!keep) {
                    continue;
                }
            }
            names.add("face"); probs.add(c.score()); boxes.add(c.landmark());
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
     */
    private void decodeStride(List<Candidate> candidates, NDArray scoreArray, NDArray bboxArray, NDArray kpsArray, int stride) {
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
            int y = location / featureSize; int x = location % featureSize;
            float l = boxes[idx * 4] * stride, t = boxes[idx * 4 + 1] * stride, r = boxes[idx * 4 + 2] * stride, b = boxes[idx * 4 + 3] * stride;
            float cx = x * stride + stride * 0.5f, cy = y * stride + stride * 0.5f;
            float x1 = clamp(cx - l, 0f, INPUT_SIZE - 1f), y1 = clamp(cy - t, 0f, INPUT_SIZE - 1f);
            float x2 = clamp(cx + r, 0f, INPUT_SIZE - 1f), y2 = clamp(cy + b, 0f, INPUT_SIZE - 1f);
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            List<Point> points = new ArrayList<>();
            if (kps != null && idx < kpsLength) {
                for (int p = 0; p < 5; p++) {
                    points.add(new Point(clamp(cx + kps[idx * 10 + p * 2] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE, clamp(cy + kps[idx * 10 + p * 2 + 1] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE));
                }
            }
            float nX1 = x1 / INPUT_SIZE; float nY1 = y1 / INPUT_SIZE; float nW = (x2 - x1) / INPUT_SIZE; float nH = (y2 - y1) / INPUT_SIZE;
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
        return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Override
    /** 获取Batchifier */
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
        /** 默认构造。 */
    public ScrfdFaceDetectorTranslator() {
    }

/**
     * 创建 Translator（支持外部阈值覆盖）。
     *
     * @param configuration 检测配置（可空）
     */
    public ScrfdFaceDetectorTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this();
        if (null != configuration) {
            float t = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0) {
                this.thresholdOverride = t;
            }
        }
    }

}
