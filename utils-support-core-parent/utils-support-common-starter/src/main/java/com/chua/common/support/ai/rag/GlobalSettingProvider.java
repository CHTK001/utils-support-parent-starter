package com.chua.common.support.ai.rag;

import java.util.List;

/**
* 全局设置 SPI 接口。
* <p>
* 用于动态获取 RAG 模块的配置项，可通过数据库或配置中心管理。
* 实现类通过 {@link com.chua.common.support.spi.ServiceProvider} 注册。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface GlobalSettingProvider {

    /**
    * 返回设置分组标识，如 "rag"、"ocr"。
    *
    * @return 分组标识
    */
    String group();

    /**
    * 返回设置分组显示名称。
    *
    * @return 分组名称
    */
    String groupName();

    /**
    * 返回该分组下的所有设置项。
    *
    * @return 设置项列表
    */
    List<SettingItem> getItems();

    /**
    * 设置项定义。
    *
    * @param field        字段名
    * @param defaultValue 默认值
    * @param valueType    值类型：STRING / NUMBER / BOOLEAN
    * @param description  描述
    * @param sort         排序序号
    */
    record SettingItem(
            String field,
            String defaultValue,
            String valueType,
            String description,
            int sort
    ) {}
}
