package com.chua.common.support.network.rpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* RPC 资源引用注解，在消费者端标记需要注入远程 RPC 服务代理的字段或方法。
*
* <p>类似于 Spring 框架的 {@code @Autowired} 或 {@code @Resource}，
* 但专用于 RPC 远程服务的依赖注入。被注解的字段会在初始化时被替换为远程服务的本地代理对象，
* 实现对远程服务的零侵入调用。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* public class OrderService {
*
*     // 注入远程用户服务代理
*     @RpcResource(version = "1.0.0", timeout = 5000)
*     private UserService userService;
*
*     public void process() {
*         // 像调用本地 Bean 一样调用远程服务
*         User user = userService.findById(1001L);
*     }
* }
* }</pre>
*
* <h2>配置优先级</h2>
* <p>当此注解上的参数与 {@link RpcConsumerConfig} 全局配置冲突时，
* 注解上的细粒度配置优先级更高（就近原则）。</p>
*
* @author CH
* @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface RpcResource {

    /**
    * 目标远程服务接口的 Class 对象
    *
    * <p>当注解标注在字段上时通常可省略（框架自动从字段类型推断），
    * 但标注在 Setter 方法时必须显式指定。</p>
    *
    * @return 服务接口的 Class 对象，默认 {@code void.class} 表示自动推断
    */
    Class<?> interfaceClass() default void.class;

    /**
    * 目标远程服务接口的全限定类名（字符串方式）
    *
    * <p>当接口类型在编译期不可获取时的备选方案。
    * 与 {@link #interfaceClass()} 二选一。</p>
    *
    * @return 接口全限定类名
    */
    String interfaceName() default "";

    /**
    * 要引用的服务版本号
    *
    * <p>客户端通过此版本号匹配对应的服务端版本。
    * 必须与 {@link RpcService#version()} 一致才能完成调用。</p>
    *
    * @return 版本号，默认空字符串表示使用 {@link RpcConsumerConfig} 中的全局版本配置
    */
    String version() default "";

    /**
    * 要引用的服务分组
    *
    * <p>客户端通过此分组匹配对应的服务端分组。
    * 必须与 {@link RpcService#group()} 一致才能完成调用。</p>
    *
    * @return 分组名称，默认空字符串表示使用 {@link RpcConsumerConfig} 中的全局分组配置
    */
    String group() default "";

    /**
    * 点对点直连的 URL 地址
    *
    * <p>在不使用注册中心、或开发时需要跳过注册中心直连指定服务的场景下使用。
    * 格式：{@code "dubbo://192.168.1.100:20880"} 或 {@code "http://localhost:8080"}。</p>
    *
    * <p>此选项常用于调试、测试环境，生产环境建议使用注册中心实现高可用。</p>
    *
    * @return 直连 URL，默认空字符串表示通过注册中心发现服务
    */
    String url() default "";

    /**
    * 使用的 RPC 客户端协议名称
    *
    * <p>当使用多协议混合部署时，指定此引用应使用哪种客户端协议（如 "dubbo"、"sofa"、"json"）。
    * 如果不指定，使用默认协议。</p>
    *
    * @return 协议名称，默认空字符串表示使用默认协议
    */
    String client() default "";

    /**
    * 启动时是否检查远程服务的连通性
    *
    * <p>容器启动完成初始化阶段，是否尝试连接远程服务验证其可用性。</p>
    *
    * <ul>
    *   <li><b>true</b>（默认）：启动时检查，如果服务不可用则初始化失败</li>
    *   <li><b>false</b>：首次调用时再检查</li>
    * </ul>
    *
    * @return 是否启动时检查
    */
    boolean check() default true;

    /**
    * 是否在容器启动完毕后就进行初始化注入
    *
    * <p>控制远程服务代理的创建时机：</p>
    * <ul>
    *   <li><b>true</b>（默认）：立即初始化，在依赖注入阶段创建代理对象</li>
    *   <li><b>false</b>：延迟初始化，在首次访问该字段时才创建代理对象</li>
    * </ul>
    *
    * @return 是否立即初始化
    */
    boolean init() default true;

    /**
    * 是否启用懒加载代理
    *
    * <p>当设置为 {@code true} 时，即使当前服务节点不可用也不会阻止引用注入，
    * 代理对象被调用时再根据负载均衡策略选择可用节点发起请求。</p>
    *
    * @return 是否启用懒加载
    */
    boolean lazy() default false;

    /**
    * 是否启用异步调用（针对当前引用）
    *
    * <p>覆盖全局 {@link RpcConsumerConfig#async} 配置，
    * 仅对当前服务引用生效。</p>
    *
    * @return 是否异步
    */
    boolean async() default false;

    /**
    * 当前服务引用的调用超时（单位：毫秒）
    *
    * <p>覆盖全局 {@link RpcConsumerConfig#timeout} 配置，
    * 仅对当前服务引用生效。</p>
    *
    * @return 超时毫秒数，默认 {@code -1} 表示使用全局配置
    */
    int timeout() default -1;

    /**
    * 缓存策略
    *
    * <p>控制当前服务引用是否开启结果缓存。常见取值：</p>
    * <ul>
    *   <li>{@code "lru"} — 最近最少使用缓存</li>
    *   <li>{@code "threadlocal"} — 线程级缓存</li>
    *   <li>{@code "jcache"} — JCache 标准缓存</li>
    *   <li>空字符串 — 不缓存</li>
    * </ul>
    *
    * @return 缓存策略
    */
    String cache() default "";

    /**
    * 使用的通信协议
    *
    * <p>Dubbo 场景下指定具体的远程调用协议，如 "dubbo"、"rest"、"grpc"、"thrift" 等。
    * 当 {@link #client()} 指定为 "dubbo" 时，此参数决定底层使用哪种协议进行数据传输。</p>
    *
    * @return 协议名称
    */
    String protocol() default "";
}
