package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DetectOptions;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import java.util.stream.Collectors;

/**
* 车牌检测器，从图像中定位车牌位置并返回边界框。
*
* @author CH
* @since 4.0.0.42
 */
public interface PlateDetector {

    /**
    * 创建车牌检测器。
    *
    * @param name 模型名称
    * @return 检测器
     */
    static PlateDetector create(String name) {
        return new DefaultPlateDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.plate.PlateDetector.class);
    }


    /**
    * 创建车牌检测器。
    *
    * @param name    模型名称
    * @param setting 模型配置
    * @return 检测器
     */
    static PlateDetector create(String name, ModelSetting setting) {
        return new DefaultPlateDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
    * 设置检测阈值。
    *
    * @param threshold 阈值
    * @return this
     */
    PlateDetector threshold(float threshold);

    /**
    * 设置模型路径。
    *
    * @param path 路径
    * @return this
     */
    PlateDetector modelPath(String path);

    /**
    * 设置运行设备。
    *
    * @param device 设备
    * @return this
     */
    PlateDetector device(String device);

    /**
    * 检测车牌位置。
    *
    * @param imageData 图像数据
    * @return 车牌框列表
     */
    List<PredictRectangle> detect(byte[] imageData);

    /**
    * 检测车牌详细信息。
    *
    * @param imageData 图像数据
    * @return 检测信息列表
     */
    List<DetectionInfo> detectInfo(byte[] imageData);

    /**
    * 检测车牌数量。
    *
    * @param imageData 图像数据
    * @return 车牌数量
     */
    int plateCount(byte[] imageData);
}

/**
* 默认车牌检测器实现。
*
* @author CH
* @since 4.0.0.42
 */
class DefaultPlateDetector implements PlateDetector {

    
    /**
    * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
    * 车牌标签名称。
     */
    private static final String PLATE_LABEL = "plate";

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
    private final ModelSetting setting;

    /**
    * 检测阈值。
     */
    private Float threshold;

    /**
    * 模型路径。
     */
    private String modelPath;

    /**
    * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
    * 构造默认车牌检测器。
    *
    * @param engine    识别引擎
    * @param modelName 模型名称
    * @param setting   模型配置
     */
    DefaultPlateDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public PlateDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public PlateDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public PlateDetector device(String device) {
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
    public List<PredictRectangle> detect(byte[] imageData) {
        ITranslator<byte[], List<PredictRectangle>> t =
                (ITranslator<byte[], List<PredictRectangle>>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    /** detect信息 */
    public List<DetectionInfo> detectInfo(byte[] imageData) {
        return detect(imageData).stream()
                .map(r -> new DetectionInfo(
                        PLATE_LABEL,
                        r.confidence(),
                        r.x(), r.y(),
                        r.width(), r.height()))
                .collect(Collectors.toList());
    }

    @Override
    /** 铭牌计算数量 */
    public int plateCount(byte[] imageData) {
        return detect(imageData).size();
    }
}
