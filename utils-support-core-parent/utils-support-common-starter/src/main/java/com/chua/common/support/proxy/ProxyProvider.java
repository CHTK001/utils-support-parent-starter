package com.chua.common.support.proxy;

import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.proxy.intercept.MethodIntercept;


/**
 * 代理提供者接口，定义了代理对象构建的标准方法。
 *
 * <p>本接口采用<b>建造者模式（Builder Pattern）</b>，允许通过链式调用配置代理对象的
 * 各项属性，包括类加载器、额外接口、目标对象、方法拦截器、注解扫描和环绕拦截等。
 * 最终通过 {@link #build()} 方法生成代理对象实例。</p>
 *
 * <p><b>核心特性：</b></p>
 * <ul>
 *   <li><b>双代理引擎</b> — 接口类型使用 JDK 动态代理，类类型使用 Javassist 代理</li>
 *   <li><b>注解扫描</b> — 自动扫描方法上的注解并执行对应的拦截逻辑</li>
 *   <li><b>环绕拦截</b> — 支持按方法签名匹配的环绕拦截器链</li>
 *   <li><b>IoC 集成</b> — 支持注入 {@link ObjectContext} 实现与容器集成</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 创建代理对象（接口类型）
 * Service proxy = ProxyProvider.of(Service.class)
 *     .classLoader(Thread.currentThread().getContextClassLoader())
 *     .methodIntercept((obj, method, args, proxyInstance) -> {
 *         System.out.println("调用方法: " + method.getName());
 *         return MethodInvoker.invoke(method, obj, args);
 *     })
 *     .build();
 *
 * // 创建代理对象（类类型，带注解扫描）
 * UserService proxy = ProxyProvider.of(UserService.class)
 *     .target(new UserServiceImpl())
 *     .enableAnnotationScan(true)
 *     .enableArround(true)
 *     .build();
 * }</pre>用户服务.类)
 * .Target(新 用户服务impl())
 * .enable注解扫描(true)
 * .enablearround(true)
 * .构建();
 * }</pre>
 *
 * @param <T> 代理接口类型
 * @author CH
 * @since 2025/11/26
 * @版本 1.0.0
 */
public interface ProxyProvider<T> {

    /**
     * 创建代理提供者实例。
     *
     * <p>使用默认配置创建代理提供者，适用于简单的接口代理场景。
     * 内部创建 {@link DefaultProxyProvider} 实例。</p>
     *
     * @param type 目标接口类型，如 {@code Service.class}
     * @param <T>  接口类型
     * @return 代理提供者实例，可继续链式配置
     */
    static <T> ProxyProvider<T> of(Class<T> type) {
        return new DefaultProxyProvider<>(type);
    }

    /**
     * 创建代理提供者实例，并指定对象上下文。
     *
     * <p>在创建代理提供者的同时注入 {@link ObjectContext}，
     * 使得代理对象可以访问容器中的其他 Bean。适用于 Spring、Guice 等 IOC 集成场景。</p>
     *
     * @param type          目标接口类型，如 {@code Service.class}
     * @param objectContext 对象上下文，用于 Bean 查找和依赖注入
     * @param <T>           接口类型
     * @return 代理提供者实例，可继续链式配置
     */
    static <T> ProxyProvider<T> of(Class<T> type, ObjectContext objectContext) {
        return new DefaultProxyProvider<>(type).objectContext(objectContext);
    }

    /**
     * 设置类加载器。
     *
     * <p>指定创建代理对象时使用的类加载器。默认使用目标接口的类加载器。
     * 在 osgi、Tomcat 等具有多个类加载器的环境中，正确设置类加载器至关重要。</p>
     *
     * @param classLoader 类加载器，如 {@code Thread.currentThread().getContextClassLoader()}
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> classLoader(ClassLoader classLoader);

    /**
     * 设置要代理的额外接口。
     *
     * <p>除了主接口（创建时指定的 {@code type}）之外，可以附加额外的接口到代理对象上，
     * 使得代理对象可以转型为这些接口类型。常用于给代理对象附加标记接口或回调接口。</p>
     *
     * @param interfaces 要代理的额外接口数组，如 {@code new Class<?>[]{Closeable.class, Serializable.class}}
    private static final long serialVersionUID = 1L;
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> interfaces(Class<?>... interfaces);

    /**
     * 设置目标对象（用于委托调用）。
     *
     * <p>指定实际执行业务逻辑的目标对象。当代理方法被调用时，
     * 默认实现会将调用委托给该目标对象的同名方法。
     * 如果未设置目标对象，拦截器中需自行处理方法调用逻辑。</p>
     *
     * @param target 目标对象实例，包含实际的业务逻辑实现
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> target(Object target);

    /**
     * 启用注解扫描（默认启用）。
     *
     * <p>开启后，代理框架会自动扫描方法上的注解，并通过 SPI 发现
     * 的 {@code MethodAnnotationIntercept} 实现来执行注解对应的拦截逻辑。
     * 等效于调用 {@code enableAnnotationScan(true)}。</p>
     *
     * @return 当前代理提供者实例（支持链式调用）
     */
    default ProxyProvider<T> enableAnnotationScan() {
        return enableAnnotationScan(true);
    }

