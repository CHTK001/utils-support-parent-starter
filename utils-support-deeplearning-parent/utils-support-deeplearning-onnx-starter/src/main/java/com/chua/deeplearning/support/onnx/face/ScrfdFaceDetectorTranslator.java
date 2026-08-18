package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;

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

    private static final int INPUT_SIZE = 640;
    private static final int[] STRIDES = {8, 16, 32};
    private static final int NUM_ANCHORS = 2;
    private static final float SCORE_THRESHOLD = 0.45f;
    private static final double NMS_THRESHOLD = 0.40d;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 纯 Java 预处理（BufferedImage resize + RGB 归一化），避免 ONNX NDArray 不支持的算术/图像操作
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = OpenCvImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        int[] rgb = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (int i = 0; i < rgb.length; i++) {
            int pixel = rgb[i];
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            // SCRFD: (rgb - 127.5) / 128，CHW
            data[i] = (r - 127.5f) / 128f;
            data[i + INPUT_SIZE * INPUT_SIZE] = (g - 127.5f) / 128f;
            data[i + 2 * INPUT_SIZE * INPUT_SIZE] = (b - 127.5f) / 128f;
        }

        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 9) {
            return empty();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < STRIDES.length; i++) {
            NDArray scoreArray = squeezeBatch(list.get(i));
            NDArray bboxArray = squeezeBatch(list.get(i + STRIDES.length));
            NDArray kpsArray = squeezeBatch(list.get(i + STRIDES.length * 2));
            decodeStride(candidates, scoreArray, bboxArray, kpsArray, STRIDES[i]);
        }

        candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
        List<String> names = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean keep = true;
            for (BoundingBox existing : boxes) {
                if (existing.getIoU(candidate.rectangle()) > NMS_THRESHOLD) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            names.add("face");
            probabilities.add(candidate.score());
            boxes.add(candidate.landmark());
        }
        return new DetectedObjects(names, probabilities, boxes);
    }

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
            if (score < SCORE_THRESHOLD) {
                continue;
            }
            int location = idx / NUM_ANCHORS;
            int y = location / featureSize;
            int x = location % featureSize;

            float left = boxes[idx * 4] * stride;
            float top = boxes[idx * 4 + 1] * stride;
            float right = boxes[idx * 4 + 2] * stride;
            float bottom = boxes[idx * 4 + 3] * stride;

            float centerX = x * stride + stride * 0.5f;
            float centerY = y * stride + stride * 0.5f;
            float x1 = clamp(centerX - left, 0f, INPUT_SIZE - 1f);
            float y1 = clamp(centerY - top, 0f, INPUT_SIZE - 1f);
            float x2 = clamp(centerX + right, 0f, INPUT_SIZE - 1f);
            float y2 = clamp(centerY + bottom, 0f, INPUT_SIZE - 1f);
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }

            // 5 关键点（kps：每点 dx,dy，相对 anchor 中心，10 维）
            List<Point> points = new ArrayList<>();
            if (kps != null && idx < kpsLength) {
                for (int p = 0; p < 5; p++) {
                    float px = clamp(centerX + kps[idx * 10 + p * 2] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE;
                    float py = clamp(centerY + kps[idx * 10 + p * 2 + 1] * stride, 0f, INPUT_SIZE - 1f) / INPUT_SIZE;
                    points.add(new Point(px, py));
                }
            }

            float nX1 = x1 / INPUT_SIZE, nY1 = y1 / INPUT_SIZE;
            float nW = (x2 - x1) / INPUT_SIZE, nH = (y2 - y1) / INPUT_SIZE;
            Landmark landmark = new Landmark(nX1, nY1, nW, nH, points);
            candidates.add(new Candidate(landmark, score));
        }
    }

    private NDArray squeezeBatch(NDArray array) {
        // ONNX 引擎无 alternative NDArray 引擎，NDArrayAdapter.squeeze 会无限递归。
        // decodeStride 通过 toFloatArray() 读取线性数据，形状不影响结果，故跳过 squeeze。
        return array;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private DetectedObjects empty() {
        return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    private record Candidate(Landmark landmark, double score) {

        private Rectangle rectangle() {
            return landmark;
        }
    }
}
