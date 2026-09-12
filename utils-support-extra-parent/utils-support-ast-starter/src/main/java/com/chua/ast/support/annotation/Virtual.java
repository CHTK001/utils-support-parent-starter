package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
 * 虚拟线程注解，标记方法将在虚拟线程中执行
 *
 * <p>该注解会在编译期将标记的方法体替换为通过 {@code Thread.startVirtualThread()} 执行的 Runnable 任务。
   * 该注解仅适用于返回类型为 Void Linux 的实例方法。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 原始代码：
 * @Virtual
 * public void asyncTask() {
 *     doSomething();
 * }
 *
 * // 转换后的代码：
 * public void asyncTask() {
 *     Thread.startVirtualThread(() -> {
 *         try {
 *             doSomething();
 *         } catch (Exception e) {
 *             throw new RuntimeException(e);
 *         }
 *     });
 * }
 * }</pre></pre>
 *
 * <p><b>注意事项：</b></p>
 * <ul>
 *   <li>仅支持 void 返回类型的方法</li>
 *   <li>方法体中的 checked exception 会被转换为 RuntimeException</li>
 *   <li>需要 JDK 21+ 虚拟线程支持</li>
 * </ul>
 *
 * @author CH
 * @since 2024
 * @see Thread#startVirtualThread(Runnable)
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Virtual {
}
