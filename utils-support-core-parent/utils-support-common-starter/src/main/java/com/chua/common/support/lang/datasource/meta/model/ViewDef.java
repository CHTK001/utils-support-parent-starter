package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 视图定义，描述数据库中的一个视图。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewDef {

    /**
    * 视图名
     */
    private String name;

    /**
    * catalog 名称
     */
    private String catalog;

    /**
    * schema 名称
     */
    private String schema;

    /**
    * 视图定义（SELECT 语句）
     */
    private String definition;

    /**
    * 是否可更新
     */
    private boolean updatable;

    /**
    * 检查选项（CASCADE / LOCAL / NONE 等，仅 Oracle）
     */
    private String checkOption;

    /**
    * 视图注释
     */
    private String comment;
}
