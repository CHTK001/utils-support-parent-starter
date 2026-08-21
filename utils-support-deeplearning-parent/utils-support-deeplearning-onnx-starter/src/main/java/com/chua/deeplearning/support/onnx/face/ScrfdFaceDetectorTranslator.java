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

/**
 * SCRFD 2.5G BNKPS              Translator   
 *
 * <p>                            stride   8/16/32       score + bbox + kps   
 *           SCRFD        distance2bbox                          score/bbox   </p>
 *
 * <p>模型输出 9 个 tensor：score×3 + bbox×3 + kps×3（每点 10 维 = 5 关键点 × 2 坐标），
 * 关键点用于 5 点仿射对齐（修复/超分/识别前处理）。</p>
 *
 * @author CH
 * @since 2026-04-23
 */
public class ScrfdFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    /** 输入尺寸 */
    /** Input_size */
    private static final int INPUT_SIZE = 640;
    /** 步长数组 */
    /** Strides */
    private static final int[] STRIDES = {8, 16, 32};
    /** 锚框数量 */
    /** Num_anchors */
    private static final int NUM_ANCHORS = 2;
    /** 分数阈值 */
    /** Score_threshold */
    private static final float SCORE_THRESHOLD = 0.45f;
    /** NMS 阈值 */
    /** Nms_threshold */
    private static final double NMS_THRESHOLD = 0.40d;

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        var src = (BufferedImage) input.getWrappedImage();
        var resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        var rgb = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        var data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (var i = 0; i < rgb.length; i++) {
            var p = rgb[i];
            data[i] = ((p >> 16) & 0xff - 127.5f) / 128f;
            data[i + INPUT_SIZE * INPUT_SIZE] = ((p >> 8) & 0xff - 127.5f) / 128f;
            data[i + 2 * INPUT_SIZE * INPUT_SIZE] = ((p & 0xff) - 127.5f) / 128f;
        }
        var array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 9) return empty();
        var candidates = new ArrayList<Candidate>();
        for (var i = 0; i < STRIDES.length; i++) {
            decodeStride(candidates, squeezeBatch(list.get(i)), squeezeBatch(list.get(i + STRIDES.length)), squeezeBatch(list.get(i + STRIDES.length * 2)), STRIDES[i]);
        }
        candidates.sort((l, r) -> Double.compare(r.score(), l.score()));
        var names = new ArrayList<String>();
        var probs = new ArrayList<Double>();
        var boxes = new ArrayList<BoundingBox>();
        for (var c : candidates) {
            var keep = true;
            for (var b : boxes) if (b.getIoU(c.rectangle()) > NMS_THRESHOLD) { keep = false; break; }
            if (!keep) continue;
            names.add("face"); probs.add(c.score()); boxes.add(c.landmark());
        }
        return new DetectedObjects(names, probs, boxes);
    }

    /** 解码Stride */
    private void decodeStride(List<Candidate> candidates, NDArray scoreArray, NDArray bboxArray, NDArray kpsArray, int stride) {
        var scores = scoreArray.toFloatArray();
        var boxes = bboxArray.toFloatArray();
        var kps = kpsArray == null ? null : kpsArray.toFloatArray();
        var featureSize = INPUT_SIZE / stride;
        var totalAnchors = featureSize * featureSize * NUM_ANCHORS;
        var scoreLength = Math.min(totalAnchors, scores.length);
        var boxLength = Math.min(totalAnchors, boxes.length / 4);
        var kpsLength = kps == null ? 0 : Math.min(totalAnchors, kps.length / 10);
        var limit = Math.min(scoreLength, boxLength);
        for (var idx = 0; idx < limit; idx++) {
            var score = scores[idx];
            if (score < SCORE_THRESHOLD) continue;
            var location = idx / NUM_ANCHORS;
            var y = location / featureSize; var x = location % featureSize;
            var l = boxes[idx * 4] * stride, t = boxes[idx * 4 + 1] * stride, r = boxes[idx * 4 + 2] * stride, b = boxes[idx * 4 + 3] * stride;
            var cx = x * stride + stride * 0.5f, cy = y * stride + stride * 0.5f;
            var x1 = clamp(cx - l, 0f, INPUT_SIZE - 1f), y1 = clamp(cy - t, 0f, INPUT_SIZE - 1f);
            var x2 = clamp(cx + r, 0f, INPUT_SIZE - 1f), y2 = clamp(cy + b, 0f, INPUT_SIZE - 1f);
            if (x2 <= x1 || y2 <= y1) continue;
            var points = new ArrayList<Point>();
            if (kps != null && idx < kpsLength) for (var p = 0; p < 5; p++) points.add(new Point(clamp(cx + kps[idx * 10 + p * 2] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE, clamp(cy + kps[idx * 10 + p * 2 + 1] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE));
            var nX1 = x1 / INPUT_SIZE; var nY1 = y1 / INPUT_SIZE; var nW = (x2 - x1) / INPUT_SIZE; var nH = (y2 - y1) / INPUT_SIZE;
            candidates.add(new Candidate(new Landmark(nX1, nY1, nW, nH, points), score));
        }
    }

    /** SqueezeBatch */
    private NDArray squeezeBatch(NDArray array) {
        return array;
    }

    /** Clamp */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Empty */
    private DetectedObjects empty() {
        return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() { return null; }

    /** Candidate */
    private record Candidate(Landmark landmark, double score) {
        private Rectangle rectangle() { return landmark; }
    }
}
