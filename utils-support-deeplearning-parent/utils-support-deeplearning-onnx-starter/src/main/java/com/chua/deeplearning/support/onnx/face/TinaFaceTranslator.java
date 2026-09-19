package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
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
import java.util.Map;

/**
 * tinaface        ONNX Translator
 *
 * <p>模型输出 18 个 flat tensor：6 层（P2~P7）× 3 输出（cls/reg/iou）：
 * [cls_2, reg_2, iou_2, cls_3, reg_3, iou_3, cls_4, reg_4, iou_4,
 *  cls_5, reg_5, iou_5, cls_6, reg_6, iou_6, cls_7, reg_7, iou_7]，
 * P2~P7 对应 stride 4/8/16/32/64/128。</p>
 *
 * <p>每像素 3 个 anchor（scales_per_octave=3，ratios=[1.3]），1 类（人脸）。
 * cls 通道 3、reg 通道 12、iou 通道 3。iou 感知得分 = sqrt(sigmoid(cls) × sigmoid(iou))。</p>
 *
 * @author CH
 * @since 2026-08-20
 */
public class TinaFaceTranslator implements Translator<Image, DetectedObjects> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 640;
    /** 层步长 */
    /** Strides */
    private static final int[] STRIDES = {4, 8, 16, 32, 64, 128};
    /** 每像素锚框数量 */
    /** Num_锚栓 */
    private static final int NUM_ANCHORS = 3;
    /** 每个 octave 的缩放数 */
    /** Scales_per_octave */
    private static final float SCALE_PER_OCTAVE = (float) Math.pow(2.0d, 4.0d / 3.0d);
    /** 宽高比 */
    /** Ratio */
    private static final float RATIO = 1.3f;
    /** 均值 */
    /** Mean */
    private static final float[] RGB_MEAN = {123.675f, 116.28f, 103.53f};
    /** 解码（缩放标准差分归一到 [0,1] 前的坐标域） */
    private static final double[] TARGET_STDS = {0.1d, 0.1d, 0.2d, 0.2d};

    /** 置信度阈值 */
    /** Confthresh */
    private double confThresh = 0.4d;
    /** NMS 阈值 */
    /** Nms_thresh */
    private double nmsThresh = 0.45d;

    /**
     * tinafacetranslator。
     */
    public TinaFaceTranslator() {
    }

    /**
     * 创建 tinafacetranslator 实例
     * @param arguments 参数（阈值 默认 0.4，nms 默认 0.45）
     */
    public TinaFaceTranslator(Map<String, ?> arguments) {
        if (arguments != null) {
            Object threshold = arguments.get("threshold");
            if (threshold instanceof Number) {
                confThresh = ((Number) threshold).doubleValue();
            }
            Object nms = arguments.get("nms");
            if (nms instanceof Number) {
                nmsThresh = ((Number) nms).doubleValue();
            }
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        var src = (BufferedImage) input.getWrappedImage();
        var resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        var rgb = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        var total = INPUT_SIZE * INPUT_SIZE;
        var data = new float[3 * total];
        for (var i = 0; i < rgb.length; i++) {
            var pixel = rgb[i];
            var r = (pixel >> 16) & 0xff;
            var g = (pixel >> 8) & 0xff;
            var b = pixel & 0xff;
            data[i] = r - RGB_MEAN[0];
            data[i + total] = g - RGB_MEAN[1];
            data[i + 2 * total] = b - RGB_MEAN[2];
        }
        var array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < STRIDES.length * 3) {
            return empty();
        }
        var candidates = new ArrayList<Candidate>();
        for (var level = 0; level < STRIDES.length; level++) {
            decodeLevel(candidates, list.get(level * 3), list.get(level * 3 + 1), list.get(level * 3 + 2), STRIDES[level]);
        }
        candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
        var names = new ArrayList<String>();
        var probabilities = new ArrayList<Double>();
        var boxes = new ArrayList<BoundingBox>();
        for (var candidate : candidates) {
            var keep = true;
            for (var existing : boxes) {
                if (existing.getIoU(candidate.rectangle()) > nmsThresh) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            names.add("face");
            probabilities.add(candidate.score());
            boxes.add(candidate.rectangle());
        }
        return new DetectedObjects(names, probabilities, boxes);
    }

    /**
     * 解码级别。
     *
     * @param candidates 方法入参 candidates
     * @param clsArray cls数组，不允许为 null
     * @param regArray reg数组，不允许为 null
     * @param iouArray iou数组，不允许为 null
     * @param stride 方法入参 stride
     */
    private void decodeLevel(List<Candidate> candidates, NDArray clsArray, NDArray regArray, NDArray iouArray, int stride) {
        var cls = clsArray.toFloatArray();
        var reg = regArray.toFloatArray();
        var iou = iouArray.toFloatArray();
        var featureSize = INPUT_SIZE / stride;
        var spatial = featureSize * featureSize;
        var limit = Math.min(spatial, Math.min(Math.min(cls.length, reg.length / 4), iou.length));
        var sqrtRatio = (float) Math.sqrt(RATIO);
        var anchorSizes = new float[]{stride, stride * SCALE_PER_OCTAVE, stride * SCALE_PER_OCTAVE * SCALE_PER_OCTAVE};
        for (var y = 0; y < featureSize; y++) {
            for (var x = 0; x < featureSize; x++) {
                var baseIdx = y * featureSize + x;
                if (baseIdx >= limit) {
                    continue;
                }
                var cx = x * stride + stride * 0.5f;
                var cy = y * stride + stride * 0.5f;
                for (var anchor = 0; anchor < NUM_ANCHORS; anchor++) {
                    var clsScore = sigmoid(cls[anchor * spatial + baseIdx]);
                    var iouScore = sigmoid(iou[anchor * spatial + baseIdx]);
                    var score = Math.sqrt(clsScore * iouScore);
                    if (score < confThresh) {
                        continue;
                    }
                    var aw = anchorSizes[anchor] * sqrtRatio;
                    var ah = anchorSizes[anchor] / sqrtRatio;
                    var dx = reg[anchor * 4 * spatial + baseIdx];
                    var dy = reg[(anchor * 4 + 1) * spatial + baseIdx];
                    var dw = reg[(anchor * 4 + 2) * spatial + baseIdx];
                    var dh = reg[(anchor * 4 + 3) * spatial + baseIdx];
                    var px = dx * TARGET_STDS[0] * aw + cx;
                    var py = dy * TARGET_STDS[1] * ah + cy;
                    var pw = aw * Math.exp(dw * TARGET_STDS[2]);
                    var ph = ah * Math.exp(dh * TARGET_STDS[3]);
                    var x1 = px - pw * 0.5d;
                    var y1 = py - ph * 0.5d;
                    var x2 = px + pw * 0.5d;
                    var y2 = py + ph * 0.5d;
                    if (x2 <= x1 || y2 <= y1) {
                        continue;
                    }
                    var nX1 = clip(x1 / INPUT_SIZE);
                    var nY1 = clip(y1 / INPUT_SIZE);
                    var nX2 = clip(x2 / INPUT_SIZE);
                    var nY2 = clip(y2 / INPUT_SIZE);
                    if (nX2 <= nX1 || nY2 <= nY1) {
                        continue;
                    }
                    var rectangle = new Rectangle(nX1, nY1, nX2 - nX1, nY2 - nY1);
                    candidates.add(new Candidate(rectangle, score));
                }
            }
        }
    }

    /**
     * Sigmoid
     *
     * @param value 值
     * @return sigmoid的结果
     */
    private static double sigmoid(float value) {
        return 1.0d / (1.0d + Math.exp(-value));
    }

    /**
     * Clip
     *
     * @param value 值
     * @return clip的结果
     */
    private double clip(double value) {
        return Math.max(0d, Math.min(1d, value));
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
    public Batchifier getBatchifier() {
 // ONNX Runtime 的 ndarray 不支持 Stack，单图推理不批处理
        return null;
    }

    /**
     * Candidate
     *
     * @param rectangle rectangle
     * @param score score
     * @return Candidate的结果
     */
    private record Candidate(Rectangle rectangle, double score) {
    }
}
