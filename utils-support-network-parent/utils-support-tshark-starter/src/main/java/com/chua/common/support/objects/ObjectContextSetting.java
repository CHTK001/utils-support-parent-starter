package com.chua.common.support.objects;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象上下文配置项，控制 SPI 加载、注解扫描与扫描包路径。
 * <p>使用 {@link #builder()} 创建配置实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ObjectContextSetting {

    /**
     * 是否启用 SPI 加载
     */
    private boolean spiEnabled;

    /**
     * 是否启用注解扫描
     */
    private boolean annotationScanEnabled;

    /**
     * 注解扫描的包路径列表
     */
    private final List<String> scanPackages = new ArrayList<>();

    /**
     * @return Builder 实例
     */
    public static ObjectContextSettingBuilder builder() {
        return new ObjectContextSettingBuilder();
    }

    public boolean isSpiEnabled() { return spiEnabled; }
    public ObjectContextSetting setSpiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
    public boolean isAnnotationScanEnabled() { return annotationScanEnabled; }
    public ObjectContextSetting setAnnotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
    public List<String> getScanPackages() { return scanPackages; }
    public ObjectContextSetting addScanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }

    /**
     * ObjectContextSetting 构建器。
     */
    public static class ObjectContextSettingBuilder {

        /**
         * 是否启用 SPI 加载
         */
        private boolean spiEnabled;

        /**
         * 是否启用注解扫描
         */
        private boolean annotationScanEnabled;

        /**
         * 注解扫描的包路径列表
         */
        private final List<String> scanPackages = new ArrayList<>();

        public ObjectContextSettingBuilder spiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
        public ObjectContextSettingBuilder annotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
        public ObjectContextSettingBuilder scanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }

        /**
         * 构造 ObjectContextSetting 实例。
         *
         * @return 新配置实例
         */
        public ObjectContextSetting build() {
            ObjectContextSetting setting = new ObjectContextSetting();
            setting.setSpiEnabled(spiEnabled);
            setting.setAnnotationScanEnabled(annotationScanEnabled);
            setting.getScanPackages().addAll(scanPackages);
            return setting;
        }
    }
}
