package com.chua.deeplearning.support.pytorch.face.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * GFPGAN 人脸修复/超分 Translator。
 * <p>输入归一化到 [-1,1]，输出还原到 [0,255]。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GfpganTranslator implements Translator<Image, Image> {

    /**
     * 输出裁剪范围。
     */
    private static final int[] MIN_MAX = new int[]{-1, 1};

    /**
     * 均值。
     */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};

    /**
     * 标准差。
     */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /**
     * 输入尺寸。
     */
    private static final int INPUT_SIZE = 512;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);
        array = array.transpose(2, 0, 1).div(255.0f);
        NDArray mean = manager.create(MEAN, new Shape(3, 1, 1));
        NDArray std = manager.create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.getFirst();
        if (array.getShape().dimension() == 4 && array.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            array = array.squeeze(0);
        }
        array = array.clip(MIN_MAX[0], MIN_MAX[1]);
        array = array.sub(MIN_MAX[0]).div(MIN_MAX[1] - MIN_MAX[0]).mul(255.0f);
        array = array.round().toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(array);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
