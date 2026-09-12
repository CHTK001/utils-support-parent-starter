package com.chua.deeplearning.support.onnx.reid;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
   * youtu reid                 Translator
 *
 * <p>            : [batch, 3, 256, 128] RGB       
 * <p>            : [batch, 768, 1, 1]             
 *
 * <p>         :
 * -                       256x128   H x W   
 * -              [0, 1]
   * - 镜像net
 * -           NCHW       
 *
 * <p>         :
 * -                    (1x1)
 * -        768                
 * - L2          
 *
 * @author CH
 * @since 2026-05-10
 */
@Slf4j
public class OsnetReidTranslator implements Translator<Image, float[]> {

    /** 输入高度 */
    /** 输入_h */
    private static final int INPUT_H = 256;
    /** 输入宽度 */
    /** 输入_w */
    private static final int INPUT_W = 128;
    /** 特征维度 */
    /** 特征_dim */
    private static final int FEATURE_DIM = 768;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        //                 256x128   H x W   
        array = NDImageUtils.resize(array, INPUT_W, INPUT_H);

        //           FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // HWC     CHW                [0, 1]
        array = array.transpose(2, 0, 1).div(255.0f);

 // 镜像net
        NDArray mean = manager.create(MEAN, new Shape(3, 1, 1));
        NDArray std = manager.create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

 // 批量       : [C, H, W]     [1, C, H, W]
        array = array.expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("YouTu ReID                   : shape={}", array.getShape());
        }

        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

 // 批量
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }

        //                    [768, 1, 1]     [768]
        if (output.getShape().dimension() > 1) {
            int dim = output.getShape().dimension();
            //              squeeze           1       
            for (int i = dim - 1; i >= 0; i--) {
                if (output.getShape().get(i) == 1) {
                    output = output.squeeze(i);
                }
            }
        }

        float[] features = output.toFloatArray();

        // L2          
        float norm = 0.0f;
        for (float f : features) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0.0f) {
            for (int i = 0; i < features.length; i++) {
                features[i] /= norm;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("YouTu ReID             : dim={}, norm={}", features.length, norm);
        }

        return features;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
