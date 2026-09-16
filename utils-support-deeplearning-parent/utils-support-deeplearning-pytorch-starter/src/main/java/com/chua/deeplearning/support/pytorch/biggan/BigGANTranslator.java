package com.chua.deeplearning.support.pytorch.biggan;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
* biggan 图像生成 Translator。
* <p>输入类别 ID（Long），输出生成图像。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class BigGANTranslator implements Translator<Long, Image> {

    /**
    * 截断系数，控制生成多样性。
     */
    private final float truncation;

    /**
    * 噪声向量维度。
     */
    private final int noiseSize;

    /**
    * 默认 128 分辨率。
     */
    public BigGANTranslator() {
        this(128, 0.4f);
    }

    /**
    * 构造 biggan Translator。
    *
    * @param size       输出分辨率 128/256/512
    * @param truncation 截断系数
     */
    public BigGANTranslator(int size, float truncation) {
        if (size == 128) {
            this.noiseSize = 120;
        } else if (size == 256) {
            this.noiseSize = 140;
        } else if (size == 512) {
            this.noiseSize = 128;
        } else {
            this.noiseSize = 120;
        }
        this.truncation = truncation;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Long classId) {
        NDArray noise = ctx.getNDManager().randomNormal(new Shape(1, noiseSize));
        // truncation trick
        noise = noise.clip(-2 * truncation, 2 * truncation).mul(truncation);
        NDArray label = ctx.getNDManager().create(new long[]{classId == null ? 0L : classId});
        NDArray truncationArr = ctx.getNDManager().create(truncation);
        return new NDList(noise, label, truncationArr);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray ndArray = list.getFirst();
        NDArray output = ctx.getNDManager().create(ndArray.toFloatArray(), ndArray.getShape());
        // [-1,1] -> [0,255]
        output = output.addi(1).muli(128).clip(0, 255).toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(output.squeeze());
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
