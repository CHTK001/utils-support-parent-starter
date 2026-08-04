package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ViewDef;
import org.jspecify.annotations.NullUnmarked;

/**
 * 建视图链式构建器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface ViewCreateBuilder {

    /**
     * 设置视图定义（SELECT 语句）。
     *
     * @param definition SELECT 语句
     * @return this
     */
    ViewCreateBuilder definition(String definition);

    /**
     * 使用 CREATE OR REPLACE 语义（如果数据库支持）。
     *
     * @return this
     */
    ViewCreateBuilder orReplace();

    /**
     * 设置视图注释。
     *
     * @param comment 注释内容
     * @return this
     */
    ViewCreateBuilder comment(String comment);

    /**
     * 设置视图为可更新（如果数据库支持）。
     *
     * @return this
     */
    ViewCreateBuilder updatable();

    /**
     * 设置检查选项（CASCADE / LOCAL / NONE，仅部分数据库支持）。
     *
     * @param checkOption 检查选项
     * @return this
     */
    ViewCreateBuilder checkOption(String checkOption);

    /**
     * 执行建视图语句。
     *
     * @return 视图定义
     */
    ViewDef execute();
}
