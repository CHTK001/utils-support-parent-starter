package com.chua.common.support.network.rpc;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.function.InitializingAware;
import com.chua.common.support.utils.ClassUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * RPC 服务端 SPI 接口，定义 RPC 服务的注册暴露与生命周期管理。
 *
 * <p>作为所有 RPC 框架服务端的顶层抽象，通过 SPI 机制支持 Dubbo、SOFA、JSON-RPC
 * 等多种协议的即插即用式切换。服务端负责：</p>
 * <ul>
 *   <li>监听指定端口，接收客户端远程调用请求</li>
 *   <li>将本地服务实现对象注册为远程可调用服务</li>
 *   <li>管理服务的生命周期（启动、运行、关闭）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 Dubbo RPC 服务端
 * RpcServer server = RpcServer.createService("dubbo",
 *     Collections.singletonList(registryConfig),
 *     protocolConfig, "my-app");
 *
 * // 注册本地实现为远程服务
 * server.register("com.example.HelloService", new HelloServiceImpl());
 * }</pre>
 *
 * <h2>SPI 实现</h2>
 * <ul>
 *   <li>{@code @Spi("dubbo")} — DubboRpcServer（Apache Dubbo）</li>
 *   <li>{@code @Spi("sofa")} — SofaRpcServer（蚂蚁 SOFA-RPC）</li>
 *   <li>{@code @Spi("json")} — JsonRpcServer（JSON-RPC 2.0）</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public interface RpcServer extends AutoCloseable, InitializingAware {

    /**
     * 创建Service
     * @param name name
     * @param registryConfig registryConfig
     * @param protocolConfig protocolConfig
     * @param appName appName
     */
    static RpcServer createService(String name, RpcRegistryConfig registryConfig,
                                   RpcProtocolConfig protocolConfig, String appName) {
        return createService(name, Collections.singletonList(registryConfig), protocolConfig, appName);
    }

    /**
     * 工厂方法：根据协议名称创建 RPC 服务端实例。
     *
     * @param name              协议名称（SPI 扩展名），如 "dubbo"、"sofa"、"json"
     * @param registryConfigs   注册中心配置列表，支持多注册中心
     *                          为 {@code null} 或空列表时表示不使用注册中心
     * @param protocolConfig    协议配置，为 {@code null} 时使用实现类默认配置
     * @param appName           应用名称
     * @return RPC 服务端实例
     */
    static RpcServer createService(String name, List<RpcRegistryConfig> registryConfigs,
                                   RpcProtocolConfig protocolConfig, String appName) {
        return ServiceProvider.of(RpcServer.class)
                .getNewExtension(name, registryConfigs, protocolConfig, appName);
    }

    /**
     * 自动注册：扫描 Bean 实现的所有接口并全部注册为远程服务。
     *
     * @param bean 服务实现实例
     * @return 当前实例自身（支持链式调用）
     */
    default RpcServer register(Object bean) {
        Set<Class<?>> allInterfaces = ClassUtils.getAllInterfaces(bean.getClass());
        for (Class<?> iface : allInterfaces) {
            register(iface.getTypeName(), bean);
        }
        return this;
    }

    /**
     * 向 RPC 框架注册一个服务实现对象。
     *
     * @param name 服务接口的全限定类名
     * @param bean 服务实现对象的实例
     * @return 当前实例自身（支持链式调用）
     */
    RpcServer register(String name, Object bean);
}