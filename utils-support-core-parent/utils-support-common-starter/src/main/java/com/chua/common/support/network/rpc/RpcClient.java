package com.chua.common.support.network.rpc;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collections;
import java.util.List;

/**
* RPC 客户端 SPI 接口，定义远程服务代理获取与生命周期管理。
*
* <h2>使用示例</h2>
* <pre>{@code
* RpcClient client = RpcClient.createClient("dubbo",
*     Collections.singletonList(registryConfig), consumerConfig, "my-app");
* HelloService helloService = client.get(HelloService.class);
* String result = helloService.sayHello("World");
* client.close();
* }</pre>
*
* <h2>SPI 实现</h2>
* <ul>
*   <li>{@code @Spi("dubbo")} — DubboRpcClient（Apache Dubbo）</li>
*   <li>{@code @Spi("sofa")} — SofaRpcClient（蚂蚁 SOFA-RPC）</li>
*   <li>{@code @Spi("json")} — JsonRpcClient（JSON-RPC 2.0）</li>
* </ul>
*
* @author CH
* @since 1.0.0
 */
public interface RpcClient extends AutoCloseable {

    /**
    * 创建Client
    * @param name name
    * @param registryConfig registryConfig
    * @param consumerConfig consumerConfig
    * @param appName appName
    */
    static RpcClient createClient(String name, RpcRegistryConfig registryConfig,
                                  RpcConsumerConfig consumerConfig, String appName) {
        return createClient(name, Collections.singletonList(registryConfig), consumerConfig, appName);
    }

    /**
    * 工厂方法：根据协议名称创建 RPC 客户端实例。
    *
    * @param name              协议名称（SPI 扩展名），如 "dubbo"、"sofa"、"json"
    * @param registryConfigs   注册中心配置列表，支持多注册中心
    * @param consumerConfig    消费者配置
    * @param appName           应用名称
    * @return RPC 客户端实例
    */
    static RpcClient createClient(String name, List<RpcRegistryConfig> registryConfigs,
                                  RpcConsumerConfig consumerConfig, String appName) {
        return ServiceProvider.of(RpcClient.class)
                .getNewExtension(name, registryConfigs, consumerConfig, appName);
    }

    /**
    * 创建远程服务的本地代理对象。
    *
    * @param targetType 目标远程服务接口的 Class 对象
    * @param <T>        接口泛型
    * @return 远程服务的本地动态代理对象
    * @throws IllegalStateException 当注册中心无可用节点时抛出
    */
    <T> T get(Class<T> targetType);
}
