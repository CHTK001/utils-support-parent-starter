package com.chua.deeplearning.support.tensorflow;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;

/**
 * TensorFlow 模块模型集中注册器。
 * <p>
 * 通过 SPI 被主框架加载；relativePath 相对 models 根目录，统一 tensorflow/ 前缀。
 * 支持 SavedModel 目录或 .pb。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TensorflowModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    public void register(ModelRegistry registry) {
        registerAll();
    }

    private static void registerAll() {
        // 分类
        reg("tf-mobilenet",
                "com.chua.deeplearning.support.tensorflow.classification.MobilenetClassificationTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/mobilenet");

        // 目标检测 SavedModel
        reg("tf-object-detection",
                "com.chua.deeplearning.support.tensorflow.detection.SavedModelObjectDetectionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/ssd");

        // 超分
        reg("tf-super-resolution",
                "com.chua.deeplearning.support.tensorflow.resolution.SuperResolutionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "resolution/esr");
    }

    /**
     * 注册单个模型。
     *
     * @param modelId            模型标识
     * @param translatorClassName 翻译器类名
     * @param inputType          输入类型
     * @param outputType         输出类型
     * @param capability         能力接口
     * @param relativePath       相对路径
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            String path = relativePath == null ? null : "tensorflow/" + relativePath;
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path);
        }
    }
}
