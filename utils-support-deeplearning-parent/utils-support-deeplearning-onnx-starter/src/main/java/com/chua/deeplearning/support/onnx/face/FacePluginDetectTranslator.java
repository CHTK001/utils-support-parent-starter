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
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
 * faceplugin SSD face detection Translator.
 *
 * <p>Model: MobileNet Tiny SSD (slim or RFB variant)
 * 输入:  1x3x320x240 RGB, normalized by (pixel - 127) / 128
 * 输出: 信心 [N, 2] (bg, face), 位置 [N, 4]
 *
 * <p>后处理：用先验框（prior anchors）从 SSD 原始输出中解码出人脸框，
 * 再按置信度阈值过滤并执行 NMS。
 *
 * <p>Prior anchors (image 320x240, computed like Python box_utils.generate_priors):
 * 特征 映射 大小 width  [40, 20, 10, 5], height [30, 15, 8, 4]
 * 最小 boxes: [[10, 16, 24], [32, 48], [64, 96], [128, 192, 256]]
 *
 * @author CH
 * @since 2026-08-08
 */
public class FacePluginDetectTranslator implements Translator<Image, DetectedObjects> {

    /** 输入宽度 */
    /** 输入_width */
    private static final int INPUT_WIDTH = 320;
    /** 输入高度 */
    /** 输入_height */
    private static final int INPUT_HEIGHT = 240;
    /** 置信度阈值 */
    /** 信心_阈值 */
    private static final float CONFIDENCE_THRESHOLD = 0.6f;
    /** NMS 阈值 */
    /** Nms_阈值 */
    private static final double NMS_THRESHOLD = 0.3d;
    /** 最大候选数量 */
    /** 最大_candidates */
    private static final int MAX_CANDIDATES = 1500;
    /** 中心点方差 */
    /** Center_variance */
    private static final float CENTER_VARIANCE = 0.1f;
    /** 尺寸方差 */
    /** 大小_variance */
    private static final float SIZE_VARIANCE = 0.2f;
    /** 图像均值 */
    /** 镜像_mean */
    private static final float IMAGE_MEAN = 127.0f;
    /** 图像标准差 */
    /** 镜像_std */
    private static final float IMAGE_STD = 128.0f;

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

        /**
         * 创建 Translator（支持外部阈值覆盖）。
         *
         * @param configuration 检测配置（可空）
         */
    public FacePluginDetectTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this();
        if (null != configuration) {
            float t = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0) {
                this.thresholdOverride = t;
            }
        }
    }

/** 创建 faceplugindetecttranslator 实例 */
    public FacePluginDetectTranslator() {
    }

    @Override
    /** 处理输入 */
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
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 2) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

 // 直接读取 ONNX 引擎 ndarray 的 flat float[] 数据，避免不支持的 squeeze/transpose
        // 输出形状固定为 [1, num_priors, 2] 和 [1, num_priors, 4]
        float[] confData = list.getFirst().toFloatArray();
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
            if (faceScore < effThreshold(CONFIDENCE_THRESHOLD)) {
                continue;
            }

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

            if (x2 <= x1 || y2 <= y1) {
                continue;
            }

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
            if (!keep) {
                continue;
            }
            names.add("face");
            probs.add((double) c.score);
            boxes.add(c.rect);
        }

        return new DetectedObjects(names, probs, boxes);
    }

    /**
     * Clip
     *
     * @param v v
     * @return clip的结果
     */
    private float clip(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Generate 默认 SSD priors for 输入 320x240.
     * Mirrors box_工具.generate_priors(特征_映射_w_h_列表, shrinkage_列表, 镜像_大小, 最小_boxes).
     * @return generatePriors的结果
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
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
 // 输入已包含 批量 维（shape [1, C, H, W]），无需 batchifier 再次叠加
        return null;
    }

    /**
     * Candidate
     *
     * @param rect rect
     * @param score score
     * @return Candidate的结果
     */
    private record Candidate(Rectangle rect, float score) {
    }
}
