package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;

/**
* 新版 Lambda 查询包装器（stub 版本）。
*
* @param <T> 实体类型
* @author CH
* @since 4.0.0.42
 */
public class LambdaQueryWrapper<T> {

    /**
    * 实体类类型。
    */
    private final Class<T> entityClass;

    /**
    * 自定义 SQL 片段。
    */
    private String customSqlSegment;

    /**
    * 参数值数组。
    */
    private Object[] paramValues;

    /**
    * 创建 lambda查询包装器 实例
    * @param entityClass 实体类
    */
    public LambdaQueryWrapper(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    /**
    * 获取实体类类型。
    *
    * @return 实体类
    */
    public Class<T> getEntityClass() {
        return entityClass;
    }

    /**
    * 获取自定义 SQL 片段。
    *
    * @return SQL 片段
    */
    public String getCustomSqlSegment() {
        return customSqlSegment;
    }

    /**
    * 获取参数值数组。
    *
    * @return 参数值
    */
    public Object[] getParamValues() {
        return paramValues;
    }

    /**
    * 添加 LIKE 条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> like(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加等于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> eq(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加不等于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> ne(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加大于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> gt(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加大于等于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> ge(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加小于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> lt(SFunction<T, ?> column, Object value) {
        return this;
    }

    /**
    * 添加小于等于条件。
    *
    * @param column 列引用
    * @param value  匹配值
    * @return this
    */
    public LambdaQueryWrapper<T> le(SFunction<T, ?> column, Object value) {
        return this;
    }
}
