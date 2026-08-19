package com.chua.deeplearning.support.onnx.emotion;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.result.PredictResult;
import com.chua.deeplearning.support.utils.ImageUtils;


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
        Object wrapped = input.getWrappedImage();
        if (!(wrapped instanceof java.awt.image.BufferedImage bufferedImage)) {
            throw new IllegalArgumentException("不支持的图像类型: " + wrapped.getClass().getName());
        }
        // AWT 缩放（ONNX Runtime 引擎的 NDArray 不支持 resize）
        java.awt.image.BufferedImage resized = ImageUtils.resize(bufferedImage, 224, 224,
                org.opencv.imgproc.Imgproc.INTER_LINEAR);
        int[] pixels = resized.getRGB(0, 0, 224, 224, null, 0, 224);
        float[] chw = new float[3 * 224 * 224];
        int total = 224 * 224;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            chw[i] = (((p >> 16) & 0xff) / 255f - 0.485f) / 0.229f;
            chw[i + total] = (((p >> 8) & 0xff) / 255f - 0.456f) / 0.224f;
            chw[i + 2 * total] = ((p & 0xff) / 255f - 0.406f) / 0.225f;
        }
        NDArray array = ctx.getNDManager().create(chw, new Shape(1, 3, 224, 224));
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


