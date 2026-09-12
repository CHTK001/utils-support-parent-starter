package com.chua.deeplearning.support.onnx.yolo.v11.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NMSUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
   * yolov11 车牌检测 Translator（morsetechlab/yolov11-执照-铭牌-detection）。
 *
 * <p>模型 {@code vision/detection/yolo11_plate/yolo11_plate_detect.onnx} 内嵌于本模块
 * resources。输入 {@code [1,3,640,640]}（letterbox + 归一化），输出 {@code [1,5,8400]}：
   * 每列 = cx, cy, w, h, conf（单类 执照-铭牌）。输出坐标为归一化值，
 * 由 {@code DjlModelTranslator} 统一乘图像尺寸转像素。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Yolo11PlateDetectTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 输入边长。
     */
    private static final int INPUT_SIZE = 640;

    /**
     * 默认置信度阈值。
     */
    private static final float DEFAULT_CONF_THRESHOLD = 0.25f;

    /**
     * 默认 IOU 阈值。
     */
    private static final float DEFAULT_IOU_THRESHOLD = 0.45f;

    /**
      * 置信度阈值（可通过 detection配置.系统期权("阈值") 覆盖）。
     */
    private final float confThreshold;

    /**
      * IOU 阈值（可通过 detection配置.系统期权("iou阈值") 覆盖）。
     */
    private final float iouThreshold;

    /**
     * 类别名。
     */
    private static final String CLASS_NAME = "license-plate";

    /**
     * 源图宽。
     */
    private int srcW;

    /**
     * 源图高。
     */
    private int srcH;

    /**
     * letterbox 缩放比例。
     */
    private float scale;

    /**
     * letterbox 水平填充。
     */
    private int padX;

    /**
     * letterbox 垂直填充。
     */
    private int padY;

    /**
     * yolo11铭牌detecttranslator。
     */
    public Yolo11PlateDetectTranslator() {
        this(null);
    }

    /**
     * 创建 Translator（支持运行参数覆盖阈值）。
     *
     * <p>支持的键：{@code threshold}（置信度，默认 0.25）、{@code iouThreshold}（默认 0.45），
     * 未提供的键使用内置准确默认值。</p>
     *
     * @param configuration 检测配置（可空）
     */
    public Yolo11PlateDetectTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this.confThreshold = configuration == null ? DEFAULT_CONF_THRESHOLD
                : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD,
                        DEFAULT_CONF_THRESHOLD);
        this.iouThreshold = configuration == null ? DEFAULT_IOU_THRESHOLD
                : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_IOU_THRESHOLD,
                        DEFAULT_IOU_THRESHOLD);
    }


    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        srcW = input.getWidth();
        srcH = input.getHeight();
        scale = Math.min(INPUT_SIZE / (float) srcW, INPUT_SIZE / (float) srcH);
        int newW = Math.round(srcW * scale);
        int newH = Math.round(srcH * scale);
        padX = (INPUT_SIZE - newW) / 2;
        padY = (INPUT_SIZE - newH) / 2;

        // letterbox：灰底画布 + 等比缩放居中绘制（保持纵横比）
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage canvas = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
        g.drawImage(src, padX, padY, newW, newH, null);
        g.dispose();

        Image letterboxed = new ai.djl.modality.cv.BufferedImageFactory().fromImage(canvas);
        NDArray hwc = letterboxed.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        return new NDList(toNormalizedChw(ctx.getNDManager(), hwc));
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        NDArray output = list.get(0);
        long f0 = output.getShape().get(1);
        long f1 = output.getShape().get(2);
        boolean transposed = f0 < f1;
        long numBoxes = transposed ? f1 : f0;
        float[] data = output.toType(DataType.FLOAT32, false).toFloatArray();
        int features = (int) (transposed ? f0 : f1);

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < numBoxes; i++) {
            float cx = val(data, i, 0, transposed, numBoxes, features);
            float cy = val(data, i, 1, transposed, numBoxes, features);
            float w = val(data, i, 2, transposed, numBoxes, features);
            float h = val(data, i, 3, transposed, numBoxes, features);
            float conf = val(data, i, 4, transposed, numBoxes, features);
            if (conf < confThreshold) {
                continue;
            }
            // letterbox 坐标 → 原图像素 → 归一化（DJL Rectangle 使用 0~1）
            cx = (cx - padX) / scale;
            cy = (cy - padY) / scale;
            w = w / scale;
            h = h / scale;
            double x1 = Math.max(0, cx - w / 2);
            double y1 = Math.max(0, cy - h / 2);
            double bw = Math.min(srcW, cx + w / 2) - x1;
            double bh = Math.min(srcH, cy + h / 2) - y1;
            boxes.add(new Rectangle(x1 / srcW, y1 / srcH, Math.max(0, bw) / srcW, Math.max(0, bh) / srcH));
            names.add(CLASS_NAME);
            probs.add((double) conf);
        }

        List<Integer> keep = NMSUtils.nms(new ArrayList<>(boxes), probs, iouThreshold);
        List<String> finalNames = new ArrayList<>(keep.size());
        List<Double> finalProbs = new ArrayList<>(keep.size());
        List<BoundingBox> finalBoxes = new ArrayList<>(keep.size());
        for (int idx : keep) {
            finalNames.add(names.get(idx));
            finalProbs.add(probs.get(idx));
            finalBoxes.add(boxes.get(idx));
        }
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
      * 读取第 boxidx 个框的第 特征idx 个特征（自动适配行/列主序）。
     */
    private static float val(float[] data, int boxIdx, int featureIdx,
                             boolean transposed, long numBoxes, int features) {
        return transposed ? data[featureIdx * (int) numBoxes + boxIdx]
                : data[boxIdx * features + featureIdx];
    }

    /**
     * HWC uint8 RGB → CHW float32 /255。
     * @param manager 管理器
     * @param hwc hwc
     * @return 转为normalizedchw的结果
     */
    private static NDArray toNormalizedChw(NDManager manager, NDArray hwc) {
        Shape shape = hwc.getShape();
        int height = (int) shape.get(0);
        int width = (int) shape.get(1);
        int channels = (int) shape.get(2);
        float[] source = hwc.toType(DataType.FLOAT32, false).toFloatArray();
        float[] chw = new float[source.length];
        int planeSize = height * width;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int hwOffset = y * width + x;
                int srcOffset = hwOffset * channels;
                for (int c = 0; c < channels; c++) {
                    chw[c * planeSize + hwOffset] = source[srcOffset + c] / 255.0f;
                }
            }
        }
        return manager.create(chw, new Shape(1, channels, height, width));
    }
}
