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
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * YOLOv11n Face ONNX Translator（嵌入 jar，纯 Java 预处理，兼容 onnxruntime engine）。
 *
 * <p>真人/动物卡通人脸检测：YOLOv11n-face（AdamCodd）。输入 640×640 RGB 归一化 [0,1]，
 * 输出 [1,5,8400]（cx,cy,w,h,face_conf）。</p>
 *
 * <p>onnxruntime engine 不支持 NDArray resize/squeeze/transpose，letterbox 与 NMS 均纯 Java。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class YoloFaceTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 标签名。
     */
    private static final String FACE_LABEL = "face";

    /**
     * 输入尺寸。
     */
    private static final int INPUT_SIZE = 640;

    /**
     * 置信度阈值。
     */
    private static final float CONF_THRESHOLD = 0.25f;

    /**
     * NMS IOU 阈值。
     */
    private static final float IOU_THRESHOLD = 0.45f;

    /**
     * Top-K。
     */
    private static final int TOP_K = 300;

    /**
     * letterbox 缩放比例。
     */
    private float scaleR = 1f;

    /**
     * 左侧填充。
     */
    private int padLeft;

    /**
     * 顶部填充。
     */
    private int padTop;

    /**
     * 原图宽。
     */
    private int imageWidth;

    /**
     * 原图高。
     */
    private int imageHeight;

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();
        BufferedImage src = (BufferedImage) input.getWrappedImage();

        // letterbox：等比缩放 + 灰边填充到 640x640
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
        java.util.Arrays.fill(data, 114f / 255f);
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int p = pixels[y * newW + x];
                int idx = (y + padTop) * INPUT_SIZE + (x + padLeft);
                data[idx] = ((p >> 16) & 0xff) / 255f;
                data[idx + INPUT_SIZE * INPUT_SIZE] = ((p >> 8) & 0xff) / 255f;
                data[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (p & 0xff) / 255f;
            }
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.get(0);
        long[] shape = output.getShape().getShape();
        // 兼容 [1,C,N] / [1,N,C]，直读 flat 避免 ORT 不支持的 squeeze/transpose
        float[] data = output.toFloatArray();
        int dim = shape.length;
        int numDets;
        int numChannels;
        boolean transpose = false;
        if (dim == 3) {
            if (shape[1] == 5 || shape[1] == 6 || shape[1] < shape[2]) {
                // [B,C,N]
                numChannels = (int) shape[1];
                numDets = (int) shape[2];
            } else {
                // [B,N,C] → 需转置
                numDets = (int) shape[1];
                numChannels = (int) shape[2];
                transpose = true;
            }
        } else if (dim == 2) {
            if (shape[1] == 5 || shape[1] == 6 || shape[1] < shape[0]) {
                numDets = (int) shape[0];
                numChannels = (int) shape[1];
            } else {
                numDets = (int) shape[1];
                numChannels = (int) shape[0];
                transpose = true;
            }
        } else {
            throw new IllegalStateException("YoloFace 输出维度异常: " + java.util.Arrays.toString(shape));
        }
        if (numChannels < 5) {
            throw new IllegalStateException("YoloFace 输出通道异常: " + numChannels);
        }

        // 收集候选框
        List<float[]> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (int i = 0; i < numDets; i++) {
            float cx, cy, w, h;
            if (transpose) {
                // [N,C] / [1,N,C]：按行读
                int base = i * numChannels;
                if (base + 4 >= data.length) {
                    break;
                }
                cx = data[base];
                cy = data[base + 1];
                w = data[base + 2];
                h = data[base + 3];
            } else {
                // [C,N]：按列读
                int base = i;
                cx = data[0 * numDets + base];
                cy = data[1 * numDets + base];
                w = data[2 * numDets + base];
                h = data[3 * numDets + base];
            }
            float conf = 0f;
            for (int c0 = 4; c0 < numChannels; c0++) {
                int idx = transpose ? i * numChannels + c0 : c0 * numDets + i;
                if (idx >= data.length) {
                    break;
                }
                conf = Math.max(conf, data[idx]);
            }
            if (conf < CONF_THRESHOLD) {
                continue;
            }
            boxes.add(new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2});
            scores.add(conf);
        }

        if (boxes.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        int[] keep = nms(boxes, scores);
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> rects = new ArrayList<>();
        int topK = Math.min(keep.length, TOP_K);
        for (int i = 0; i < topK; i++) {
            float[] b = boxes.get(keep[i]);
            // letterbox 坐标还原
            float x1 = Math.max(0, (b[0] - padLeft) / scaleR);
            float y1 = Math.max(0, (b[1] - padTop) / scaleR);
            float x2 = Math.min(imageWidth, (b[2] - padLeft) / scaleR);
            float y2 = Math.min(imageHeight, (b[3] - padTop) / scaleR);
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            names.add(FACE_LABEL);
            probs.add((double) scores.get(keep[i]));
            rects.add(new Rectangle(x1 / imageWidth, y1 / imageHeight,
                    (x2 - x1) / imageWidth, (y2 - y1) / imageHeight));
        }
        return new DetectedObjects(names, probs, rects);
    }

    /**
     * 纯 Java NMS（分数降序，抑制 IOU 重叠框）。
     *
     * @param boxes  候选框 [x1,y1,x2,y2]
     * @param scores 分数
     * @return 保留框索引
     */
    private static int[] nms(List<float[]> boxes, List<Float> scores) {
        int n = boxes.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(scores.get(b), scores.get(a)));
        List<Integer> keep = new ArrayList<>();
        boolean[] removed = new boolean[n];
        for (int ii = 0; ii < n; ii++) {
            int i = idx[ii];
            if (removed[i]) {
                continue;
            }
            keep.add(i);
            float[] bi = boxes.get(i);
            float iArea = Math.max(0, bi[2] - bi[0]) * Math.max(0, bi[3] - bi[1]);
            for (int jj = ii + 1; jj < n; jj++) {
                int j = idx[jj];
                if (removed[j]) {
                    continue;
                }
                float[] bj = boxes.get(j);
                float xx1 = Math.max(bi[0], bj[0]);
                float yy1 = Math.max(bi[1], bj[1]);
                float xx2 = Math.min(bi[2], bj[2]);
                float yy2 = Math.min(bi[3], bj[3]);
                if (xx2 <= xx1 || yy2 <= yy1) {
                    continue;
                }
                float inter = (xx2 - xx1) * (yy2 - yy1);
                float jArea = Math.max(0, bj[2] - bj[0]) * Math.max(0, bj[3] - bj[1]);
                if (inter / (iArea + jArea - inter) > IOU_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }
        int[] result = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            result[i] = keep.get(i);
        }
        return result;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        // ONNX Runtime 的 NDArray 不支持 Stack，单图推理不批处理
        return null;
    }
}