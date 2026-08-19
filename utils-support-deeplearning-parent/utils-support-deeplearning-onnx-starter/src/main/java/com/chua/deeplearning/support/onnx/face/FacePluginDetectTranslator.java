package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * FacePlugin SSD face detection Translator.
 *
 * <p>Model: MobileNet Tiny SSD (slim or RFB variant)
 * Input:  1x3x320x240 RGB, normalized by (pixel - 127) / 128
 * Output: confidences [N, 2] (bg, face), locations [N, 4]
 *
 * <p>Post-processing: decode boxes from raw SSD outputs using prior anchors,
 * apply confidence threshold and NMS.
 *
 * <p>Prior anchors (image 320x240, computed like Python box_utils.generate_priors):
 * feature map sizes width  [40, 20, 10, 5], height [30, 15, 8, 4]
 * min boxes: [[10, 16, 24], [32, 48], [64, 96], [128, 192, 256]]
 *
 * @author CH
 * @since 2026-08-08
 */
public class FacePluginDetectTranslator implements Translator<Image, DetectedObjects> {

    /** 输入宽度 */
    private static final int INPUT_WIDTH = 320;
    /** 输入高度 */
    private static final int INPUT_HEIGHT = 240;
    /** 置信度阈值 */
    private static final float CONFIDENCE_THRESHOLD = 0.6f;
    /** NMS 阈值 */
    private static final double NMS_THRESHOLD = 0.3d;
    /** 最大候选数量 */
    private static final int MAX_CANDIDATES = 1500;
    /** 中心点方差 */
    private static final float CENTER_VARIANCE = 0.1f;
    /** 尺寸方差 */
    private static final float SIZE_VARIANCE = 0.2f;
    /** 图像均值 */
    private static final float IMAGE_MEAN = 127.0f;
    /** 图像标准差 */
    private static final float IMAGE_STD = 128.0f;

    public FacePluginDetectTranslator() {
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        if (input.getHeight() != INPUT_HEIGHT || input.getWidth() != INPUT_WIDTH) {
            input = input.resize(INPUT_WIDTH, INPUT_HEIGHT, false);
        }
        // 正确路径：HWC->CHW + 归一化（避开 ONNX 不支持的 transpose/sub/div）
        return OnnxImageProcessor.toModelInput(
                input, INPUT_WIDTH, INPUT_HEIGHT,
                3, false, IMAGE_MEAN, 1.0f / IMAGE_STD,
                ctx.getNDManager());
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 2) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        // 直接读取 ONNX 引擎 NDArray 的 flat float[] 数据，避免不支持的 squeeze/transpose
        // 输出形状固定为 [1, num_priors, 2] 和 [1, num_priors, 4]
        float[] confData = list.get(0).toFloatArray();
        float[] locData = list.get(1).toFloatArray();

        // 推断 num_priors（confData 长度 = 2 * num_priors）
        int numPriors = confData.length / 2;

        float[] priors = generatePriors();

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < numPriors && candidates.size() < MAX_CANDIDATES; i++) {
            // 模型输出原始 logits，对每个 prior 的两个类别做 softmax 后取 face 概率
            float bg = confData[i * 2 + 0];
            float fg = confData[i * 2 + 1];
            float maxVal = Math.max(bg, fg);
            float expBg = (float) Math.exp(bg - maxVal);
            float expFg = (float) Math.exp(fg - maxVal);
            float faceScore = expFg / (expBg + expFg);
            if (faceScore < CONFIDENCE_THRESHOLD) continue;

            float priorCx = priors[i * 4 + 0];
            float priorCy = priors[i * 4 + 1];
            float priorW  = priors[i * 4 + 2];
            float priorH  = priors[i * 4 + 3];

            float cx = priorCx + locData[i * 4 + 0] * CENTER_VARIANCE * priorW;
            float cy = priorCy + locData[i * 4 + 1] * CENTER_VARIANCE * priorH;
            float w  = priorW * (float) Math.exp(locData[i * 4 + 2] * SIZE_VARIANCE);
            float h  = priorH * (float) Math.exp(locData[i * 4 + 3] * SIZE_VARIANCE);

            float x1 = clip(cx - w / 2);
            float y1 = clip(cy - h / 2);
            float x2 = clip(cx + w / 2);
            float y2 = clip(cy + h / 2);

            if (x2 <= x1 || y2 <= y1) continue;

            candidates.add(new Candidate(
                new Rectangle(x1, y1, x2 - x1, y2 - y1), faceScore));
        }

        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (Candidate c : candidates) {
            boolean keep = true;
            for (BoundingBox existing : boxes) {
                if (existing.getIoU(c.rect) > NMS_THRESHOLD) {
                    keep = false;
                    break;
                }
            }
            if (!keep) continue;
            names.add("face");
            probs.add((double) c.score);
            boxes.add(c.rect);
        }

        return new DetectedObjects(names, probs, boxes);
    }

    private float clip(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Generate default SSD priors for input 320x240.
     * Mirrors box_utils.generate_priors(feature_map_w_h_list, shrinkage_list, image_size, min_boxes).
     */
    private float[] generatePriors() {
        int[] featureW = {40, 20, 10, 5};
        int[] featureH = {30, 15, 8, 4};
        int[] shrinkW = {8, 16, 32, 64};
        int[] shrinkH = {8, 16, 30, 60};
        int[][] minBoxes = {
            {10, 16, 24},
            {32, 48},
            {64, 96},
            {128, 192, 256}
        };

        List<Float> priors = new ArrayList<>();
        for (int level = 0; level < featureW.length; level++) {
            float scaleW = (float) INPUT_WIDTH / shrinkW[level];
            float scaleH = (float) INPUT_HEIGHT / shrinkH[level];
            for (int y = 0; y < featureH[level]; y++) {
                for (int x = 0; x < featureW[level]; x++) {
                    float cx = (x + 0.5f) / scaleW;
                    float cy = (y + 0.5f) / scaleH;
                    for (int box : minBoxes[level]) {
                        float w = (float) box / INPUT_WIDTH;
                        float h = (float) box / INPUT_HEIGHT;
                        priors.add(cx);
                        priors.add(cy);
                        priors.add(w);
                        priors.add(h);
                    }
                }
            }
        }

        float[] result = new float[priors.size()];
        for (int i = 0; i < priors.size(); i++) {
            result[i] = priors.get(i);
        }
        return result;
    }

@Override
    public Batchifier getBatchifier() {
        // 输入已包含 batch 维（shape [1, C, H, W]），无需 batchifier 再次叠加
        return null;
    }

    private record Candidate(Rectangle rect, float score) {
    }
}