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
     * 元素归约键（SpEL 表达式）。
     *
     * <p>合并拆分模式下，窗口内全部调用的元素按该表达式提取归约键去重并集，
     * 核心方法执行一次后按各调用者元素的归约键拆分回填。为空时元素自身即键
     * （要求方法返回 {@code Map} 且 key = 元素，兼容默认语义）。</p>
     *
     * <p>表达式基于元素对象求值，如 {@code "id"}、{@code "#this.id"}、{@code "getKey()"}；
     * 支持同参合并场景下将元素映射为统一键（如实体按业务键归约）。</p>
     *
     * @return 元素归约键 SpEL 表达式
     */
    String key() default "";

    /**
     * 折叠执行失败时调用的降级方法。
     *
     * <p>支持两种引用形式：</p>
     * <ul>
     *   <li>{@code methodName}：目标方法同类的同名方法（参数签名一致）；</li>
     *   <li>{@code beanName#methodName}：Spring 容器中指定 Bean 的方法
     *       （可将降级方法收敛到公共降级 Bean，如统一空降级）。</li>
     * </ul>
     *
     * <p>降级方法返回值非 null 视为降级成功；降级不可用（未配置/Bean 或方法不存在/返回 null）
     * 时抛出原始异常。</p>
     *
     * @return 降级方法引用，为空时折叠失败直接抛原始异常
     */
    String fallback() default "";

    /**
     * 批量收集的最小阈值，达到该数量的调用后立即执行。
     *
     * <p>{@code -1} 表示未显式指定：生效值取全局默认配置
     * （Spring Boot {@code collapse.executor.wait-threshold}），未配置全局时使用内置默认 10。</p>
     *
     * @return 批量收集阈值
     */
    int waitThreshold() default -1;

    /**
     * 未达到阈值时的补收等待时间（毫秒）。
     *
     * <p>小于 0：立即执行；等于 0：让出当前收集线程时间片后补收一次；
     * 大于 0：等待指定毫秒后再补收。</p>
     *
     * <p>{@code -2} 表示未显式指定：生效值取全局默认配置
     * （Spring Boot {@code collapse.executor.collecting-wait-time}），未配置全局时使用内置默认 0。</p>
     *
     * @return 补收等待时间（毫秒）
     */
    long collectingWaitTime() default -2;
}
