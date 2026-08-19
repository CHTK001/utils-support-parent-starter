package com.chua.deeplearning.support.onnx.emotion;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.result.PredictResult;


/**
 * Emotion FERPlus                   
 * <p>
 *             : [1, 3, 224, 224] RGB       
 *             : [1, 7]                   
 * <p>
 *          :
 * -                 224x224
 * -              ImageNet       
 * -           CHW       
 * <p>
 *          :
 * -        softmax             
 * -                                  
 *
 * @author CH
 * @since 2025-01-20
 */
public class EmotionFerplusTranslator implements Translator<Image, PredictResult> {

    /** 情感标签数组 */
    /** Emotionlabels */
    private final String[] emotionLabels;

    /** 创建 EmotionFerplusTranslator 实例 */
    public EmotionFerplusTranslator() {
        this(new String[]{"angry", "disgust", "fear", "happy", "sad", "surprise", "neutral"});
    }

    /**
     * 创建 EmotionFerplusTranslator 实例
     * @param emotionLabels emotionLabels
     */
    public EmotionFerplusTranslator(String[] emotionLabels) {
        this.emotionLabels = emotionLabels;
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, 224, 224);
        array = array.toType(DataType.FLOAT32, false).div(255.0f);

        NDArray mean = ctx.getNDManager().create(new float[]{0.485f, 0.456f, 0.406f}, new Shape(1, 1, 3));
        NDArray std = ctx.getNDManager().create(new float[]{0.229f, 0.224f, 0.225f}, new Shape(1, 1, 3));
        array = array.sub(mean).div(std);

        array = array.transpose(2, 0, 1).expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public PredictResult processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.singletonOrThrow();
        float[] scores = logits.toFloatArray();
        
        //        softmax
        float[] probabilities = softmax(scores);
        
        //                            
        int maxIndex = 0;
        float maxProb = probabilities[0];
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > maxProb) {
                maxProb = probabilities[i];
                maxIndex = i;
            }
        }
        
        String emotion = maxIndex < emotionLabels.length ? emotionLabels[maxIndex] : "Unknown";
        
        return PredictResult.builder()
                .value(emotion)
                .confidence(maxProb)
                .build();
    }

    /**
     *        softmax       
     */
    private float[] softmax(float[] logits) {
        float max = 0.0f;
        if (logits.length > 0) {
            max = logits[0];
            for (int i = 1; i < logits.length; i++) {
                max = Math.max(max, logits[i]);
            }
        }
        float sum = 0.0f;
        float[] expValues = new float[logits.length];
        
        for (int i = 0; i < logits.length; i++) {
            expValues[i] = (float) Math.exp(logits[i] - max);
            sum += expValues[i];
        }
        
        for (int i = 0; i < expValues.length; i++) {
            expValues[i] = expValues[i] / sum;
        }
        
        return expValues;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}


