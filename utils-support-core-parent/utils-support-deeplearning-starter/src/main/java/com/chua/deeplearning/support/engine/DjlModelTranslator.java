package com.chua.deeplearning.support.engine;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
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
    * DJL Translator 输入是否为图像（处理输入 第一参数为 {@link Image}）。
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
    * 构造翻译器（设备跟随全局设置）。
    *
    * @param modelName     模型名称
    * @param modelPath     模型路径
    * @param engineName    引擎名称
    * @param djlTranslator DJL Translator
    */
    public DjlModelTranslator(String modelName, Path modelPath, String engineName, Translator<?, ?> djlTranslator) {
        this(modelName, modelPath, engineName, null, djlTranslator);
    }

    /**
    * 构造翻译器（指定设备设置）。
    *
    * @param modelName      模型名称
    * @param modelPath      模型路径
    * @param engineName     引擎名称
    * @param deviceSetting  设备设置：auto / cpu / gpu / cuda，可为 空
    * @param djlTranslator  DJL Translator
    */
    public DjlModelTranslator(String modelName, Path modelPath, String engineName,
                              String deviceSetting, Translator<?, ?> djlTranslator) {
        this.modelName = modelName;
        this.factory = new DjlModelFactory(modelName, modelPath, engineName, deviceSetting, () -> djlTranslator);
        this.imageInput = isImageInput(djlTranslator);
        if (imageInput) {
            log.info("[deeplearning-engine] DJL 模型 {} 输入类型为 Image，启用 byte[] 自动转换", modelName);
        }
    }

    /**
    * 判断 DJL Translator 的 处理输入 是否接收 {@link Image}。
    *
    * @param djlTranslator DJL Translator
    * @return true 表示图像输入
    */
    private static boolean isImageInput(Translator<?, ?> djlTranslator) {
        if (djlTranslator == null) {
            return false;
        }
        // 泛型接口会生成桥接方法 processInput(TranslatorContext, Object)，
 // 需遍历找到参数类型最具体（非 对象）的真实签名
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
    /** 名称 */
    public String name() {
        return modelName;
    }

    @Override
    /** Translate */
    public Object translate(Object input) {
        Object result;
        int imgW = 0;
        int imgH = 0;
        if (imageInput && input instanceof byte[] bytes) {
            try {
                org.opencv.core.Mat decoded = ImageUtils.decode(bytes);
                if (decoded == null || decoded.empty()) {
                    throw new IllegalArgumentException("无法解码图像字节，OpenCV imdecode 返回空: " + modelName);
                }
                java.awt.image.BufferedImage buffered = ImageUtils.toBufferedImage(decoded);
                decoded.release();
                if (buffered == null) {
                    throw new IllegalArgumentException("无法解码图像字节，转 BufferedImage 返回 null: " + modelName);
                }
                ai.djl.modality.cv.Image img = new ai.djl.modality.cv.BufferedImageFactory().fromImage(buffered);
                imgW = img.getWidth();
                imgH = img.getHeight();
                result = factory.predict(img);
            } catch (Exception e) {
                log.error("[deeplearning-engine] DJL 图像转换失败: {}", modelName, e);
                throw new RuntimeException("DJL 图像转换失败: " + modelName, e);
            }
        } else {
            result = factory.predict(input);
        }
        return adaptOutput(result, imgW, imgH);
    }

    /**
    * 将 DJL 推理输出适配为框架类型。
    * <p>DJL 检测模型（如 SCRFD/YOLO）返回 {@link DetectedObjects}，适配为
    * {@code List<PredictRectangle>}（归一化坐标×图像尺寸转像素）；
    * 分类模型返回 {@link Classifications}，适配为最可能类别名（字符串）。
    * 非这两种输出原样返回。</p>
    *
    * @param result DJL 推理输出
    * @param imgW   输入图像宽（0 表示未知，按原样返回归一化值）
    * @param imgH   输入图像高
    * @return 适配后的输出
    */
    private Object adaptOutput(Object result, int imgW, int imgH) {
        if (result instanceof ai.djl.modality.cv.Image image) {
            // 图像输出模型（人脸修复/超分/抠图等）：转 byte[]（PNG，保留 alpha）
            try {
                Object wrapped = image.getWrappedImage();
                if (wrapped instanceof java.awt.image.BufferedImage bufferedImage) {
                    boolean hasAlpha = bufferedImage.getColorModel().hasAlpha();
                    if (hasAlpha) {
 // 有 alpha 通道，用 镜像io 写 PNG 保留透明
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(bufferedImage, "PNG", baos);
                        return baos.toByteArray();
                    }
                    return ImageUtils.encode(ImageUtils.toMat(bufferedImage));
                }
            } catch (Exception e) {
                log.warn("[deeplearning-engine] DJL 模型 {} 图像输出转 byte[] 失败: {}", modelName, e.getMessage());
            }
            return result;
        }
        if (result instanceof DetectedObjects detected) {
            List<PredictRectangle> boxes = detected.items().stream()
                    .map(item -> {
                        if (item instanceof ai.djl.modality.cv.output.DetectedObjects.DetectedObject det) {
                            BoundingBox box = det.getBoundingBox();
                            Rectangle bounds = box.getBounds();
                            // DJL 的 bounds 为 0~1 归一化坐标，需乘图像尺寸转像素；
 // 关键点 路径 同样归一化，一并转像素（人脸 5 点，用于对齐）
                            float scaleX = imgW > 0 ? imgW : 1f;
                            float scaleY = imgH > 0 ? imgH : 1f;
                            java.util.List<float[]> keypoints = new java.util.ArrayList<>();
                            Iterable<ai.djl.modality.cv.output.Point> path = box.getPath();
                            if (path != null) {
                                for (ai.djl.modality.cv.output.Point p : path) {
                                    keypoints.add(new float[]{(float) (p.getX() * scaleX), (float) (p.getY() * scaleY)});
                                }
                            }
                            return new PredictRectangle(
                                    (float) (bounds.getX() * scaleX),
                                    (float) (bounds.getY() * scaleY),
                                    (float) (bounds.getWidth() * scaleX),
                                    (float) (bounds.getHeight() * scaleY),
                                    (float) det.getProbability(),
                                    0,
                                    det.getClassName(),
                                    keypoints);
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
 // 分类输出 → 最可能类别名（业务接口 镜像classifier 期望 字符串）
            List<ai.djl.modality.Classifications.Classification> items = classifications.items();
            if (items != null && !items.isEmpty()) {
                String top = String.valueOf(items.getFirst().getClassName());
                log.debug("[deeplearning-engine] DJL 模型 {} 分类结果: {}", modelName, top);
                return top;
            }
            return "";
        }
        if (result instanceof com.chua.deeplearning.support.ai.result.PredictResult predictResult) {
 // 通用预测结果 → 字符串标签（表情/年龄等业务接口期望 字符串）
            String value = predictResult.value();
            return value == null ? "" : value;
        }
        return result;
    }

    @Override
    /** 关闭 */
    public void close() {
        factory.close();
    }
}
