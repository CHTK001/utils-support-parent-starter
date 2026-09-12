package com.chua.deeplearning.support.paddle.ocr;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.paddle.util.BoundFinderV2;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
* PaddleOCR 文字检测 Translator。
*
* @author CH
* @since 4.0.0.42
 */
public class PpWordDetectionTranslator implements Translator<Image, DetectedObjects> {

    /**
    * 最长边限制。
     */
    private final int maxSideLen;

    /** 创建 ppworddetectiontranslator 实例 */
    public PpWordDetectionTranslator() {
        this(960);
    }

    /**
    * 创建 ppworddetectiontranslator 实例
    * @param maxSideLen 最大sidelen
     */
    public PpWordDetectionTranslator(int maxSideLen) {
        this.maxSideLen = maxSideLen;
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray result = list.singletonOrThrow();
        result = result.squeeze().mul(255f).toType(DataType.UINT8, true).gt(0.3);
        boolean[] flattened = result.toBooleanArray();
        Shape shape = result.getShape();
        int w = (int) shape.get(0);
        int h = (int) shape.get(1);
        boolean[][] grid = new boolean[w][h];
        IntStream.range(0, flattened.length)
                .parallel()
                .forEach(i -> grid[i / h][i % h] = flattened[i]);
        List<BoundingBox> boxes = new BoundFinderV2(grid).getBoxes();
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            names.add("word");
            probs.add(1.0);
        }
        return new DetectedObjects(names, probs, boxes);
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager());
        int h = input.getHeight();
        int w = input.getWidth();
        int resizeW = w;
        int resizeH = h;
        float ratio = 1.0f;
        if (Math.max(resizeH, resizeW) > maxSideLen) {
            if (resizeH > resizeW) {
                ratio = (float) maxSideLen / (float) resizeH;
            } else {
                ratio = (float) maxSideLen / (float) resizeW;
            }
        }
        resizeH = (int) (resizeH * ratio);
        resizeW = (int) (resizeW * ratio);
        resizeH = align32(resizeH);
        resizeW = align32(resizeW);
        img = NDImageUtils.resize(img, resizeW, resizeH);
        img = NDImageUtils.toTensor(img);
        img = NDImageUtils.normalize(img,
                new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f});
        img = img.expandDims(0);
        return new NDList(img);
    }

    /**
    * Align
    *
    * @param value 值
    * @return align32的结果
     */
    private int align32(int value) {
        if (value % 32 == 0) {
            return value;
        }
        if (Math.floor(value / 32f) <= 1) {
            return 32;
        }
        return (int) Math.floor(value / 32f) * 32;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
