package com.chua.deeplearning.support.onnx.style;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import com.chua.common.support.image.filter.ImageFilter;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 风格迁移图像滤镜（Fast Neural Style Transfer）
 * <p>
 * 基于 Fast Neural Style Transfer ONNX 模型的图像风格迁移滤镜，
 * 支持以下 5 种艺术风格：
 * <ul>
 *   <li>candy - 糖果风格</li>
 *   <li>mosaic - 马赛克风格</li>
 *   <li>rain-princess - 雨中公主风格</li>
 *   <li>udnie - 抽象风格</li>
 *   <li>pointilism - 点彩派/卜利吉风格</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>
 * // 通过 SPI 创建
 * ImageFilter filter = ServiceProvider.of(ImageFilter.class).getExtension("style-candy");
 * BufferedImage result = filter.converter(inputImage);
 *
 * // 直接创建
 * StyleTransferImageFilter filter = new StyleTransferImageFilter("style-candy");
 * BufferedImage result = filter.converter(inputImage);
 * </pre>
 * <p>
 * 依赖：需要引入对应的 models-parent 模块（如 utils-support-models-onnx-style-candy）
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
@Spi({"style-candy", "style-mosaic", "style-rain-princess", "style-udnie", "style-pointilism"})
@SpiDescribe("Fast Neural Style Transfer 风格迁移滤镜")
public class StyleTransferImageFilter implements ImageFilter {

    /**
     * 支持的风格模型名称
     */
    private static final String[] STYLE_NAMES = {
            "style-candy", "style-mosaic", "style-rain-princess", "style-udnie", "style-pointilism"
    };

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 默认构造函数（SPI 加载用，默认使用 candy 风格）
     */
    public StyleTransferImageFilter() {
        this("style-candy");
    }

    /**
     * 构造函数
     *
     * @param modelName 模型名称（如 style-candy, style-mosaic 等）
     */
    public StyleTransferImageFilter(String modelName) {
        this.modelName = modelName;
        this.engine = AbstractIdentificationEngine.getInstance();
        log.info("风格迁移滤镜初始化: {}", modelName);
    }

    /**
     * 获取当前使用的模型名称
     *
     * @return 模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取所有支持的风格名称
     *
     * @return 风格名称数组
     */
    public static String[] getSupportedStyles() {
        return STYLE_NAMES.clone();
    }

    @Override
    /** Converter */
    public BufferedImage converter(BufferedImage image) throws Exception {
        if (image == null) {
            throw new IllegalArgumentException("输入图像不能为空");
        }

        if (log.isDebugEnabled()) {
            log.debug("风格迁移开始: model={}, inputSize={}x{}", modelName, image.getWidth(), image.getHeight());
        }

        try {
            // 1. BufferedImage → byte[]
            byte[] imageData = toBytes(image, "png");

            // 2. 通过引擎获取 Translator 并推理
            ITranslator<Object, Object> translator = engine.get(modelName, ITranslator.class);
            if (translator == null) {
                throw new IllegalStateException("风格迁移模型未注册: " + modelName
                        + "，请确保引入了对应的 models-parent 模块");
            }

            Object result = translator.translate(imageData);

            // 3. 结果转换：DJL Image → BufferedImage
            BufferedImage outputImage;
            if (result instanceof Image djlImage) {
                outputImage = djlImageToBufferedImage(djlImage);
            } else if (result instanceof BufferedImage bi) {
                outputImage = bi;
            } else if (result instanceof byte[] bytes) {
                outputImage = ImageUtils.toBufferedImage(bytes);
            } else {
                throw new RuntimeException("风格迁移模型返回了不支持的类型: "
                        + (result != null ? result.getClass().getName() : "null"));
            }

            if (log.isDebugEnabled()) {
                log.debug("风格迁移完成: model={}, outputSize={}x{}",
                        modelName, outputImage.getWidth(), outputImage.getHeight());
            }

            return outputImage;

        } catch (Exception e) {
            log.error("风格迁移失败: model={}", modelName, e);
            throw e;
        }
    }

    @Override
    /** Converter */
    public OutputStream converter(InputStream image) throws Exception {
        BufferedImage inputImage = ImageUtils.toBufferedImage(ImageUtils.decode(image.readAllBytes()));
        BufferedImage outputImage = converter(inputImage);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(ImageUtils.encode(ImageUtils.toMat(outputImage), getImageFormat()));
        return baos;
    }

    @Override
    /** 获取Image格式化 */
    public String getImageFormat() {
        return "png";
    }

    @Override
    /** 获取Image格式化 */
    public String getImageFormat(String name) {
        return name != null ? name : getImageFormat();
    }

    /**
     * 将 BufferedImage 转换为字节数组
     *
     * @param image  图像
     * @param format 格式（如 "png", "jpg"）
     * @return 字节数组
     * @throws IOException IO 异常
     */
    private static byte[] toBytes(BufferedImage image, String format) throws IOException {
        return ImageUtils.encode(ImageUtils.toMat(image), format);
    }

    /**
     * 将 DJL Image 转换为 BufferedImage
     *
     * @param djlImage DJL Image 对象
     * @return BufferedImage
     */
    private static BufferedImage djlImageToBufferedImage(Image djlImage) {
        // DJL Image 的 getWrappedImage() 返回底层 OpenCV/BufferedImage 对象
        // 或通过 toNDArray → BufferedImage 转换
        try {
            // 尝试直接获取 BufferedImage
            Object wrapped = djlImage.getWrappedImage();
            if (wrapped instanceof BufferedImage bi) {
                return bi;
            }
        } catch (Exception ignored) {
            // 某些 DJL 实现可能不支持 getWrappedImage
        }

        // 回退方案：通过 NDArray 转换
        try (var manager = ai.djl.ndarray.NDManager.newBaseManager()) {
            ai.djl.ndarray.NDArray array = djlImage.toNDArray(manager);
            // NHWC → BufferedImage
            array = array.clip(0, 255).toType(ai.djl.ndarray.types.DataType.UINT8, false);
            Image result = ImageFactory.getInstance().fromNDArray(array);
            Object wrapped = result.getWrappedImage();
            if (wrapped instanceof BufferedImage bi) {
                return bi;
            }
        } catch (Exception e) {
            log.warn("DJL Image 转 BufferedImage 失败", e);
        }

        throw new RuntimeException("无法将 DJL Image 转换为 BufferedImage");
    }
}