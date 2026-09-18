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

    /**
    * 是否spi已启用
    *
    * @return 是否spi已启用的结果
    */
    public boolean isSpiEnabled() { return spiEnabled; }
    /**
    * 设置spi已启用
    *
    * @param spiEnabled spi已启用
    * @return 设置spi已启用的结果
    */
    public ObjectContextSetting setSpiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
    /**
    * 是否注解扫描已启用
    *
    * @return 是否注解扫描已启用的结果
    */
    public boolean isAnnotationScanEnabled() { return annotationScanEnabled; }
    /**
    * 设置注解扫描已启用
    *
    * @param annotationScanEnabled 注解扫描已启用
    * @return 设置注解扫描已启用的结果
    */
    public ObjectContextSetting setAnnotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
    /**
    * 获取扫描包
    *
    * @return 获取扫描包的结果
    */
    public List<String> getScanPackages() { return scanPackages; }
    /**
    * 添加扫描包
    *
    * @param scanPackage 扫描包
    * @return 添加扫描包的结果
    */
    public ObjectContextSetting addScanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }

    /**
    * 对象上下文setting 构建器。
    * @author CH
    * @since 4.0.0
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

        /**
        * spi已启用
        *
        * @param spiEnabled spi已启用
        * @return spi已启用的结果
        */
        public ObjectContextSettingBuilder spiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
        /**
        * 注解扫描已启用
        *
        * @param annotationScanEnabled 注解扫描已启用
        * @return 注解扫描已启用的结果
        */
        public ObjectContextSettingBuilder annotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
        /**
        * 扫描包
        *
        * @param scanPackage 扫描包
        * @return 扫描包的结果
        */
        public ObjectContextSettingBuilder scanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }

        /**
        * 构造 对象上下文setting 实例。
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
