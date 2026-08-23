   package com.chua.ast.support.annotation;


import java.lang.annotation.*;

/**
 * 自动关闭资源注解，编译期自动为实现了 AutoCloseable 的参数插入 try-finally 关闭代码
 *
 * <p>该注解会在编译期在方法体外部包裹 try-finally 块，
 * 在 finally 中确保 {@code close()} 方法被安全调用。
 * 如果参数实现了 {@code AutoCloseable} 接口，编译期会生成 null 检查 + close() 调用代码。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 原始代码：
 * public void process(@AutoClose InputStream is) {
 *     is.read();
 * }
 *
 * // 转换后的代码：
 * public void process(InputStream is) {
 *     try {
 *         is.read();
 *     } finally {
 *         if (is != null) {
 *             try { is.close(); } catch (Exception e) { /* 忽略 *&#47; }
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><b>注意：</b>参数必须实现 {@link AutoCloseable} 接口，否则编译期会发出警告。
 * close() 方法调用中的异常会被捕获并忽略。</p>
 *
 * @author CH
 * @since 2024
 * @see AutoCloseable
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface AutoClose {
}