package com.chua.common.support.spi.definition;

/**
 * SPI 可选参数描述对象，用于承载扩展点参数的名称、默认值、说明及类型信息。
 * <p>
 * 该类主要用于描述服务提供者或配置项的可选参数，便于在 SPI 解析和文档展示场景中统一传递元数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public record DescribeOptional(String name, String defaultValue, String describe, String type) {

    /**
     * 创建一个空的可选参数描述对象。
     */
    public DescribeOptional() {
        this(null, null, null, null);
    }

    /**
     * 获取参数名称。
     *
     * @return 参数名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取参数默认值。
     *
     * @return 默认值
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 获取参数描述。
     *
     * @return 参数描述
     */
    public String getDescribe() {
        return describe;
    }

    /**
     * 获取参数类型。
     *
     * @return 参数类型
     */
    public String getType() {
        return type;
    }
}
