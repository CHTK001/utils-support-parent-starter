package com.chua.datasource.support.wrapper.toolkit;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaColumnResolver;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;

/**
 * Lambda 解析工具类，将 sfunction 方法引用解析为属性名/列名。
 *
 * <p>解析与缓存逻辑收敛到 common 层的 {@link LambdaColumnResolver}
 * （规约 R4：同一能力只保留一份实现）；本类仅做语义适配——
 * 解析失败时返回 {@code null} 而非抛出异常，保持既有调用方契约。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LambdaUtils {

    /**
     * 工具类，禁止实例化。
     */
    private LambdaUtils() {
    }

    /**
     * 解析 sfunction 方法引用为属性名。
     *
     * @param func 方法引用
     * @param <T>  实体类型
     * @return 属性名（驼峰），解析失败返回 null
     */
    public static <T> String resolveObject(SFunction<T, ?> func) {
        try {
            return LambdaColumnResolver.toPropertyName(func);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析 sfunction 方法引用为数据库列名（下划线风格）。
     *
     * <p>先解析出驼峰属性名（如 {@code deptId}），再转为数据库列名
     * （如 {@code dept_id}），供 JDBC 引擎的 wrapper 直接作为 SQL 列名使用。
     * 非关系型引擎（如 Solr/Elasticsearch/Redis/Neo4j）字段名保持驼峰，
     * 应继续使用 {@link #resolveObject(SFunction)}。</p>
     *
     * @param func 方法引用
     * @param <T>  实体类型
     * @return 数据库列名（下划线），解析失败返回 null
     */
    public static <T> String resolveColumn(SFunction<T, ?> func) {
        try {
            return LambdaColumnResolver.toColumnName(func);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
