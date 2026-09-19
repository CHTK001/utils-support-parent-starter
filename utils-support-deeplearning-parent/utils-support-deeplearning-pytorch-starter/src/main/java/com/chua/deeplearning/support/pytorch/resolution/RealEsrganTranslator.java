package com.chua.deeplearning.support.pytorch.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Real-ESRGAN 超分辨率 Translator。
 * <p>
 * 输入 HWC→CHW 并归一化到 [0,1]；输出 clip 到 [0,1] 后还原为 镜像。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RealEsrganTranslator implements Translator<Image, Image> {

    /**
     * 放大倍数（元数据，推理不依赖）。
     */
    private final int scale;

    /** 创建 realesrgantranslator 实例 */
    public RealEsrganTranslator() {
        this(4);
    }

    /**
    * 创建 realesrgantranslator 实例
    * @param scale scale
    */
    public RealEsrganTranslator(int scale) {
        this.scale = scale;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager()).toType(DataType.FLOAT32, false);
        array = array.transpose(2, 0, 1).div(255.0f);
        log.debug("RealESRGAN 输入 shape={}, scale={}x", array.getShape(), scale);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();
        if (outputImg.getShape().dimension() == 4 && outputImg.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            outputImg = outputImg.squeeze(0);
        }
        outputImg = outputImg.clip(0.0f, 1.0f);
        outputImg = outputImg.mul(255.0f).round().toType(DataType.UINT8, false);
        Image img = ImageFactory.getInstance().fromNDArray(outputImg);
        log.debug("RealESRGAN 输出: {}x{}", img.getWidth(), img.getHeight());
        return img;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
