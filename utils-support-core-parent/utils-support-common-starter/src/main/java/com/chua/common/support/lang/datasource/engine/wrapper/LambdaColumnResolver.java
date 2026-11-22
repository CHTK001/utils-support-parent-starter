// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.lang.datasource.engine.wrapper;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Lambda 方法引用 → 数据库列名 解析器。
 *
 * <p>通过反射调用 lambda 隐藏类的私有 {@code writeReplace} 方法取得
 * {@link SerializedLambda}，再从实现方法名推导属性名并转为下划线风格列名。
 * {@code writeReplace} 的 {@link Method} 句柄按 lambda 类缓存——同一调用点的
 * lambda 类在 JVM 侧稳定不变，缓存后可消除每次解析都重复执行的
 * {@code getDeclaredMethod} 与 {@code setAccessible}；缓存为软引用实现，
 * 不会阻止 lambda 隐藏类被回收。</p>
 *
 * <p>原先 {@link LambdaQueryWrapper}、{@link LambdaUpdateWrapper}、
 * {@link LambdaDeleteWrapper} 各自持有一份完全相同的实现，现收敛到本类
 * （规约 R4：同一能力只保留一份实现）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class LambdaColumnResolver {

    /**
     * lambda 类 → writeReplace 方法句柄缓存（软引用键值，防隐藏类泄漏）。
     */
    private static final Map<Class<?>, Method> WRITE_REPLACE_CACHE = new ConcurrentReferenceHashMap<>(64);

    /**
     * 工具类，禁止实例化。
     */
    private LambdaColumnResolver() {
    }

    /**
     * 将 Lambda 方法引用解析为数据库列名（下划线风格）。
     *
     * <p>实现方法名去掉 {@code get}/{@code is} 前缀得到属性名，再按驼峰转下划线，
     * 例如 {@code User::getDeptId} 解析为 {@code dept_id}。</p>
     *
     * @param column Lambda 方法引用，不能为 null
     * @return 数据库列名（下划线风格）
     * @throws IllegalArgumentException 方法引用无法解析时抛出
     */
    public static String toColumnName(SFunction<?, ?> column) {
        try {
            Class<?> lambdaClass = column.getClass();
            Method writeReplace = WRITE_REPLACE_CACHE.get(lambdaClass);
            if (null == writeReplace) {
                // [P3C 1.10 豁免] writeReplace 为 lambda 隐藏类的私有方法，需精确方法句柄，无法用 ReflectUtils 替代
                writeReplace = lambdaClass.getDeclaredMethod("writeReplace");
                // [P3C 1.10 豁免] writeReplace 需 setAccessible 后方可在隐藏类外调用
                writeReplace.setAccessible(true);
                WRITE_REPLACE_CACHE.put(lambdaClass, writeReplace);
            }
            // [P3C 1.10 豁免] 同上：writeReplace 为 lambda 私有方法，ReflectUtils 公共 LOOKUP 无法访问
            SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(column);
            String methodName = lambda.getImplMethodName();
            String field = methodName.startsWith("is") ? methodName.substring(2)
                    : methodName.startsWith("get") ? methodName.substring(3) : methodName;
            var sb = new StringBuilder();
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
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Lambda 列: " + column, e);
        }
    }
}
