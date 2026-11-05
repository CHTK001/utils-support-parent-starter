// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * 图文超分 Translator（短边对齐后归一化到 [-1,1]）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageTextSuperResolutionTranslator implements Translator<Image, Image> {

    /**
     * 检测/短边分辨率。
     */
    private final int detectResolution;

    /**
     * 创建 镜像文本父resolutiontranslator 实例
    */
    public ImageTextSuperResolutionTranslator() {
        this(512);
    }

    /**
     * 创建 镜像文本父resolutiontranslator 实例
     * @param detectResolution detectresolution
     */
    public ImageTextSuperResolutionTranslator(int detectResolution) {
        this.detectResolution = detectResolution;
    }

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager()).toType(DataType.FLOAT32, false);
        float upScale = (float) detectResolution / (float) Math.max(1, input.getHeight());
        int resizedWidth = Math.max(1, (int) (upScale * input.getWidth()));
        array = NDImageUtils.resize(array, resizedWidth, detectResolution);
        array = array.transpose(2, 0, 1).div(255.0f);
        NDArray mean = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    /**
     * 处理输出
    */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        if (array.getShape().dimension() == 4 && array.getShape().get(0) == 1) {
            array = array.squeeze(0);
        }
        array = array.clip(-1f, 1f);
        array = array.add(1f).div(2f).mul(255f).clip(0, 255).toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(array);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}