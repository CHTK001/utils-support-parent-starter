package com.chua.deeplearning.support.pytorch.style;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * 风格迁移 Translator（CycleGAN / 类似模型）。
 * <p>输入 CHW float32，输出还原为 Image。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StyleTransferTranslator implements Translator<Image, Image> {

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray image = switchFormat(input.toNDArray(ctx.getNDManager())).expandDims(0);
        return new NDList(image.toType(DataType.FLOAT32, false));
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray ndArray = list.get(0);
        NDArray output = ctx.getNDManager().create(ndArray.toFloatArray(), ndArray.getShape())
                .addi(1)
                .muli(128)
                .clip(0, 255)
                .toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(output.squeeze());
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * HWC → CHW。
     *
     * @param array 输入数组
     * @return CHW 数组
     */
    private NDArray switchFormat(NDArray array) {
        return NDArrays.stack(array.split(3, 2)).squeeze();
    }
}
