package com.chua.deeplearning.support.paddle.ocr;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.Arrays;
import java.util.List;

/**
 * PaddleOCR 文字方向分类 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpWordRotateTranslator implements Translator<Image, Classifications> {

    /**
     * 类别。
     */
    private final List<String> classes = Arrays.asList("No Rotate", "Rotate");

    @Override
    /**
     * 处理输出
    */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        return new Classifications(this.classes, list.singletonOrThrow());
    }

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager());
        img = NDImageUtils.resize(img, 192, 48);
        img = NDImageUtils.toTensor(img).sub(0.5F).div(0.5F);
        img = img.expandDims(0);
        return new NDList(img);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
