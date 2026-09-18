package com.chua.deeplearning.support.pytorch.face.detector;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.List;

/**
* ultraface 人脸检测 Translator（pytorch torchscript）。
*
* @author CH
* @since 4.0.0.42
 */
public class UltraFaceTranslator implements Translator<Image, DetectedObjects> {

    /**
    * 默认方差。
    */
    private static final double[] DEFAULT_VARIANCE = {0.1d, 0.2d};

    /**
    * BGR 均值。
    */
    private static final float[] BGR_MEAN = {104f, 117f, 123f};

    /**
    * 置信度阈值。
    */
    private final double confThresh;

    /**
    * NMS 阈值。
    */
    private final double nmsThresh;

    /**
    * Top-K。
    */
    private final int topK;

    /**
    * 输入宽。
    */
    private final int inputWidth;

    /**
    * 输入高。
    */
    private final int inputHeight;

    /**
    * 方差。
    */
    private final double[] variance;

    /**
    * 锚框尺度。
    */
    private final int[][] scales;

    /**
    * 特征步长。
    */
    private final int[] steps;

    /** 创建 ultrafacetranslator 实例 */
    public UltraFaceTranslator() {
        this(0.7d, 0.3d);
    }

    /**
    * 创建 ultrafacetranslator 实例
    * @param confThresh confthresh
    * @param confThresh double
    * @param nmsThresh nmsthresh
    */
    public UltraFaceTranslator(double confThresh, double nmsThresh) {
        this.confThresh = confThresh;
        this.nmsThresh = nmsThresh;
        this.topK = 200;
        this.inputWidth = 320;
        this.inputHeight = 240;
        this.variance = DEFAULT_VARIANCE;
        this.scales = new int[][]{{10, 16, 24}, {32, 48}, {64, 96}, {128, 192, 256}};
        this.steps = new int[]{8, 16, 32, 64};
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        long height = array.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        long width = array.getShape().get(1);
        if (height != inputHeight || width != inputWidth) {
            array = NDImageUtils.resize(array, inputWidth, inputHeight);
        }
        array = array.transpose(2, 0, 1).flip(0);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        NDArray mean = ctx.getNDManager().create(BGR_MEAN).reshape(3, 1, 1);
        array = array.sub(mean);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 2) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        NDArray rawBoxes = squeezeBatch(list.getFirst());
        NDArray rawScores = squeezeBatch(list.get(1));
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        double[][] priors = boxRecover(inputWidth, inputHeight, scales, steps);
        float[] boxArray = rawBoxes.toFloatArray();
        float[] scoreArray = rawScores.toFloatArray();
        int candidateCount = (int) rawBoxes.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）

        for (int i = 0; i < candidateCount; i++) {
            double probability = scoreArray[i * 2 + 1];
            if (probability < confThresh) {
                continue;
            }
            double[] prior = priors[i];
            double x = boxArray[i * 4];
            double y = boxArray[i * 4 + 1];
            double w = boxArray[i * 4 + 2];
            double h = boxArray[i * 4 + 3];
            double decodedW = Math.exp(w * variance[1]) * prior[2];
            double decodedH = Math.exp(h * variance[1]) * prior[3];
            double decodedX = x * variance[0] * prior[2] + prior[0] - decodedW * 0.5d;
            double decodedY = y * variance[0] * prior[3] + prior[1] - decodedH * 0.5d;
            Rectangle rectangle = new Rectangle(
                    clip(decodedX),
                    clip(decodedY),
                    clipSize(decodedX, decodedW),
                    clipSize(decodedY, decodedH));
            if (rectangle.getWidth() <= 0d || rectangle.getHeight() <= 0d) {
                continue;
            }
            candidates.add(new Candidate(rectangle, probability));
        }

        candidates.sort((left, right) -> Double.compare(right.probability(), left.probability()));
        int limit = Math.min(candidates.size(), topK);
        for (int i = 0; i < limit; i++) {
            Candidate candidate = candidates.get(i);
            boolean keep = true;
            for (BoundingBox existing : boxes) {
                if (existing.getIoU(candidate.rectangle()) > nmsThresh) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            names.add("face");
            probs.add(candidate.probability());
            boxes.add(candidate.rectangle());
        }
        return new DetectedObjects(names, probs, boxes);
    }

    /**
    * squeezebatch
    *
    * @param array array
    * @return squeezeBatch的结果
    */
    private NDArray squeezeBatch(NDArray array) {
        if (array != null && array.getShape().dimension() == 3 && array.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            return array.squeeze(0);
        }
        return array;
    }

    /**
    * boxrecover
    *
    * @param width width
    * @param height height
    * @param scales scales
    * @param steps steps
    * @return boxRecover的结果
    */
    private double[][] boxRecover(int width, int height, int[][] scales, int[] steps) {
        List<double[]> defaultBoxes = new ArrayList<>();
        for (int index = 0; index < steps.length; index++) {
            int step = steps[index];
            int hRatio = (int) Math.ceil((double) height / step);
            int wRatio = (int) Math.ceil((double) width / step);
            for (int h = 0; h < hRatio; h++) {
                for (int w = 0; w < wRatio; w++) {
                    for (int scale : scales[index]) {
                        double skx = scale * 1.0d / width;
                        double sky = scale * 1.0d / height;
                        double cx = (w + 0.5d) * step / width;
                        double cy = (h + 0.5d) * step / height;
                        defaultBoxes.add(new double[]{cx, cy, skx, sky});
                    }
                }
            }
        }
        double[][] boxes = new double[defaultBoxes.size()][4];
        for (int i = 0; i < defaultBoxes.size(); i++) {
            boxes[i] = defaultBoxes.get(i);
        }
        return boxes;
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
    * Clip获取大小
    *
    * @param origin origin
    * @param size 大小
    * @return clip大小的结果
    */
    private double clipSize(double origin, double size) {
        return Math.max(0d, Math.min(1d - clip(origin), size));
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * Candidate
    *
    * @param rectangle rectangle
    * @param probability probability
    * @return Candidate的结果
    */
    private record Candidate(Rectangle rectangle, double probability) {
    }
}
