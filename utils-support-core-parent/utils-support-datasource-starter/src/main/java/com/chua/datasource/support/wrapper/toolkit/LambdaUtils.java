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
}