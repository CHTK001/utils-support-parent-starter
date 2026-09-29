package com.chua.common.support.task.script;

/**
 * 片段执行期的宿主能力注入面。
 *
 * <p>脚本引擎本身不该知道 Spring：{@code utils-support-common-starter} 是纯 JDK 工具层，
 * 引不进 {@code ApplicationContext}。但片段正文又确实需要「取业务 Bean」「字段自动装配」
 * 这类宿主能力，于是把这些能力收敛成本接口，由宿主模块实现并随执行上下文传入，
 * 引擎只面向本接口编程。非 Spring 宿主可以不提供实现，此时片段退化为纯计算。</p>
 *
 * <p>实现类的实例随每次执行传入，不要缓存到引擎里。</p>
 *
 * @since 4.0.0.43
 * @author CH
 */
public interface ScriptHost {

    /**
     * 按类型取宿主 Bean。
     *
     * @param type 目标类型
     * @param <T>  目标类型泛型
     * @return 命中的 Bean；宿主未提供该能力时返回 null
     */
    <T> T getBean(Class<T> type);

    /**
     * 对已实例化的片段对象做宿主装配。
     *
     * <p>Spring 宿主下即字段 {@code @Autowired} 生效；非 Spring 宿主可以空实现。</p>
     *
     * @param instance 片段实例，允许为 null
     */
    void inject(Object instance);
}
