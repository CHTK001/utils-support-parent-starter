package com.chua.osgi.support.source;

import java.util.Map;

/**
 * 源码扩展点的依赖注入契约。
 *
 * <p><b>为什么源码需要它</b>：源码编译产物活在独立 {@link ClassLoader} 里，
 * 不在 Spring 容器内，因此拿不到 {@code @Autowired} 注入的 Bean
 * （Mapper、Service、配置…）。本契约让容器侧把已就绪的 Bean 以普通 {@link Map}
 * 暴露给源码，源码按类型或名字取用——等价于「把容器的某一小块交给源码」。</p>
 *
 * <p><b>用法</b>：</p>
 * <pre>{@code
 * // 源码侧（门面最常用的一步）
 * public final EduStudentMapper mapper = SourceContextHolder.get(EduStudentMapper.class);
 * // 按名取用显式不同名，避免 get(String) 与 get(Class) 重载歧义
 * Object cfg = SourceContextHolder.getBean("myBean");
 * }</pre>
 *
 * <p><b>方法命名约束</b>：按类型取叫 {@code get(Class)}，按名取<b>不叫</b> {@code get(String)}
 * ——两个 {@code get} 重载在调用点天然歧义（编译器对 {@code null} 字面量等场景会拒绝解析），
 * 会逼调用方写显式转型才能编译。</p>
 *
 * <p><b>生命周期</b>：由 {@link SourceContextHolder} 持有，装配阶段调用
 * {@link SourceContextHolder#install(Map)}；应用停止时 {@link SourceContextHolder#clear()}
 * 释放，防止源码 ClassLoader 连带持有已销毁的 Bean。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface SourceContext {

    /**
     * 按类型取一个 Bean。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型
     * @return Bean 实例，未提供该类型时返回 {@code null}
     */
    <T> T get(Class<T> type);

    /**
     * 按名字取一个 Bean。
     *
     * @param name Bean 名称
     * @return Bean 实例，不存在时返回 {@code null}
     */
    Object getBean(String name);

    /**
     * 判断某类型是否已提供。
     *
     * @param type Bean 类型
     * @return 已提供返回 {@code true}
     */
    boolean contains(Class<?> type);

    /**
     * 当前已暴露的 Bean 数量。
     *
     * @return 数量
     */
    int size();
}
