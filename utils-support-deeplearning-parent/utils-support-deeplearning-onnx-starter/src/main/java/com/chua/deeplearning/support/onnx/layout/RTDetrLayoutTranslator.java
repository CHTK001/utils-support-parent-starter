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
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
* RT-DETR v2 文档版面检测 Translator（doclaynet 17 类）。
*
* <p>双输入 images[N,3,640,640] + orig_target_sizes[N,2]，
* 输出 标签/scores/boxes（已后处理）。</p>
* @author CH
* @since 4.0.0
 */
@Slf4j
public class RTDetrLayoutTranslator implements Translator<Image, DetectedObjects> {

    private static final int INPUT_SIZE = 640; // 输入大小
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f}; // MEAN
    private static final float[] STD = {0.229f, 0.224f, 0.225f}; // STD

    private float scoreThreshold = 0.5f; // score阈值
    private int imgWidth; // imgwidth
    private int imgHeight; // imgheight

    /**
    * rtdetrlayouttranslator。
    */
    public RTDetrLayoutTranslator() {}
    /**
    * rtdetrlayouttranslator。
    * @param threshold 阈值
    */
    public RTDetrLayoutTranslator(float threshold) { this.scoreThreshold = threshold; }

    /**
    * 创建 Translator（支持外部阈值覆盖，未提供时使用内置默认值）。
    *
    * @param configuration 检测配置（可空）
    */
    public RTDetrLayoutTranslator(DetectionConfiguration configuration) {
        this(configuration == null ? 0.5f
                : configuration.optFloat(DetectionConfiguration.KEY_THRESHOLD, 0.5f));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        imgWidth = input.getWidth();
        imgHeight = input.getHeight();

        // 直接拉伸到 640×640（preprocessor_config.json: do_pad=false）
        BufferedImage wrapped = (BufferedImage) input.getWrappedImage();
        var resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        var g2d = resized.createGraphics();
        g2d.drawImage(wrapped, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        g2d.dispose();

        float[] chw = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int idx = 0;
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                // 仅 /255 缩放（preprocessor_config.json: do_normalize=false）
                chw[idx++] = ((rgb >> (16 - 8 * c)) & 0xff) / 255.0f;
                }
            }
        }
        NDArray images = manager.create(chw, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        images.setName("images");
        NDArray sizes = manager.create(new long[][]{{imgHeight, imgWidth}});
        sizes.setName("orig_target_sizes");
        return new NDList(images, sizes);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray labelNd = null, scoreNd = null, boxNd = null;
        for (NDArray nd : list) {
            String n = nd.getName();
            if ("labels".equals(n)) {
                labelNd = nd;
            }
            else if ("scores".equals(n)) {
                scoreNd = nd;
            }
            else if ("boxes".equals(n)) {
                boxNd = nd;
            }
        }
        if (labelNd == null) {
            labelNd = list.get(0);
        }
        if (scoreNd == null && list.size() > 1) {
            scoreNd = list.get(1);
        }
        if (boxNd == null && list.size() > 2) {
            boxNd = list.get(2);
        }
        if (labelNd == null || scoreNd == null || boxNd == null) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        long[] labels = labelNd.toLongArray();
        float[] scores = scoreNd.toFloatArray();
        float[] boxArr = boxNd.toFloatArray();
        var bshape = boxNd.getShape().getShape();
        int numBoxes = (int) bshape[bshape.length - 2];
        int boxDim = (int) bshape[bshape.length - 1];

        List<String> nameList = new ArrayList<>();
        List<Double> probList = new ArrayList<>();
        List<ai.djl.modality.cv.output.BoundingBox> boxList = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            if (scores[i] < scoreThreshold) {
                continue;
            }
            int clsId = (int) labels[i];
            String label = clsId < LABELS.length ? LABELS[clsId] : "region_" + clsId;

            int bi = i * boxDim;
            float x1 = boxArr[bi], y1 = boxArr[bi+1], x2 = boxArr[bi+2], y2 = boxArr[bi+3];
            float w = x2 - x1, h = y2 - y1;
            if (w <= 0 || h <= 0) {
                continue;
            }

            nameList.add(label);
            probList.add((double) scores[i]);
            boxList.add(new Rectangle(x1 / imgWidth, y1 / imgHeight, w / imgWidth, h / imgHeight));
        }

        if (nameList.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }
        return new DetectedObjects(nameList, probList, boxList);
    }

    private static final String[] LABELS = {
            "Caption", "Footnote", "Formula", "List-item", "Page-footer",
            "Page-header", "Picture", "Section-header", "Table", "Text",
            "Title", "Checkbox-Selected", "Checkbox-Unselected", "Form",
            "Key-Value Region", "Underline", "Handwriting"
    };

    @Override
    public Batchifier getBatchifier() { return null; }
}
