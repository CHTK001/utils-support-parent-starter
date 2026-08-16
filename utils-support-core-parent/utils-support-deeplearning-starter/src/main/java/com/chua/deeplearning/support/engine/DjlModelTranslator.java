package com.chua.deeplearning.support.engine;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * DJL 翻译器包装。
 * <p>将 DJL Translator 适配为框架 {@link ITranslator}，并按路径选择引擎。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DjlModelTranslator implements ITranslator<Object, Object>, AutoCloseable {

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型工厂。
     */
    private final DjlModelFactory factory;

    /**
     * DJL Translator 输入是否为图像（processInput 第一参数为 {@link Image}）。
     */
    private final boolean imageInput;

    /**
     * 构造翻译器（自动推断引擎）。
     *
     * @param modelName     模型名称
     * @param modelPath     模型路径
     * @param djlTranslator DJL Translator
     */
    public DjlModelTranslator(String modelName, Path modelPath, Translator<?, ?> djlTranslator) {
        this(modelName, modelPath, null, djlTranslator);
    }

    /**
     * 构造翻译器。
     *
     * @param modelName     模型名称
     * @param modelPath     模型路径
     * @param engineName    引擎名称
     * @param djlTranslator DJL Translator
     */
    public DjlModelTranslator(String modelName, Path modelPath, String engineName, Translator<?, ?> djlTranslator) {
        this.modelName = modelName;
        this.factory = new DjlModelFactory(modelName, modelPath, engineName, () -> djlTranslator);
        this.imageInput = isImageInput(djlTranslator);
        if (imageInput) {
            log.info("[deeplearning-engine] DJL 模型 {} 输入类型为 Image，启用 byte[] 自动转换", modelName);
        }
    }

    /**
     * 判断 DJL Translator 的 processInput 是否接收 {@link Image}。
     *
     * @param djlTranslator DJL Translator
     * @return true 表示图像输入
     */
    private static boolean isImageInput(Translator<?, ?> djlTranslator) {
        if (djlTranslator == null) {
            return false;
        }
        // 泛型接口会生成桥接方法 processInput(TranslatorContext, Object)，
        // 需遍历找到参数类型最具体（非 Object）的真实签名
        Class<?> bestParam = null;
        for (Method method : djlTranslator.getClass().getMethods()) {
            if ("processInput".equals(method.getName()) && method.getParameterCount() == 2) {
                Class<?> paramType = method.getParameterTypes()[1];
                if (Image.class.isAssignableFrom(paramType)) {
                    log.info("[deeplearning-engine] DJL 模型 {} 输入类型为 Image（参数: {}）",
                            djlTranslator.getClass().getName(), paramType.getName());
                    return true;
                }
                if (bestParam == null && !Object.class.equals(paramType)) {
                    bestParam = paramType;
                }
            }
        }
        log.info("[deeplearning-engine] DJL 模型 {} 的 processInput 参数类型: {}，非图像输入",
                djlTranslator.getClass().getName(), bestParam == null ? "Object" : bestParam.getName());
        return false;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public Object translate(Object input) {
        Object result;
        if (imageInput && input instanceof byte[] bytes) {
            try {
                result = factory.predict(ImageFactory.getInstance().fromInputStream(new ByteArrayInputStream(bytes)));
            } catch (Exception e) {
                log.error("[deeplearning-engine] DJL 图像转换失败: {}", modelName, e);
                throw new RuntimeException("DJL 图像转换失败: " + modelName, e);
            }
        } else {
            result = factory.predict(input);
        }
        return adaptOutput(result);
    }

    /**
     * 将 DJL 推理输出适配为框架类型。
     * <p>DJL 检测模型（如 SCRFD/YOLO）返回 {@link DetectedObjects}，适配为
     * {@code List<PredictRectangle>}；分类模型返回 {@link Classifications}，适配为
     * 最可能类别名（String）。非这两种输出原样返回。</p>
     *
     * @param result DJL 推理输出
     * @return 适配后的输出
     */
    private Object adaptOutput(Object result) {
        if (result instanceof DetectedObjects detected) {
            List<PredictRectangle> boxes = detected.items().stream()
                    .map(item -> {
                        if (item instanceof ai.djl.modality.cv.output.DetectedObjects.DetectedObject det) {
                            BoundingBox box = det.getBoundingBox();
                            Rectangle bounds = box.getBounds();
                            return new PredictRectangle(
                                    (float) bounds.getX(),
                                    (float) bounds.getY(),
                                    (float) bounds.getWidth(),
                                    (float) bounds.getHeight(),
                                    (float) det.getProbability(),
                                    0,
                                    det.getClassName());
                        }
                        return new PredictRectangle(0, 0, 0, 0,
                                (float) item.getProbability(), 0, String.valueOf(item.getClassName()));
                    })
                    .toList();
            log.debug("[deeplearning-engine] DJL 模型 {} 检测到 {} 个目标，适配为 List<PredictRectangle>",
                    modelName, boxes.size());
            return boxes;
        }
        if (result instanceof ai.djl.modality.Classifications classifications) {
            // 分类输出 → 最可能类别名（业务接口 ImageClassifier 期望 String）
            List<ai.djl.modality.Classifications.Classification> items = classifications.items();
            if (items != null && !items.isEmpty()) {
                String top = String.valueOf(items.get(0).getClassName());
                log.debug("[deeplearning-engine] DJL 模型 {} 分类结果: {}", modelName, top);
                return top;
            }
            return "";
        }
        return result;
    }

    @Override
    public void close() {
        factory.close();
    }
}
