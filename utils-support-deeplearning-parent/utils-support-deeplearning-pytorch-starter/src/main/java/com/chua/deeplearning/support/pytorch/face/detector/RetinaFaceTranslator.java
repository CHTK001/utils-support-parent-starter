package com.chua.deeplearning.support.pytorch.face.detector;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.List;

/**
* retinaface 人脸检测 Translator（AIAS face_restoration_sdk 同款）。
*
* <p>PyTorch TorchScript 模型（retinaface_traced_model.pt），输出 3 个 tensor：
* loc（框回归）、conf（分类）、landms（5 关键点，10 维）。关键点顺序：
* 左眼、右眼、鼻、左嘴角、右嘴角，用于 5 点仿射对齐。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class RetinaFaceTranslator implements Translator<Image, DetectedObjects> {

    /**
    * topk。
     */
    private static final int TOP_K = 200;

    /**
    * 双眼最小距离（跳过误检）。
     */
    private static final double EYE_DIST_THRESHOLD = 5;

    /**
    * 置信度阈值。
     */
    private static final double CONF_THRESH = 0.85;

    /**
    * NMS 阈值。
     */
    private static final double NMS_THRESH = 0.45;

    /**
    * 框与关键点回归方差。
     */
    private static final double[] VARIANCE = {0.1, 0.2};

    /**
    * 每层 锚栓 尺寸。
     */
    private static final int[][] SCALES = {{16, 32}, {64, 128}, {256, 512}};

    /**
    * 每层步长。
     */
    private static final int[] STEPS = {8, 16, 32};

    /**
    * 输入宽。
     */
    private int width;

    /**
    * 输入高。
     */
    private int height;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (array.getShape().dimension() == 4) {
            array = array.get(new NDIndex(":, :, 0:3"));
        }
        array = array.transpose(2, 0, 1); // HWC -> CHW
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        NDArray mean = ctx.getNDManager().create(new float[]{104f, 117f, 123f}, new Shape(3, 1, 1));
        array = array.sub(mean);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDManager manager = ctx.getNDManager();
        double scaleXY = VARIANCE[0];
        double scaleWH = VARIANCE[1];

        NDArray boxRecover = boxRecover(manager, width, height, SCALES, STEPS);
        NDArray boundingBoxes = squeeze(list.getFirst());
        NDArray rawConf = squeeze(list.get(1));
        NDArray landms = squeeze(list.get(2));

        NDArray prob = rawConf.get(":, 1:");
        prob = NDArrays.stack(
                new NDList(
                        prob.argMax(1).toType(DataType.FLOAT32, false),
                        prob.max(new int[]{1})));

        NDArray bbWH = boundingBoxes.get(":, 2:").mul(scaleWH).exp().mul(boxRecover.get(":, 2:"));
        NDArray bbXY = boundingBoxes.get(":, :2")
                .mul(scaleXY)
                .mul(boxRecover.get(":, 2:"))
                .add(boxRecover.get(":, :2"))
                .sub(bbWH.mul(0.5));
        boundingBoxes = NDArrays.concat(new NDList(bbXY, bbWH), 1);

        landms = decodeLandm(landms, boxRecover, scaleXY);

        NDArray cutOff = prob.get(1).gt(CONF_THRESH);
        boundingBoxes = boundingBoxes.transpose().booleanMask(cutOff, 1).transpose();
        landms = landms.transpose().booleanMask(cutOff, 1).transpose();
        prob = prob.booleanMask(cutOff, 1);

        NDArray newProb = prob.get(1).reshape(1, prob.getShape().getShape()[1]);
        long[] order = newProb.argSort(1, false).get(":" + TOP_K).toLongArray();
        prob = prob.transpose();

        List<String> retNames = new ArrayList<>();
        List<Double> retProbs = new ArrayList<>();
        List<BoundingBox> retBB = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (long l : order) {
            long currMaxLoc = l;
            float[] classProb = prob.get(currMaxLoc).toFloatArray();
            double probability = classProb[1];

            double[] boxArr = boundingBoxes.get(currMaxLoc).toDoubleArray();
            double[] landmsArr = landms.get(currMaxLoc).toDoubleArray();
            Rectangle rect = new Rectangle(boxArr[0], boxArr[1], boxArr[2], boxArr[3]);
            boolean belowIoU = true;
            for (BoundingBox box : boxes) {
                double iou = getIoU(rect, box.getBounds());
                if (iou > NMS_THRESH) {
                    belowIoU = false;
                    break;
                }
            }
            if (belowIoU) {
                List<Point> keyPoints = new ArrayList<>();
                for (int j = 0; j < 5; j++) {
                    double x = landmsArr[j * 2];
                    double y = landmsArr[j * 2 + 1];
 // 输出归一化坐标（DJL detected对象 约定），由 adapt输出 统一乘图像尺寸转像素
                    keyPoints.add(new Point(x, y));
                }
                Point leftEye = keyPoints.getFirst();
                Point rightEye = keyPoints.get(1);
                double eyeDist = Math.sqrt(Math.pow(leftEye.getX() - rightEye.getX(), 2)
                        + Math.pow(leftEye.getY() - rightEye.getY(), 2));
                // 关键点归一化 [0,1]，像素级阈值需乘图宽；保留极小距离过滤（误检）
                if (eyeDist < EYE_DIST_THRESHOLD / Math.max(width, height)) {
                    continue;
                }
                Landmark landmark = new Landmark(boxArr[0], boxArr[1], boxArr[2], boxArr[3], keyPoints);
                boxes.add(landmark);
                retNames.add("face");
                retProbs.add(probability);
                retBB.add(landmark);
            }
        }
        return new DetectedObjects(retNames, retProbs, retBB);
    }

    /**
    * 计算默认框。
    * @param manager 管理器
    * @param width width
    * @param height height
    * @param scales scales
    * @param steps steps
    * @return boxRecover的结果
     */
    private NDArray boxRecover(NDManager manager, int width, int height, int[][] scales, int[] steps) {
        int[][] aspectRatio = new int[steps.length][2];
        for (int i = 0; i < steps.length; i++) {
            aspectRatio[i] = new int[]{
                    (int) Math.ceil((float) height / steps[i]),
                    (int) Math.ceil((float) width / steps[i])};
        }
        List<double[]> defaultBoxes = new ArrayList<>();
        for (int idx = 0; idx < steps.length; idx++) {
            int[] scale = scales[idx];
            for (int h = 0; h < aspectRatio[idx][0]; h++) {
                for (int w = 0; w < aspectRatio[idx][1]; w++) {
                    for (int i : scale) {
                        double skx = i * 1.0 / width;
                        double sky = i * 1.0 / height;
                        double cx = (w + 0.5) * steps[idx] / width;
                        double cy = (h + 0.5) * steps[idx] / height;
                        defaultBoxes.add(new double[]{cx, cy, skx, sky});
                    }
                }
            }
        }
        double[][] boxes = new double[defaultBoxes.size()][defaultBoxes.getFirst().length];
        for (int i = 0; i < defaultBoxes.size(); i++) {
            boxes[i] = defaultBoxes.get(i);
        }
        return manager.create(boxes).clip(0.0, 1.0);
    }

    /**
    * 解码 5 点关键点。
    * @param pre pre
    * @param priors priors
    * @param scaleXY scalexy
    * @return decodeLandm的结果
     */
    private NDArray decodeLandm(NDArray pre, NDArray priors, double scaleXY) {
        NDArray point1 = pre.get(":, :2").mul(scaleXY).mul(priors.get(":, 2:")).add(priors.get(":, :2"));
        NDArray point2 = pre.get(":, 2:4").mul(scaleXY).mul(priors.get(":, 2:")).add(priors.get(":, :2"));
        NDArray point3 = pre.get(":, 4:6").mul(scaleXY).mul(priors.get(":, 2:")).add(priors.get(":, :2"));
        NDArray point4 = pre.get(":, 6:8").mul(scaleXY).mul(priors.get(":, 2:")).add(priors.get(":, :2"));
        NDArray point5 = pre.get(":, 8:10").mul(scaleXY).mul(priors.get(":, 2:")).add(priors.get(":, :2"));
        return NDArrays.concat(new NDList(point1, point2, point3, point4, point5), 1);
    }

    /**
    * 计算 iou。
    * @param rec1 rec1
    * @param rec2 rec2
    * @return 获取iou的结果
     */
    private double getIoU(Rectangle rec1, Rectangle rec2) {
        double s1 = rec1.getWidth() * rec1.getHeight();
        double s2 = rec2.getWidth() * rec2.getHeight();
        double sumArea = s1 + s2;
        double left = Math.max(rec1.getX(), rec2.getX());
        double top = Math.max(rec1.getY(), rec2.getY());
        double right = Math.min(rec1.getX() + rec1.getWidth(), rec2.getX() + rec2.getWidth());
        double bottom = Math.min(rec1.getY() + rec1.getHeight(), rec2.getY() + rec2.getHeight());
        if (left >= right || top >= bottom) {
            return 0.0;
        }
        double intersect = (right - left) * (bottom - top);
        return intersect / (sumArea - intersect);
    }

    /**
    * 去除 批量 维度（模型输出可能带 [1,N,...]）。
    * @param array array
    * @return squeeze的结果
     */
    private NDArray squeeze(NDArray array) {
        if (array.getShape().dimension() == 3 && array.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            return array.squeeze(0);
        }
        return array;
    }

    /**
    * 计算 ndarray 的 最小/最大（调试用）。
    * @param array array
    * @return 最小最大的结果
     */
    private String minMax(NDArray array) {
        try {
            double[] d = array.toDoubleArray();
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (double v : d) {
                mn = Math.min(mn, v);
                mx = Math.max(mx, v);
            }
            return "[" + String.format("%.2f", mn) + ", " + String.format("%.2f", mx) + "]";
        } catch (Exception e) {
            return "err";
        }
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
