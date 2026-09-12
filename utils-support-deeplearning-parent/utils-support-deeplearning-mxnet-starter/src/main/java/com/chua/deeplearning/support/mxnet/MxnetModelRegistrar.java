package com.chua.deeplearning.support.mxnet;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;

/**
   * mxnet 模块模型集中注册器。
 * <p>
   * 通过 SPI 被主框架加载；relative路径 相对 模型 根目录，统一 mxnet/ 前缀。
   * 支持 mxnet 模型符号文件与参数文件。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MxnetModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    /** 注册 */
    public void register(ModelRegistry registry) {
        registerAll();
    }

    /** 注册全部 */
    private static void registerAll() {
 // 图像分类 - inceptionv3
        reg("mxnet-inceptionv3",
                "com.chua.deeplearning.support.mxnet.translator.InceptionV3ActionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/inceptionv3");

        // 图像分类 - VGG16
        reg("mxnet-vgg16",
                "com.chua.deeplearning.support.mxnet.translator.Vgg16ActionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/vgg16");
    }

    /**
     * Reg
     * @param modelId 模型标识
     * @param translatorClassName translator类名称
     * @param inputType 输入类型
     * @param outputType 输出类型
     * @param capability capability
     * @param relativePath relative路径
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            String path = relativePath == null ? null : "mxnet/" + relativePath;
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path);
        }
    }
}
