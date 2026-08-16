package com.chua.deeplearning.support.onnx.assessment;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * NIMA (Neural Image Assessment)                    Translator
 *
 * <p>       VGG16                                            10                          1-10          
 *        Softmax             ,                          Mean Opinion Score (MOS)   
 *
 * <p>         :
 * <ul>
 *   <li>          224x224
 *   <li>             [0, 1]          255.0   
 *   <li>ImageNet                                        
 *   <li>HWC     CHW       
 * </ul>
 *
 * <p>         :
 * <ul>
 *   <li>Softmax                            
 *   <li>       float[10]          1-10             
 * </ul>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class NimaTranslator implements Translator<Image, float[]> {

    private static final int IMAGE_SIZE = 224;
    private static final float[] IMAGE_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGE_STD = {0.229f, 0.224f, 0.225f};

    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        // OpenCV 预处理：resize 224 + ImageNet 归一化 + CHW → float[] → create() 喂入 djl-onnx
        float[] pixels = OpenCvImageUtils.toTensor(input, IMAGE_SIZE, IMAGE_MEAN, IMAGE_STD, false);
        NDArray array = ctx.getNDManager().create(pixels, new Shape(1, 3, IMAGE_SIZE, IMAGE_SIZE));
        array.setName("input");
        return new NDList(array);
    }

    @Override
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        float[] logits = output.toFloatArray();
        if (logits.length == 0) {
            return new float[0];
        }
        // Softmax 纯 Java 实现（djl-onnx 不支持 exp/sum）
        double[] exp = new double[logits.length];
        double max = Double.NEGATIVE_INFINITY;
        for (float v : logits) {
            max = Math.max(max, v);
        }
        double sum = 0.0;
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        float[] softmax = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            softmax[i] = (float) (exp[i] / sum);
        }
        return softmax;
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
