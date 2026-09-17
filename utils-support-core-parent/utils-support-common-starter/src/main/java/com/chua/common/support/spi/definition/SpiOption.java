package com.chua.common.support.spi.definition;


/**
 * SPI 选项元数据，描述一个 SPI 扩展点所支持的配置项。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>在 SPI 扩展点描述中暴露支持的配置项名称与说明</li>
 *     <li>对外提供扩展点支持的类型列表，便于动态校验与文档生成</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class SpiOption {

    /**
    * 选项名称
    */
    private String name;

    /**
    * 选项描述
    */
    private String describe;

    /**
    * 选项支持的类型列表
    */
    private String[] supportedTypes;

    /**
    * 默认构造方法。
    */
    public SpiOption() {
    }

    /**
    * 全参构造方法。
    *
    * @param name           选项名称
    * @param describe       选项描述
    * @param supportedTypes 选项支持的类型列表
    */
    public SpiOption(String name, String describe, String[] supportedTypes) {
        this.name = name;
        this.describe = describe;
        this.supportedTypes = supportedTypes;
    }

    /**
    * 工厂方法，快速创建 spi期权 实例。
    *
    * @param name           选项名称
    * @param describe       选项描述
    * @param supportedTypes 选项支持的类型列表
    * @return SpiOption 实例
    */
    public static SpiOption of(String name, String describe, String[] supportedTypes) {
        return new SpiOption(name, describe, supportedTypes);
    }

    /**
    * 获取选项名称（链式风格）。
    *
    * @return 选项名称
    */
    public String name() {
        return name;
    }

    /**
    * 获取选项名称。
    *
    * @return 选项名称
    */
    public String getName() {
        return name;
    }

    /**
    * 设置选项名称。
    *
    * @param name 选项名称
    */
    public void setName(String name) {
        this.name = name;
    }

    /**
    * 获取选项描述（链式风格）。
    *
    * @return 选项描述
    */
    public String describe() {
        return describe;
    }

    /**
    * 获取选项描述。
    *
    * @return 选项描述
    */
    public String getDescribe() {
        return describe;
    }

    /**
    * 设置选项描述。
    *
    * @param describe 选项描述
    */
    public void setDescribe(String describe) {
        this.describe = describe;
    }

    /**
    * 获取选项支持的类型列表（链式风格）。
    *
    * @return 选项支持的类型列表
    */
    public String[] supportedTypes() {
        return supportedTypes;
    }

    /**
    * 获取选项支持的类型列表。
    *
    * @return 选项支持的类型列表
    */
    public String[] getSupportedTypes() {
        return supportedTypes;
    }

    /**
    * 设置选项支持的类型列表。
    *
    * @param supportedTypes 选项支持的类型列表
    */
    public void setSupportedTypes(String[] supportedTypes) {
        this.supportedTypes = supportedTypes;
    }
}
