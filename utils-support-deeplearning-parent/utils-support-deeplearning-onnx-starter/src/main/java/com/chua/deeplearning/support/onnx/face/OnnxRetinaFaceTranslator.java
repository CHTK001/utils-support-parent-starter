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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RetinaFace 人脸检测 Translator（ONNX 版，AIAS traced 导出）。
 *
 * <p>模型输出 3 个 tensor：loc([1,N,4])、conf([1,N,2])、landms([1,N,10])，N 由输入尺寸决定。
 * 关键点顺序：左眼、右眼、鼻、左嘴角、右嘴角，用于 5 点仿射对齐。输入为原图尺寸（任意 H×W）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxRetinaFaceTranslator implements Translator<Image, DetectedObjects> {

    private static final int TOP_K = 200;
    private static final double EYE_DIST_THRESHOLD = 5;
    private static final double[] VARIANCE = {0.1, 0.2};
    private static final int[][] SCALES = {{16, 32}, {64, 128}, {256, 512}};
    private static final int[] STEPS = {8, 16, 32};

    private double confThresh = 0.85;
    private double nmsThresh = 0.45;

    public OnnxRetinaFaceTranslator() {
    }

    public OnnxRetinaFaceTranslator(Map<String, ?> arguments) {
        if (arguments != null) {
            Object ct = arguments.get("threshold");
            if (ct instanceof Number) {
                confThresh = ((Number) ct).doubleValue();
            }
            Object nt = arguments.get("nms");
            if (nt instanceof Number) {
                nmsThresh = ((Number) nt).doubleValue();
            }
        }
    }

    /**
     * 输入图像宽。
     */
    private int width;

    /**
     * 输入图像高。
     */
    private int height;

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        java.awt.image.BufferedImage rgb = src;
        if (src.getType() != java.awt.image.BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(src, 0, 0, width, height, null);
        }
        int[] pixels = rgb.getRGB(0, 0, width, height, null, 0, width);
        float[] data = new float[3 * width * height];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            data[i] = ((p >> 16) & 0xff) - 104f;
            data[i + width * height] = ((p >> 8) & 0xff) - 117f;
            data[i + 2 * width * height] = (p & 0xff) - 123f;
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, height, width));
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 3) {
            return empty();
        }
        float[][] boxArr = to2d(list.get(0));
        float[][] confArr = to2d(list.get(1));
        float[][] landmArr = to2d(list.get(2));

        // 默认框（priors）
        double[][] priors = boxRecover(width, height, SCALES, STEPS);

        int n = Math.min(Math.min(boxArr.length, confArr.length), landmArr.length);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float[] loc = boxArr[i];
            float[] conf = confArr[i];
            float[] landms = landmArr[i];
            // 只取人脸类的概率
            float faceProb = conf.length > 1 ? conf[1] : conf[0];
            if (faceProb < confThresh) {
                continue;
            }
            double scaleXY = VARIANCE[0];
            double scaleWH = VARIANCE[1];
            double prX = priors[i][0], prY = priors[i][1];
            double prW = priors[i][2], prH = priors[i][3];
            double bw = Math.exp(loc[2] * scaleWH) * prW;
            double bh = Math.exp(loc[3] * scaleWH) * prH;
            double bx = loc[0] * scaleXY * prW + prX - bw * 0.5;
            double by = loc[1] * scaleXY * prH + prY - bh * 0.5;
            double[] kp = new double[10];
            for (int j = 0; j < 5; j++) {
                kp[j * 2] = landms[j * 2] * scaleXY * prW + prX;
                kp[j * 2 + 1] = landms[j * 2 + 1] * scaleXY * prH + prY;
            }
            candidates.add(new Candidate(bx, by, bw, bh, faceProb, kp));
        }

        candidates.sort((a, b) -> Double.compare(b.prob, a.prob));
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean keep = true;
            for (BoundingBox b : boxes) {
                Rectangle nb = b.getBounds();
                if (iouPixels(c, nb) > nmsThresh) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            // 眼睛距离过滤（关键点归一化，乘图像尺寸转像素）
            double eyeDist = Math.sqrt(Math.pow(c.kp[2] - c.kp[0], 2) + Math.pow(c.kp[3] - c.kp[1], 2))
                    * Math.max(width, height);
            if (eyeDist < EYE_DIST_THRESHOLD) {
                continue;
            }
            List<Point> pts = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                pts.add(new Point(c.kp[j * 2], c.kp[j * 2 + 1]));
            }
            // bounds 与关键点均为归一化坐标（adaptOutput 统一乘图像尺寸转像素）
            Landmark landmark = new Landmark(c.x, c.y, c.w, c.h, pts);
            names.add("face");
            probs.add(c.prob);
            boxes.add(landmark);
        }
        DetectedObjects result = new DetectedObjects(names, probs, boxes);
        return result;
    }

    /**
     * 与原已加入框计算 IoU（归一化坐标域）。
     */
    private double iouPixels(Candidate c, Rectangle nb) {
        double s1 = c.w * c.h;
        double s2 = nb.getWidth() * nb.getHeight();
        double sum = s1 + s2;
        double left = Math.max(c.x, nb.getX());
        double top = Math.max(c.y, nb.getY());
        double right = Math.min(c.x + c.w, nb.getX() + nb.getWidth());
        double bottom = Math.min(c.y + c.h, nb.getY() + nb.getHeight());
        if (left >= right || top >= bottom) {
            return 0.0;
        }
        double inter = (right - left) * (bottom - top);
        return inter / (sum - inter);
    }

    /**
     * 计算默认框（priors）。
     */
    private double[][] boxRecover(int width, int height, int[][] scales, int[] steps) {
        List<double[]> boxes = new ArrayList<>();
        for (int idx = 0; idx < steps.length; idx++) {
            int hCells = (int) Math.ceil((float) height / steps[idx]);
            int wCells = (int) Math.ceil((float) width / steps[idx]);
            for (int h = 0; h < hCells; h++) {
                for (int w = 0; w < wCells; w++) {
                    for (int i : scales[idx]) {
                        double skx = i * 1.0 / width;
                        double sky = i * 1.0 / height;
                        double cx = (w + 0.5) * steps[idx] / width;
                        double cy = (h + 0.5) * steps[idx] / height;
                        boxes.add(new double[]{cx, cy, skx, sky});
                    }
                }
            }
        }
        return boxes.toArray(new double[0][]);
    }

    /**
     * NDArray 转二维 float 数组（处理 batch 维）。
     */
    private static float[][] to2d(NDArray array) {
        Shape shape = array.getShape();
        float[] flat = array.toFloatArray();
        if (shape.dimension() == 3) {
            long batch = shape.get(0);
            long rows = shape.get(1);
            int cols = (int) shape.get(2);
            float[][] out = new float[(int) rows][cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(flat, (int) (i * batch * cols), out[i], 0, cols);
            }
            return out;
        }
        long rows = shape.get(0);
        int cols = (int) shape.get(1);
        float[][] out = new float[(int) rows][cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(flat, i * cols, out[i], 0, cols);
        }
        return out;
    }

    /**
     * 空结果。
     */
    private static DetectedObjects empty() {
        return new DetectedObjects(List.of(), List.of(), List.of());
    }

    /**
     * 候选框（像素坐标）。
     */
    private static final class Candidate {
        final double x;
        final double y;
        final double w;
        final double h;
        final double prob;
        final double[] kp;

        Candidate(double x, double y, double w, double h, double prob, double[] kp) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.prob = prob;
            this.kp = kp;
        }
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}