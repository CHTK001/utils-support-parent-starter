package com.chua.osgi.support.proxy;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OSGi 服务切换代理工厂。
 *
 * <p>把桥接 Bean 从「快照时点的裸实例」升级为「每次调用都解析当前生效服务」的
 * JDK 动态代理，使消费方在启动期拿到的引用也能随 OSGi 侧变化自动切换：
 * bundle 升级/卸载、或更高 {@code service.ranking} 的服务上线后，
 * 旧引用的下一次调用会自动落到新实例上。</p>
 *
 * <h3>实现口径</h3>
 * <ul>
 *   <li>代理实现目标实例的全部接口（含父类声明的接口）与
 *       {@link OsgiServiceProxy} 标记；目标没有可代理接口时无法生成 JDK 代理，
 *       退化为原始实例（行为与旧版一致，不切换），以 debug 日志说明；</li>
 *   <li>当前实例由调用方注入的 {@link TargetResolver} 按调用时解析，
 *       本工厂不缓存实例；</li>
 *   <li>服务已下线时，业务方法抛 {@link IllegalStateException}；
 *       {@code getOsgiTarget()} 返回 {@code null} 供调用方自行处置；</li>
 *   <li>{@code toString}/{@code hashCode}/{@code equals} 基于当前实例或
 *       Bean 名称稳定实现，代理被放入集合、打印日志均安全。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public final class OsgiServiceProxyFactory {

    /**
     * 当前生效服务实例的解析策略。
     *
     * <p>由桥接注册器实现：按 Bean 名称从当前快照取定义并解包出原始实例，
     * 服务已下线时返回 {@code null}。</p>
     */
    public interface TargetResolver {

        /**
         * 解析当前生效的原始服务实例。
         *
         * @param beanName 桥接 Bean 名称
         * @return 当前实例；服务已下线时返回 {@code null}
         */
        Object resolve(String beanName);
    }

    private OsgiServiceProxyFactory() {
    }

    /**
     * 为服务实例创建切换代理。
     *
     * @param beanName 桥接 Bean 名称（用于日志与 {@code toString}）
     * @param target   当前服务实例
     * @param resolver 实例解析策略
     * @return 切换代理；无法代理（无接口/创建失败）时原样返回 {@code target}
     */
    public static Object create(String beanName, Object target, TargetResolver resolver) {
        if (target == null || resolver == null) {
            return target;
        }
        Class<?> targetClass = target.getClass();
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(targetClass, interfaces);
        interfaces.add(OsgiServiceProxy.class);
        if (interfaces.size() <= 1) {
            log.debug("[osgi] 服务无可代理接口，退化为直接引用（不随 OSGi 切换）: {}", beanName);
            return target;
        }
        ClassLoader classLoader = targetClass.getClassLoader();
        if (classLoader == null) {
            classLoader = OsgiServiceProxyFactory.class.getClassLoader();
        }
        try {
            return Proxy.newProxyInstance(classLoader, interfaces.toArray(new Class<?>[0]),
                    new SwitchingHandler(beanName, resolver));
        } catch (Exception e) {
            log.debug("[osgi] 创建切换代理失败，退化为直接引用: {}", beanName, e);
            return target;
        }
    }

    /**
     * 解包代理，取其当前目标；非代理原样返回。
     *
     * @param candidate 待解包对象
     * @return 原始服务实例（可能为 {@code null}）
     */
    public static Object unwrap(Object candidate) {
        return candidate instanceof OsgiServiceProxy proxy ? proxy.getOsgiTarget() : candidate;
    }

    /**
     * 收集类型及其父类声明的全部接口。
     *
     * @param type 起始类型
     * @param out  收集结果
     */
    private static void collectInterfaces(Class<?> type, Set<Class<?>> out) {
        for (Class<?> iface : type.getInterfaces()) {
            if (out.add(iface)) {
                collectInterfaces(iface, out);
            }
        }
        Class<?> superType = type.getSuperclass();
        if (superType != null && superType != Object.class) {
            collectInterfaces(superType, out);
        }
    }

    /**
     * 切换调用处理器：每次调用都向解析策略要「当前生效实例」再转发。
     *
     * @param beanName 桥接 Bean 名称
     * @param resolver 实例解析策略
     */
    private record SwitchingHandler(String beanName, TargetResolver resolver) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == OsgiServiceProxy.class) {
                return resolver.resolve(beanName);
            }
            String methodName = method.getName();
            if ("equals".equals(methodName) && args != null && args.length == 1) {
                Object self = resolver.resolve(beanName);
                return self == null ? proxy == args[0] : self.equals(unwrap(args[0]));
            }
            if ("hashCode".equals(methodName)) {
                return beanName.hashCode();
            }
            if ("toString".equals(methodName)) {
                Object self = resolver.resolve(beanName);
                return "OsgiServiceProxy{" + beanName + " -> " + (self == null ? "<已下线>" : self) + "}";
            }
            Object target = resolver.resolve(beanName);
            if (target == null) {
                throw new IllegalStateException("[osgi] 服务已下线，无法调用: " + beanName);
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
