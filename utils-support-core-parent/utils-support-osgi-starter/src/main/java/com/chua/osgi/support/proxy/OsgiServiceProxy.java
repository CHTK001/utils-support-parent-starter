package com.chua.osgi.support.proxy;

/**
 * OSGi 服务切换代理标记接口。
 *
 * <p>桥接注册器（{@code OsgiBeanDefinitionRegister}）外发给容器的桥接 Bean
 * 并不是 OSGi 服务的原始实例，而是一个实现了本标记接口的 JDK 动态代理：
 * 代理在<b>每次方法调用时</b>才向注册器解析「当前生效」的服务实例，
 * 因此 bundle 升级、卸载或更高 {@code service.ranking} 的服务上线后，
 * 此前被消费方持有的引用会自动打到新实例上——无需重新注入或重新查找。</p>
 *
 * <p>需要拿到底层真实实例（如做 {@code instanceof} 具体实现类判断、
 * 反射注解扫描）时，调用 {@link #getOsgiTarget()} 解包；服务已下线时返回
 * {@code null}，而经代理调用业务方法会抛
 * {@link IllegalStateException}。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface OsgiServiceProxy {

    /**
     * 获取当前生效的原始服务实例。
     *
     * <p>返回值随 OSGi 服务注册表实时变化：bundle 升级后返回新实例，
     * 服务卸载且无替代后返回 {@code null}。本方法本身不抛异常，
     * 由调用方决定对「已下线」的处理方式。</p>
     *
     * @return 当前生效的服务实例；服务已下线时返回 {@code null}
     */
    Object getOsgiTarget();
}
