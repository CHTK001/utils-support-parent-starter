package com.chua.common.support.network.rpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RPC 服务提供者注解，标记当前类或方法为远程可调用的 RPC 服务。
 *
 * <p>通常标注在服务实现类上，配合 {@link RpcServer#register(String, Object)} 注册机制使用。
 * 注解中的参数与各 RPC 框架（Dubbo、SOFA 等）的原生属性一一对应，
 * 在注册时自动读取并透传到底层框架的配置中。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 标记服务实现，指定版本和超时
 * @RpcService(interfaceClass = HelloService.class, version = "1.0.0", timeout = 3000)
 * public class HelloServiceImpl implements HelloService {
 *     public String sayHello(String name) {
 *         return "Hello, " + name;
 *     }
 * }
 * }</pre>
 *
 * <p>关于 {@link #interfaceClass()} 和 {@link #interfaceName()}：</p>
 * <ul>
 *   <li>两者只需填写其一</li>
 *   <li>{@code interfaceClass} 强类型、编译期安全，推荐使用</li>
 *   <li>{@code interfaceName} 字符串方式，用于运行时动态确定接口类</li>
 *   <li>如果都不填，默认取被注解类所实现的第一个接口</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface RpcService {

    /**
     * 服务接口的 Class 对象（推荐方式）
     *
     * <p>使用 Java Class 字面量指定服务接口，编译期类型安全。
     * 如果实现类只实现了一个接口，通常可省略此项（自动推断）。</p>
     *
     * <pre>{@code @RpcService(interfaceClass = UserService.class)}</pre>
     *
     * @return 服务接口的 Class 对象，默认 {@code void.class} 表示未设置
     */
    Class<?> interfaceClass() default void.class;

    /**
     * 服务接口的全限定类名（备选方式）
     *
     * <p>当 {@link #interfaceClass()} 不方便使用（如接口类不在编译路径上），
     * 可通过字符串名称指定接口。</p>
     *
     * <pre>{@code @RpcService(interfaceName = "com.example.UserService")}</pre>
     *
     * @return 服务接口的全限定类名，默认空字符串表示未设置
     */
    String interfaceName() default "";

    /**
     * 服务版本号
     *
     * <p>用于控制服务的多版本，实现平滑上线和灰度发布。
     * 消费端引用服务时需指定相同版本才能调用。</p>
     *
     * <p>示例：{@code "1.0.0"}、{@code "2.0.0"}。</p>
     *
     * @return 版本号字符串，默认空字符串表示使用框架默认版本
     */
    String version() default "";

    /**
     * 服务分组名称
     *
     * <p>将同一接口的多个服务实现在逻辑上划分为不同的分组。
     * 常用于环境隔离（dev vs prod）、多机房部署等场景。
     * 消费端在 {@link RpcConsumerConfig#getGroup()} 或 {@link RpcResource#group()} 中
     * 设置相同分组才能成功调用。</p>
     *
     * @return 分组名称，默认空字符串表示不分组
     */
    String group() default "";

    /**
     * 服务安全令牌
     *
     * <p>用于服务间的简单认证。消费端需要提供相同的 token 才能调用此服务，
     * 否则服务端拒绝请求。适用于对安全性有轻度要求的内部微服务间通信。</p>
     *
     * @return token 字符串，默认空字符串表示不启用 token 校验
     */
    String token() default "";

    /**
     * 延迟暴露服务的时间（单位：毫秒）
     *
     * <p>指定服务注册到注册中心的延迟时间，避免服务端启动过程中尚未完全初始化
     * 就接收外部请求。例如 {@code 5000} 表示启动 5 秒后再注册到注册中心。</p>
     *
     * @return 延迟毫秒数，默认 {@code 0} 表示不延迟持续暴露
     */
    int delay() default 0;

    /**
     * 服务端重试次数
     *
     * <p>服务端执行业务方法时，如果发生某些可重试的系统级异常（非业务异常），
     * 自动重新执行的次数。注意这和客户端的重试 ({@link RpcConsumerConfig#retries}) 不同。</p>
     *
     * @return 重试次数，默认 {@code 0} 表示不重试
     */
    int retry() default 0;

    /**
     * 是否异步执行服务端业务方法
     *
     * <ul>
     *   <li><b>false</b>（默认）：同步执行，客户端等待方法返回结果。</li>
     *   <li><b>true</b>：异步执行，服务端方法在独立线程中执行，调用方立即返回。</li>
     * </ul>
     *
     * @return 是否启用异步执行
     */
    boolean async() default false;

    /**
     * 服务端方法调用的超时时间（单位：毫秒）
     *
     * <p>限定了服务端执行业务方法的超时阈值。超过时间后，服务端应中断执行
     * 并返回超时异常给客户端。如果配置为 {@code 0}，表示使用框架默认超时。</p>
     *
     * @return 超时毫秒数
     */
    int timeout() default 0;

    /**
     * 服务端缓存策略名称
     *
     * <p>指定服务端对方法返回值进行缓存的策略，用于减少重复计算。
     * 通常仅在查询类接口上启用。常见取值：</p>
     * <ul>
     *   <li>{@code "lru"} — LRU（最近最少使用）缓存</li>
     *   <li>{@code "threadlocal"} — 线程局部缓存</li>
     *   <li>{@code "jcache"} — JCache（JSR-107）标准缓存</li>
     *   <li>空字符串 — 不启用缓存</li>
     * </ul>
     *
     * @return 缓存策略名称，默认空字符串表示不缓存
     */
    String cache() default "";
}