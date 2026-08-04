package com.chua.common.support.lang.datasource.engine.wrapper;

import java.io.Serializable;
import java.util.function.Function;
import org.jspecify.annotations.NullUnmarked;

/**
 * 可序列化的函数式接口，用于 Lambda 方法引用到属性名的编译期安全解析。
 * <p>
 * 该接口继承 {@link Function} 和 {@link Serializable}，
 * 使得使用方法引用（如 {@code User::getName}）时，Java 编译器会生成
 * {@code SerializedLambda} 字节码，包含被调用方法的句柄信息。
 * 通过解析该句柄可以还原出属性名（{@code name}），从而替代硬编码字符串。
 * </p>
 * <p>
 * 这是实现 MyBatis-Plus 风格 Lambda 查询的关键基础接口。
 * 配合 {@link AbstractLambdaWrapper} 使用，可构建类型安全的链式查询 API。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 方法引用作为 SFunction
 * SFunction<User, String> fn = User::getName;
 *
 * // 在 LambdaQueryWrapper 中使用
 * engine.query(User.class)
 *     .eq(User::getName, "张三")    // User::getName 自动解析为 "name"
 *     .gt(User::getAge, 18)         // User::getAge 自动解析为 "age"
 *     .list();
 * }</pre>
 * </p>
 *
 * @param <T> 实体类型
 * @param <R> 属性类型
 * @author CH
 * @since 2024/12/12
 * @see AbstractLambdaWrapper
 * @see LambdaQueryWrapper
 * @see LambdaUpdateWrapper
 * @see LambdaDeleteWrapper
 */
@NullUnmarked
@FunctionalInterface
public interface SFunction<T, R> extends Function<T, R>, Serializable {
}
