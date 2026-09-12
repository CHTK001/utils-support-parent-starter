package com.chua.deeplearning.support.onnx.clip;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
* CN-CLIP                 Translator   
*
* <p>CN-CLIP        Chinese-CLIP ViT-B/16 image encoder ONNX                       
* 输入 [N,3,224,224] float32  unnorm_镜像_特征 [N,512]           </p>
*
* @author CH
* @since 4.0.0.42
 */
public class CnClipImageFeatureTranslator implements Translator<ai.djl.modality.cv.Image, float[]> {

    /** 图像尺寸 */
    /** 镜像_大小 */
    private static final int IMAGE_SIZE = 224;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = new float[]{0.48145466f, 0.45782750f, 0.40821073f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = new float[]{0.26862954f, 0.26130258f, 0.27577711f};

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, ai.djl.modality.cv.Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), ai.djl.modality.cv.Image.Flag.COLOR);
        array = ai.djl.modality.cv.util.NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255f);
        int h = (int) array.getShape().get(0);
        int w = (int) array.getShape().get(1);
        int c = (int) array.getShape().get(2);
        float[] flat = array.toFloatArray();
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                for (int k = 0; k < c; k++) {
                    int idx = (i * w + j) * c + k;
                    flat[idx] = (flat[idx] - MEAN[k]) / STD[k];
                }
            }
        }
        array = ctx.getNDManager().create(flat, new ai.djl.ndarray.types.Shape(h, w, c));
        array = array.transpose(2, 0, 1).expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray imageEmbeds = list.singletonOrThrow();
        return imageEmbeds.squeeze().toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
