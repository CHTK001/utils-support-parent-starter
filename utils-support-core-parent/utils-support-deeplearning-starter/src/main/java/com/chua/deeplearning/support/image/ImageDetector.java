package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 图像检测器，检测图像中的目标物体并返回边界框和类别信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageDetector {

    /**
     * 创建图像检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageDetector create(String provider, String apiKey) {
        return ServiceProvider.of(ImageDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default ImageDetector provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageDetector model(String model) {
        return this;
    }

    /**
     * 创建
     *
     * @param name 名称
     * @return 创建的结果
     */
    static ImageDetector create(String name) {
        return new DefaultImageDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageDetector.class);
    }


    /**
     * 创建图像检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static ImageDetector create(String name, ModelSetting setting) {
        return new DefaultImageDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置检测阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    ImageDetector threshold(float threshold);

    /**
     * 设置 NMS 阈值。
     *
     * @param nms NMS 阈值
     * @return this
     */
    ImageDetector nms(float nms);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ImageDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    ImageDetector device(String device);

    /**
     * 检测目标。
     *
     * @param imageData 图像数据
     * @return 检测信息列表
     */
    List<DetectionInfo> detect(byte[] imageData);
}

/**
 * 默认图像检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageDetector implements ImageDetector {

    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";



    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型配置。
     */
    @SuppressWarnings("unused")
    /** 设置 */
    private final ModelSetting setting;

    /**
    * 检测阈值（空 表示未显式设置，使用各模型自身默认值）。
    */
    private Float threshold;

    /**
     * NMS 阈值（空 表示未显式设置，使用各模型自身默认值）。
     */
    private Float nms;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造默认图像检测器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultImageDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
    }

    @Override
    /** 阈值 */
    public ImageDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** Nms */
    public ImageDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    /** 模型路径 */
    public ImageDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public ImageDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Detect
     *
     * @param imageData 镜像数据
     * @return detect的结果
     */
    public List<DetectionInfo> detect(byte[] imageData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class, detectOptions());
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(imageData);
        if (result == null) {
            return List.of();
        }
        if (result instanceof List<?> list) {
            List<DetectionInfo> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof DetectionInfo info) {
                    out.add(info);
                } else if (item instanceof PredictRectangle pr) {
                    out.add(new DetectionInfo(
                            pr.labelName() == null || pr.labelName().isBlank() ? "detected" : pr.labelName(),
                            pr.confidence(),
                            pr.x(), pr.y(), pr.width(), pr.height()));
                }
            }
            return out;
        }
        throw new IllegalStateException("模型输出不是检测结果: " + modelName + " -> " + result.getClass());
    }

    /**
     * 汇总显式设置的运行参数（未设置的键不出现，保留各模型默认值）。
     *
     * @return 运行参数（可能为空 映射）
     */
    private java.util.Map<String, Object> detectOptions() {
        return com.chua.deeplearning.support.engine.DetectOptions.of(threshold, nms);
    }
}
