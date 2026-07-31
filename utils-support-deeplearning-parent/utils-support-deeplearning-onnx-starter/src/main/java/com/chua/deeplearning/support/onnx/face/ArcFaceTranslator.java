package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;


/**
 * ArcFace                   
 * <p>
 *             : [1, 3, 112, 112] RGB       
 *             : [1, 512]             
 * <p>
 *          :
 * -                       112x112
 * -           RGB       
 * -          : (pixel - 127.5) / 128.0
 * -           CHW       
 * <p>
 *          :
 * -                   
 * -                         L2             
 *
 * @author CH
 * @since 2025-01-20
 */
public class ArcFaceTranslator implements Translator<Image, float[]> {

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        
        //                 112x112
        array = NDImageUtils.resize(array, 112, 112);
        
        //           FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        
        //           CHW       
        array = array.transpose(2, 0, 1);
        
        //          : (pixel - 127.5) / 128.0
        array = array.div(255.0f).sub(0.5f).div(0.5f);
        
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        float[] features = array.toFloatArray();
        
        // L2          
        float norm = 0.0f;
        for (float feature : features) {
            norm += feature * feature;
        }
        norm = (float) Math.sqrt(norm);
        
        if (norm > 0.0f) {
            for (int i = 0; i < features.length; i++) {
                features[i] = features[i] / norm;
            }
        }
        
        return features;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}

