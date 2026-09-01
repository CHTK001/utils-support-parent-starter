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

    /** 运行参数键：置信度阈值 */
    public static final String KEY_THRESHOLD = "threshold";

    /** 运行参数键：NMS IOU 阈值 */
    public static final String KEY_IOU_THRESHOLD = "iouThreshold";

    /** 是否使用 GPU */
    /** USEGPU */
    private boolean useGpu;

    /** 模型名称 */
    private String modelName;

    /** 覆盖加载的模型名称 */
    /** Load模型名称 */
    private String loadModelName;

    /** 模型文件路径 */
    /** Load模型路径 */
    private String loadModelPath;

    /** 系统级选项（appId、appKey 等） */
    private Map<String, Object> systemOption;

    /** 默认配置实例 */
    /** 默认 */
    public static final DetectionConfiguration DEFAULT = new DetectionConfiguration();

    /** 全局当前配置（静态单例，线程安全） */
    private static volatile DetectionConfiguration current;

    static {
        current = DEFAULT;
    }

    /**
     * 是否使用 GPU。
     *
     * @return 是否使用 GPU
     */
    public boolean deviceIsGpu() {
        return useGpu;
    }

    /**
     * 获取模型名称。
     *
     * @return 模型名称
     */
    public String modelName() {
        return modelName;
    }

    /**
     * 获取加载模型名称，未指定时回退为模型名称。
     *
     * @return 加载模型名称
     */
    public String loadModelName() {
        return loadModelName != null ? loadModelName : modelName;
    }

    /**
     * 获取模型文件路径。
     *
     * @return 模型文件路径
     */
    public String loadModelPath() {
        return loadModelPath;
    }

    /**
     * 获取系统级选项。
     *
     * @return 系统级选项
     */
    public Map<String, Object> systemOption() {
        return systemOption;
    }

    /**
     * 读取浮点运行参数。
     *
     * @param key 键（如 {@link #KEY_THRESHOLD}）
     * @param def 默认值
     * @return 参数值或默认值
     */
    public float optFloat(String key, float def) {
        if (systemOption == null) {
            return def;
        }
        Object v = systemOption.get(key);
        if (v instanceof Number num) {
            return num.floatValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Float.parseFloat(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
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

    /**
     * 设置模型名称。
     *
     * @param m 模型名称
     * @return 当前配置
     */
    public DetectionConfiguration modelName(String m) {
        this.modelName = m;
        return this;
    }

    /**
     * 获取云端认证 AppId。
     *
     * @return AppId，未配置时返回 null
     */
    public String optAppId() {
        if (systemOption == null) {
            return null;
        }
        Object v = systemOption.get("appId");
        return v != null ? v.toString() : null;
    }

    /**
     * 获取云端认证 AppKey。
     *
     * @return AppKey，未配置时返回 null
     */
    public String optAppKey() {
        if (systemOption == null) {
            return null;
        }
        Object v = systemOption.get("appKey");
        return v != null ? v.toString() : null;
    }

    /**
     * 获取当前全局配置。
     *
     * @return 当前 DetectionConfiguration
     */
    public static DetectionConfiguration get() {
        return current;
    }

    /**
     * 设置全局配置。所有模型共享此配置，调用一次即可全局生效。
     *
     * @param config 全局配置
     */
    public static void set(DetectionConfiguration config) {
        if (config != null) {
            current = config;
        }
    }

    /**
     * 基于当前配置创建 Builder。
     *
     * @return Builder 实例
     */
    public DetectionConfigurationBuilder toBuilder() {
        return new DetectionConfigurationBuilder(this);
    }

    /**
     * 检测配置 Builder。
     */
    public static class DetectionConfigurationBuilder {

        /**
         * 待构建的配置实例
         */
        private final DetectionConfiguration c = new DetectionConfiguration();

        /**
         * 创建空 Builder。
         */
        public DetectionConfigurationBuilder() {
        }

        /**
         * 基于已有配置创建 Builder。
         *
         * @param src 已有配置
         */
        public DetectionConfigurationBuilder(DetectionConfiguration src) {
            c.useGpu = src.useGpu;
            c.modelName = src.modelName;
            c.loadModelName = src.loadModelName;
            c.loadModelPath = src.loadModelPath;
            c.systemOption = src.systemOption;
        }

        /**
         * 设置是否使用 GPU。
         *
         * @param g 是否使用 GPU
         * @return 当前 Builder
         */
        public DetectionConfigurationBuilder useGpu(boolean g) {
            c.useGpu = g;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * @param m 模型名称
         * @return 当前 Builder
         */
        public DetectionConfigurationBuilder modelName(String m) {
            c.modelName = m;
            return this;
        }

        /**
         * 设置加载模型名称。
         *
         * @param m 加载模型名称
         * @return 当前 Builder
         */
        public DetectionConfigurationBuilder loadModelName(String m) {
            c.loadModelName = m;
            return this;
        }

        /**
         * 设置模型文件路径。
         *
         * @param p 模型文件路径
         * @return 当前 Builder
         */
        public DetectionConfigurationBuilder loadModelPath(String p) {
            c.loadModelPath = p;
            return this;
        }

        /**
         * 设置系统级选项。
         *
         * @param o 系统级选项
         * @return 当前 Builder
         */
        public DetectionConfigurationBuilder systemOption(Map<String, Object> o) {
            c.systemOption = o;
            return this;
        }

        /**
         * 构建检测配置。
         *
         * @return 检测配置实例
         */
        public DetectionConfiguration build() {
            return c;
        }
    }
}
