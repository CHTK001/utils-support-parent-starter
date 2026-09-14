package com.chua.datasource.support.wrapper.toolkit;

import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;

import java.lang.invoke.SerializedLambda;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* Lambda 解析工具类，将 sfunction 方法引用解析为属性名。
*
* @author CH
* @since 4.0.0.42
 */
public class LambdaUtils {

    /**
    * 缓存：类 → (方法引用类 → 属性名)。
     */
    private static final Map<Class<?>, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    /**
    * 解析 sfunction 方法引用为属性名。
    *
    * @param func 方法引用
    * @param <T>  实体类型
    * @return 属性名（驼峰），解析失败返回 空
     */
    public static <T> String resolveObject(SFunction<T, ?> func) {
        try {
            var writeReplace = func.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            var lambda = (SerializedLambda) writeReplace.invoke(func);
            String methodName = lambda.getImplMethodName();
            if (methodName.startsWith("get")) {
                String prop = methodName.substring(3);
                return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
            }
            if (methodName.startsWith("is")) {
                String prop = methodName.substring(2);
                return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
            }
            return methodName;
        } catch (Exception e) {
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
    * @return 数据库列名（下划线），解析失败返回 空
     */
    public static <T> String resolveColumn(SFunction<T, ?> func) {
        String field = resolveObject(func);
        if (field == null || field.isEmpty()) {
            return field;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char ch = field.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}