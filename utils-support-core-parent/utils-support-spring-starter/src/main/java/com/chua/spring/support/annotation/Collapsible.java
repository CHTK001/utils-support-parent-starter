package com.chua.spring.support.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 请求折叠注解，标注在需要聚合的方法上。
 *
 * <p><b>方法契约</b>：被标注方法必须<b>恰好一个入参且为 {@link java.util.Collection}</b>
 * （如 {@code List<Long> ids}），且方法应只读/幂等、不依赖调用线程上下文
 * （事务、ThreadLocal 等，合并后可能在虚拟线程上执行）。</p>
 *
 * <p><b>折叠语义（按返回类型自适应）</b>：</p>
 * <ul>
 *   <li><b>返回 {@code Map}（key = 入参元素）</b>：收集窗口内到达的全部调用合并为一次核心方法执行
 *       （实参为各调用者集合的并集），随后按元素归属将结果拆分为子 Map 回填给各调用者
 *       ——精确、无歧义，对应"批量查询合并"场景；</li>
 *   <li><b>返回非 {@code Map}</b>：无法安全拆分，自动降级为"同参折叠"——相同实参的并发调用
 *       合并执行一次并广播结果（幂等热点兜底）；</li>
 *   <li><b>实参为空集合 / 未引入折叠实现模块</b>：直接执行，不做折叠。</li>
 * </ul>
 *
 * <p>收集窗口由 {@link #waitThreshold()} 与 {@link #collectingWaitTime()} 控制：
 * 补收等待（毫秒）小于 0 立即执行、等于 0（默认）让出收集线程时间片后补收一次、大于 0 等待指定毫秒。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Collapsible {

    /**
     * 折叠执行器名称（同一名称共享同一收集器）。
     *
     * <p>为空时使用 {@code 目标类全限定名.方法名}；不同方法不应共享同一名称
     * （合并拆分模式按方法执行，混入不同方法会抛出异常）。</p>
     *
     * @return 折叠执行器名称
     */
    String name() default "";

    /**
     * 批量收集的最小阈值，达到该数量的调用后立即执行，默认 10。
     *
     * @return 批量收集阈值
     */
    int waitThreshold() default 10;

    /**
     * 未达到阈值时的补收等待时间（毫秒）。
     *
     * <p>小于 0：立即执行；等于 0（默认）：让出当前收集线程时间片后补收一次；
     * 大于 0：等待指定毫秒后再补收。</p>
     *
     * @return 补收等待时间（毫秒）
     */
    long collectingWaitTime() default 0;
}
