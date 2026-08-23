   package com.chua.ast.support.annotation;


import java.lang.annotation.*;

/**
 * 方法执行耗时统计注解，编译期自动注入计时逻辑
 *
 * <p>该注解会在编译期在方法开头插入计时开始代码，在 finally 块中计算耗时并输出。
 * 支持两种输出方式：通过 SLF4J 日志输出（配合 @Slf4j 注解），或通过 System.err 打印。</p>
 *
 * <p>配合 @Trace 注解可同时实现链路追踪和耗时统计。</p>
 * <ul>
 *   <li>存在 {@code log} 字段时优先使用日志框架输出，并通过 try-catch(NoClassDefFoundError) 兜底 System.err 输出</li>
 *   <li>不存在 {@code log} 字段时直接使用 {@code System.err.println()} 输出</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 原始代码：
 * @Timed
 * public void process() {
 *     doSomething();
 * }
 *
 * // 转换后（有 log 字段）：
 * public void process() {
 *     long _timedStart = System.nanoTime();
 *     try {
 *         doSomething();
 *     } finally {
 *         long _timedElapsed = System.nanoTime() - _timedStart;
 *         try {
 *             log.info("process 执行耗时: {} ms", _timedElapsed / 1_000_000L);
 *         } catch (NoClassDefFoundError e) {
 *             System.err.println("process 执行耗时: " + (_timedElapsed / 1_000_000L) + " ms");
 *         }
 *     }
 * }
 *
 * // 转换后（无 log 字段）：
 * public void process() {
 *     long _timedStart = System.nanoTime();
 *     try {
 *         doSomething();
 *     } finally {
 *         long _timedElapsed = System.nanoTime() - _timedStart;
 *         System.err.println("process 执行耗时: " + (_timedElapsed / 1_000_000L) + " ms");
 *     }
 * }
 * }</pre>
 *
 * <p>耗时单位为毫秒，使用 {@code System.nanoTime()} 保证高精度计时。</p>
 *
 * @author CH
 * @since 2024
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Timed {
}