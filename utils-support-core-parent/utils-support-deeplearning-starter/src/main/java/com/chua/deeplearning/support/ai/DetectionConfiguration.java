package com.chua.deeplearning.support.ai;

import java.util.Map;

/**
 * 检测/推理配置。
 * <p>
 * 定义模型加载、设备选择、以及云端认证等通用参数。
 * 所有模型引擎（onnx-starter、pytorch-starter 等）共享同一份配置。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DetectionConfiguration {

    /** 默认配置实例 */
    public static final DetectionConfiguration DEFAULT = new DetectionConfiguration();

    /** 是否使用 GPU */
    private boolean useGpu;

    /** 模型名称 */
    private String modelName;

    /** 覆盖加载的模型名称 */
    private String loadModelName;

    /** 模型文件路径 */
    private String loadModelPath;

    /** 系统级选项（appId、appKey 等） */
    private Map<String, Object> systemOption;

    public boolean deviceIsGpu() {
        return useGpu;
    }

    public String modelName() {
        return modelName;
    }

    public String loadModelName() {
        return loadModelName != null ? loadModelName : modelName;
    }

    public String loadModelPath() {
        return loadModelPath;
    }

    public Map<String, Object> systemOption() {
        return systemOption;
    }

    /**
     * 优先返回 loadModelName，否则返回默认值。
     *
     * @param def 兜底值
     * @return 模型名称
     */
    public String getModelNameAndDefault(String def) {
        return loadModelName != null ? loadModelName : def;
    }

    public DetectionConfiguration modelName(String m) {
        this.modelName = m;
        return this;
    }

    public String optAppId() {
        if (systemOption == null) {
            return null;
        }
        Object v = systemOption.get("appId");
        return v != null ? v.toString() : null;
    }

    public String optAppKey() {
        if (systemOption == null) {
            return null;
        }
        Object v = systemOption.get("appKey");
        return v != null ? v.toString() : null;
    }

    public DetectionConfigurationBuilder toBuilder() {
        return new DetectionConfigurationBuilder(this);
    }

    /** Builder */
    public static class DetectionConfigurationBuilder {

        private final DetectionConfiguration c = new DetectionConfiguration();

        public DetectionConfigurationBuilder() {
        }

        public DetectionConfigurationBuilder(DetectionConfiguration src) {
            c.useGpu = src.useGpu;
            c.modelName = src.modelName;
            c.loadModelName = src.loadModelName;
            c.loadModelPath = src.loadModelPath;
            c.systemOption = src.systemOption;
        }

        public DetectionConfigurationBuilder useGpu(boolean g) {
            c.useGpu = g;
            return this;
        }

        public DetectionConfigurationBuilder modelName(String m) {
            c.modelName = m;
            return this;
        }

        public DetectionConfigurationBuilder loadModelName(String m) {
            c.loadModelName = m;
            return this;
        }

        public DetectionConfigurationBuilder loadModelPath(String p) {
            c.loadModelPath = p;
            return this;
        }

        public DetectionConfigurationBuilder systemOption(Map<String, Object> o) {
            c.systemOption = o;
            return this;
        }

        public DetectionConfiguration build() {
            return c;
        }
    }
}
