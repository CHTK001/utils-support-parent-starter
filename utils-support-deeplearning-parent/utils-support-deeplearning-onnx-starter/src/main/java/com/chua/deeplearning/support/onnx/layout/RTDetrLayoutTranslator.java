package com.chua.deeplearning.support.onnx.layout;

import ai.djl.modality.cv.Image;
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
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * RT-DETR v2 文档版面检测 Translator（DocLayNet 17 类）。
 *
 * <p>嵌入式权重 vision/layout/rtdetr/model.onnx（169MB fp32）。双输入：
 * images[N,3,640,640](ImageNet 归一化 letterbox) + orig_target_sizes[N,2](i64)。
 * 输出 labels/scores/boxes 已由模型后处理（无 NMS，DETR 端到端），boxes 为原图绝对像素坐标。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RTDetrLayoutTranslator implements Translator<Image, DetectedObjects> {

    private static final int INPUT_SIZE = 640;

    /** ImageNet 归一化参数 */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /** DocLayNet 17 类标签（按模型输出索引排列） */
    private static final String[] LABELS = {
            "Caption", "Footnote", "Formula", "List-item", "Page-footer",
            "Page-header", "Picture", "Section-header", "Table", "Text",
            "Title", "Checkbox-Selected", "Checkbox-Unselected", "Form",
            "Key-Value Region", "Underline", "Handwriting"
    };

    private float scoreThreshold = 0.5f;

    public RTDetrLayoutTranslator() {}

    public RTDetrLayoutTranslator(float threshold) {
        this.scoreThreshold = threshold;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        int origW = input.getWidth();
        int origH = input.getHeight();

        // Letterbox：等比缩放到 640×640 并居中填充
        float scale = Math.min((float) INPUT_SIZE / origW, (float) INPUT_SIZE / origH);
        int newW = Math.round(origW * scale);
        int newH = Math.round(origH * scale);
        int padLeft = (INPUT_SIZE - newW) / 2;
        int padTop = (INPUT_SIZE - newH) / 2;

        BufferedImage wrapped = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = resized.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
        g.drawImage(wrapped, padLeft, padTop, newW, newH, null);
        g.dispose();

        // CHW 归一化
        float[] chw = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int idx = 0;
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    int rgb = resized.getRGB(x, y);
                    float v = ((rgb >> (16 - 8 * c)) & 0xff) / 255.0f;
                    chw[idx++] = (v - MEAN[c]) / STD[c];
                }
            }
        }

        NDArray images = manager.create(chw, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        images.setName("images");

        NDArray origSizes = manager.create(new long[][]{{origH, origW}});
        origSizes.setName("orig_target_sizes");

        return new NDList(images, origSizes);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray labelNd = null;
        NDArray scoreNd = null;
        NDArray boxNd = null;
        for (NDArray nd : list) {
            String n = nd.getName();
            if ("labels".equals(n)) { labelNd = nd; }
            else if ("scores".equals(n)) { scoreNd = nd; }
            else if ("boxes".equals(n)) { boxNd = nd; }
        }
        if (labelNd == null) { labelNd = list.get(0); }
        if (scoreNd == null) { scoreNd = list.size() > 1 ? list.get(1) : null; }
        if (boxNd == null) { boxNd = list.size() > 2 ? list.get(2) : null; }
        if (scoreNd == null || boxNd == null || labelNd == null) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        long[] labels = labelNd.toLongArray();
        float[] scores = scoreNd.toFloatArray();
        float[] boxArr = boxNd.toFloatArray();
        long[] bshape = boxNd.getShape().getShape();
        int numBoxes = (int) bshape[bshape.length - 2];
        int boxDim = (int) bshape[bshape.length - 1];

        List<String> nameList = new ArrayList<>();
        List<Double> probList = new ArrayList<>();
        List<ai.djl.modality.cv.output.BoundingBox> boxList = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            float score = scores[i];
            if (score < scoreThreshold) { continue; }
            int clsId = (int) labels[i];
            String label = clsId < LABELS.length ? LABELS[clsId] : "region_" + clsId;

            // boxes 为原图绝对像素 xyxy（模型通过 orig_target_sizes 已还原）
            int bi = i * boxDim;
            float x1 = boxArr[bi];
            float y1 = boxArr[bi + 1];
            float x2 = boxArr[bi + 2];
            float y2 = boxArr[bi + 3];
            float w = x2 - x1;
            float h = y2 - y1;
            if (w <= 0 || h <= 0) { continue; }

            nameList.add(label);
            probList.add((double) score);
            boxList.add(new Rectangle(x1, y1, w, h));
        }

        if (nameList.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }
        return new DetectedObjects(nameList, probList, boxList);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