    /**
     * 设置是否启用注解扫描。
     *
     * <p>注解扫描用于自动发现方法上的注解并执行对应的拦截逻辑。
     * 如果代理对象不需要注解驱动的拦截，可以禁用此功能以提升性能。</p>
     *
     * @param enable 是否启用注解扫描，true 表示启用，false 表示禁用
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> enableAnnotationScan(boolean enable);

    /**
     * 启用环绕拦截（默认启用）。
     *
     * <p>开启后，代理框架会通过 SPI 发现 {@code MethodArroundIntercept} 实现，
     * 并根据 {@code @Around} 注解中配置的方法签名规则自动匹配并执行环绕拦截。
     * 等效于调用 {@code enableArround(true)}。</p>
     *
     * @return 当前代理提供者实例（支持链式调用）
     */
    default ProxyProvider<T> enableArround() {
        return enableArround(true);
    }

    /**
     * 设置是否启用环绕拦截。
     *
     * <p>环绕拦截器允许在目标方法执行前后插入自定义逻辑，类似于 AOP 的 Around Advice。
     * 如果不需要环绕拦截，可以禁用此功能以提升性能。</p>
     *
     * @param enable 是否启用环绕拦截，true 表示启用，false 表示禁用
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> enableArround(boolean enable);

    /**
     * 设置对象上下文。
     *
     * <p>对象上下文用于与 IoC 容器集成，提供 Bean 的查找和注入能力。
     * 设置后，代理框架可以从上下文中获取所需的拦截器、处理器等组件。
     * 上下文中的 Bean 生命周期由外部容器管理。</p>
     *
     * @param objectContext 对象上下文实例
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> objectContext(ObjectContext objectContext);

    /**
     * 设置是否优先使用 ASM 代理。
     *
     * <p>开启后构建代理时将优先尝试 ASM 字节码代理（通过 SPI 加载 {@code ProxyFactory} 的 "asm" 扩展）。
     * 默认启用。</p>
     *
     * @param enable 是否启用 ASM 代理
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> tryAsm(boolean enable);

    /**
     * 设置是否使用 Javassist 代理。
     *
     * <p>开启后 ASM 不可用时将尝试 Javassist 代理（通过 SPI 加载 {@code ProxyFactory} 的 "javassist" 扩展）。
     * 默认启用。</p>
     *
     * @param enable 是否启用 Javassist 代理
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> tryJavassist(boolean enable);

    /**
     * 设置方法拦截器。
     *
     * <p>设置用户自定义的方法拦截器，用于拦截代理对象的方法调用。
     * 拦截器接口 {@link MethodIntercept} 提供了完整的拦截生命周期：
     * 前置处理（之前）→ 方法调用（invoke）→ 后置处理（之后）→ 异常处理（处理异常）。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * proxyProvider.methodIntercept(new MethodIntercept<Service>() {
     *     public void before(Object obj, Method method, Object[] args, Service proxy) {
     *         System.out.println("前置: " + method.getName());
     *     }
     *     public Object invoke(Object obj, Method method, Object[] args, Service proxy) {
     *         System.out.println("调用: " + method.getName());
     *         return MethodInvoker.invoke(method, obj, args);
     *     }
     *     public void after(Object obj, Method method, Object[] args, Service proxy) {
     *         System.out.println("后置: " + method.getName());
     *     }
     * });
     * }</pre>.println("后置: " + 方法.获取名称());
     *     }
     * });
     * }</pre>
     *
     * @param methodIntercept 方法拦截器实例，传入 空 会使用空拦截器
     * @return 当前代理提供者实例（支持链式调用）
     */
    ProxyProvider<T> methodIntercept(MethodIntercept<T> methodIntercept);

    /**
     * 构建代理对象。
     *
     * <p>根据配置的参数（类加载器、接口、目标对象、拦截器等）创建最终的代理对象实例。
     * 代理引擎选择顺序：ASM → Javassist → JDK，可通过 {@link #tryAsm(boolean)} /
     * {@link #tryJavassist(boolean)} 开关控制。</p>
     *
     * <p>此方法应只在所有配置完成后调用一次。重复调用会创建多个代理实例。</p>
     *
     * @return 代理对象实例，类型为构造时指定的 {@code T}
     */
    T build();
}
