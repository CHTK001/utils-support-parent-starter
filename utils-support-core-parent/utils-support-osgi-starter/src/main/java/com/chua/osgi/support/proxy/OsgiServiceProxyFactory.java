package com.chua.osgi.support.proxy;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OSGi 服务切换代理工厂。
 *
 * <p>把桥接 Bean 从「快照时点的裸实例」升级为「每次调用都解析当前生效服务」的
 * JDK 动态代理，使消费方在启动期拿到的引用也能随 OSGi 侧变化自动切换：
 * bundle 升级/卸载后，旧引用的下一次调用会自动落到当前生效实例上
 * （同类型仍有其他在册服务时取 {@code service.ranking} 最高者，
 * 与容器按优先级重新选取的口径一致；全部下线时业务方法抛
 * {@link IllegalStateException}）。</p>
 *
 * <h3>实现口径</h3>
 * <ul>
 *   <li>代理实现目标实例的全部公共接口（含父类声明的接口）与
 *       {@link OsgiServiceProxy} 标记；目标没有可代理公共接口时无法生成 JDK 代理，
 *       退化为原始实例（行为与旧版一致，不切换），以 debug 日志说明；</li>
 *   <li>桥接场景下服务接口可能由宿主经 {@code org.osgi.framework.system.packages.extra}
 *       导出共享，单一类加载器未必同时可见全部接口与本标记接口，故按
 *       「接口自带加载器 → 目标类加载器 → 本工厂加载器」依次尝试；</li>
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
     * @return 切换代理；无法代理（无公共接口/创建失败）时原样返回 {@code target}
     */
    public static Object create(String beanName, Object target, TargetResolver resolver) {
        if (target == null || resolver == null) {
            return target;
        }
        Class<?> targetClass = target.getClass();
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(targetClass, interfaces);
        // 非公共接口要求代理类定义在同包同加载器，跨加载器桥接场景无法满足，直接排除
        interfaces.removeIf(iface -> !Modifier.isPublic(iface.getModifiers()));
        interfaces.add(OsgiServiceProxy.class);
        if (interfaces.size() <= 1) {
            log.debug("[osgi] 服务无可代理公共接口，退化为直接引用（不随 OSGi 切换）: {}", beanName);
            return target;
        }
        Class<?>[] interfaceArray = interfaces.toArray(new Class<?>[0]);
        SwitchingHandler handler = new SwitchingHandler(beanName, resolver);
        // 依次尝试多个类加载器：契约接口自带加载器（宿主经 system.packages.extra
        // 导出契约包时与 bundle 侧共享同一 Class）、目标类加载器、本工厂加载器
        ClassLoader[] candidates = {
                interfaceArray[0].getClassLoader(),
                targetClass.getClassLoader(),
                OsgiServiceProxyFactory.class.getClassLoader()
        };
        for (ClassLoader classLoader : candidates) {
            if (classLoader == null) {
                continue;
            }
            try {
                return Proxy.newProxyInstance(classLoader, interfaceArray, handler);
            } catch (Exception e) {
                log.debug("[osgi] 类加载器 {} 创建切换代理失败，尝试下一个: {}",
                        classLoader, beanName);
            }
        }
        log.debug("[osgi] 创建切换代理失败，退化为直接引用: {}", beanName);
        return target;
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
