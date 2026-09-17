package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ViewDef;

/**
* 改视图链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface ViewAlterBuilder {

    /**
    * 替换视图定义（CREATE OR REPLACE VIEW）。
    *
    * @param definition 新的 SELECT 语句
    * @return this
    */
    ViewAlterBuilder definition(String definition);

    /**
    * 重命名视图。
    *
    * @param newName 新视图名
    * @return this
    */
    ViewAlterBuilder renameTo(String newName);

    /**
    * 执行改视图语句。
    *
    * @return 修改后的视图定义
    */
    ViewDef execute();
}
