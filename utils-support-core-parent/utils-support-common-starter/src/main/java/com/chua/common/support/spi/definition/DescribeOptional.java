package com.chua.common.support.spi.definition;

import lombok.Data;

/**
 * SPI 可选参数描述对象，用于承载扩展点参数的名称、默认值、说明及类型信息。
 * <p>
 * 该类主要用于描述服务提供者或配置项的可选参数，便于在 SPI 解析和文档展示场景中统一传递元数据。
 *
 * @author CH
 */
@Data
public class DescribeOptional {

    /**
     * 参数名称
     */
    private String name;
    /**
     * 默认值
     */
    private String defaultValue;
    /**
     * 参数描述
     */
    private String describe;
    /**
     * 参数类型
     */
    private String type;

    /**
     * 创建一个空的可选参数描述对象。
     */
    public DescribeOptional() {
    }

    /**
     * 创建一个完整的可选参数描述对象。
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @param describe 参数描述
     * @param type 参数类型
     */
    public DescribeOptional(String name, String defaultValue, String describe, String type) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.describe = describe;
        this.type = type;
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
     * 设置参数名称。
     *
     * @param name 参数名称
     */
    public void setName(String name) {
        this.name = name;
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
     * 设置参数默认值。
     *
     * @param defaultValue 默认值
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
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
     * 设置参数描述。
     *
     * @param describe 参数描述
     */
    public void setDescribe(String describe) {
        this.describe = describe;
    }

    /**
     * 获取参数类型。
     *
     * @return 参数类型
     */
    public String getType() {
        return type;
    }

    /**
     * 设置参数类型。
     *
     * @param type 参数类型
     */
    public void setType(String type) {
        this.type = type;
    }
}
