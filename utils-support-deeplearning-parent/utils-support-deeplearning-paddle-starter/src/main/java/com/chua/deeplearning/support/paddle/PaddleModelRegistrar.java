package com.chua.deeplearning.support.paddle;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.nlp.TextTranslator;

/**
 * PaddlePaddle 模块模型集中注册器。
 * <p>
 * 通过 SPI 被主框架加载；类名字符串注册 + 懒加载 Translator。
 * relativePath 相对 models 根目录，统一 paddle/ 前缀。
 * 模型一般为 inference.pdmodel + .pdiparams。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PaddleModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    /** 注册 */
    public void register(ModelRegistry registry) {
        registerAll();
    }

    /** 注册All */
    private static void registerAll() {
        // 分类
        reg("paddle-animal",
                "com.chua.deeplearning.support.paddle.classification.AnimalTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/animal");
        reg("paddle-dishes",
                "com.chua.deeplearning.support.paddle.classification.DishesTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/dishes");

        // 检测
        reg("paddle-vehicle",
                "com.chua.deeplearning.support.paddle.detection.VehicleTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/vehicle");
        reg("paddle-pedestrian",
                "com.chua.deeplearning.support.paddle.detection.PedestrianTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/pedestrian");
        reg("paddle-traffic",
                "com.chua.deeplearning.support.paddle.detection.TrafficTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/traffic");

        // 人脸
        reg("paddle-face-detect",
                "com.chua.deeplearning.support.paddle.face.PaddleFaceDetectorTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "face/detect");
        reg("paddle-face-feature",
                "com.chua.deeplearning.support.paddle.face.PaddleFaceFeatureTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/feature");
        reg("paddle-face-landmark",
                "com.chua.deeplearning.support.paddle.face.FaceLandmarkTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/landmark");

        // OCR
        reg("paddle-ocr-det",
                "com.chua.deeplearning.support.paddle.ocr.PpWordDetectionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "ocr/det");
        reg("paddle-ocr-rec",
                "com.chua.deeplearning.support.paddle.ocr.PpWordRecognitionTranslator",
                ai.djl.modality.cv.Image.class, String.class,
                TextTranslator.class, "ocr/rec");
        reg("paddle-ocr-rotate",
                "com.chua.deeplearning.support.paddle.ocr.PpWordRotateTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "ocr/rotate");

        // NLP
        reg("paddle-senta",
                "com.chua.deeplearning.support.paddle.nlp.SentaTranslator",
                String[].class, float[].class,
                FeatureExtractor.class, "nlp/senta");
        reg("paddle-review",
                "com.chua.deeplearning.support.paddle.nlp.ReviewTranslator",
                String[].class, float[].class,
                FeatureExtractor.class, "nlp/review");
        reg("paddle-lac",
                "com.chua.deeplearning.support.paddle.nlp.LacTranslator",
                String.class, String[][].class,
                TextTranslator.class, "nlp/lac");
        reg("paddle-simnet",
                "com.chua.deeplearning.support.paddle.nlp.SimnetBowTranslator",
                String[][].class, float[].class,
                FeatureExtractor.class, "nlp/simnet");

        }

    /**
     * Reg
     * @param modelId modelId
     * @param translatorClassName translatorClassName
     * @param inputType inputType
     * @param outputType outputType
     * @param capability capability
     * @param relativePath relativePath
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            String path = relativePath == null ? null : "paddle/" + relativePath;
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path);
        }
    }
}
