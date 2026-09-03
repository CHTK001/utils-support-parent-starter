package com.chua.spring.support.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 请求折叠注解，标注在需要聚合的方法上。
 *
 * <p><b>折叠语义</b>：并发窗口内（同一收集批次），<b>方法相同且实参深度相同</b>
 * （equals/hashCode 语义）的调用将合并为一次真实执行，结果广播给组内全部调用方。
 * 适用于无副作用（只读/幂等）的热点查询方法，可显著降低下游 I/O 与线程占用。</p>
 *
 * <p><b>适用约定</b>：</p>
 * <ul>
 *   <li>方法须为 Spring 托管 Bean 的 public 方法（依赖 AOP 代理拦截）</li>
 *   <li>方法应只读/幂等：折叠会改变方法的实际执行次数</li>
 *   <li>方法不应依赖调用线程上下文（事务、ThreadLocal 等），合并后可能在虚拟线程上执行</li>
 *   <li>入参可为任意形态（单对象、集合、数组均可）；数组参数会自动按元素深度归一化参与相等比较；
 *       对象参数要求实现正确的 equals/hashCode</li>
 * </ul>
 *
 * <p>所有数值属性支持 {@code ${...}} 占位符和 {@code #{...}} SpEL 表达式。</p>
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
     * <p>为空时使用 {@code 目标类全限定名.方法名}；支持 {@code #{...}} 表达式实现
     * 参数级动态隔离（如 {@code #{method.name + '-' + args[0]}}）。</p>
     *
     * @return 折叠执行器名称
     */
    String name() default "";

    /**
     * 批量收集的最小阈值，达到该数量的相同调用后立即执行。
     *
     * <p>支持 {@code ${...}} 与 {@code #{...}} 表达式，默认 10。</p>
     *
     * @return 批量收集阈值
     */
    String waitThreshold() default "10";

    /**
     * 未达到阈值时的补收等待时间（毫秒）。
     *
     * <p>小于 0：立即执行；等于 0（默认）：让出当前收集线程时间片后补收一次；
     * 大于 0：等待指定毫秒后再补收。支持 {@code ${...}} 与 {@code #{...}} 表达式。</p>
     *
     * @return 补收等待时间（毫秒）
     */
    String collectingWaitTime() default "0";
}
