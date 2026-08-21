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
    private int imageWidth;
    private int imageHeight;
    private float scaleR = 1f;
    private int padLeft;
    private int padTop;

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        float r = Math.min(INPUT_SIZE / (float) imageWidth, INPUT_SIZE / (float) imageHeight);
        int newW = Math.round(imageWidth * r);
        int newH = Math.round(imageHeight * r);
        scaleR = r;
        padLeft = (INPUT_SIZE - newW) / 2;
        padTop = (INPUT_SIZE - newH) / 2;
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        scaled.getGraphics().drawImage(src, 0, 0, newW, newH, null);
        int[] pixels = scaled.getRGB(0, 0, newW, newH, null, 0, newW);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        java.util.Arrays.fill(data, (114f - 127.5f) / 128f);
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int p = pixels[y * newW + x];
                int idx = (y + padTop) * INPUT_SIZE + (x + padLeft);
                data[idx] = (((p >> 16) & 0xff) - 127.5f) / 128f;
                data[idx + INPUT_SIZE * INPUT_SIZE] = (((p >> 8) & 0xff) - 127.5f) / 128f;
                data[idx + 2 * INPUT_SIZE * INPUT_SIZE] = ((p & 0xff) - 127.5f) / 128f;
            }
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /** 处理Output */
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

    /** 解码Stride */
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

            // 5 关键点（kps：每点 dx,dy，相对 anchor 中心，10 维）— letterbox 逆变换
            List<Point> points = new ArrayList<>();
            if (kps != null && idx < kpsLength) {
                for (int p = 0; p < 5; p++) {
                    float lbX = clamp(centerX + kps[idx * 10 + p * 2] * stride, 0f, INPUT_SIZE - 1f);
                    float lbY = clamp(centerY + kps[idx * 10 + p * 2 + 1] * stride, 0f, INPUT_SIZE - 1f);
                    float px = (lbX - padLeft) / scaleR / imageWidth;
                    float py = (lbY - padTop) / scaleR / imageHeight;
                    px = Math.max(0f, Math.min(1f, px));
                    py = Math.max(0f, Math.min(1f, py));
                    points.add(new Point(px, py));
                }
            }

            float lbX1 = (x1 - padLeft) / scaleR, lbY1 = (y1 - padTop) / scaleR;
            float lbX2 = (x2 - padLeft) / scaleR, lbY2 = (y2 - padTop) / scaleR;
            lbX1 = clamp(lbX1, 0f, imageWidth); lbY1 = clamp(lbY1, 0f, imageHeight);
            lbX2 = clamp(lbX2, 0f, imageWidth); lbY2 = clamp(lbY2, 0f, imageHeight);
            if (lbX2 <= lbX1 || lbY2 <= lbY1) {
                continue;
            }
            float nX1 = lbX1 / imageWidth, nY1 = lbY1 / imageHeight;
            float nW = (lbX2 - lbX1) / imageWidth, nH = (lbY2 - lbY1) / imageHeight;
            Landmark landmark = new Landmark(nX1, nY1, nW, nH, points);
            candidates.add(new Candidate(landmark, score));
        }
    }

    /** SqueezeBatch */
    private NDArray squeezeBatch(NDArray array) {
        // ONNX 引擎无 alternative NDArray 引擎，NDArrayAdapter.squeeze 会无限递归。
        // decodeStride 通过 toFloatArray() 读取线性数据，形状不影响结果，故跳过 squeeze。
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
    public Batchifier getBatchifier() {
        return null;
    }

    /** Candidate */
    private record Candidate(Landmark landmark, double score) {

        /** Rectangle */
        private Rectangle rectangle() {
            return landmark;
        }
    }
}
