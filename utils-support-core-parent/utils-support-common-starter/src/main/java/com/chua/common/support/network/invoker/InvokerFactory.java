package com.chua.common.support.network.invoker;

import com.chua.common.support.network.invoker.annotations.RemoteService;
import com.chua.common.support.spi.ServiceProvider;

/**
* {@link Invoker} 工厂，自动选择当前环境最高优先级的 {@code Invoker} SPI 实现。
*
* <p>通过 SPI 机制按优先级自动选择可用的 {@code Invoker} 实现：</p>
* <ol>
*   <li>RetrofitHttpInvoker（order=100，需 retrofit2 依赖）</li>
*   <li>RpcInvoker（order=50，RPC 协议调用）</li>
*   <li>IpcInvoker（order=40，IPC 协议调用）</li>
*   <li>HttpInvoker（order=0，默认兜底，无外部依赖）</li>
* </ol>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* // 接口标注 @RemoteService 注解，自动路由
* \@RemoteService(url = "http://api.example.com", path = "/api")
* public interface UserApi {
*     \@GetMapping("/users/{id}")
*     User getUser(@PathVariable("id") Long id);
* }
*
* // 统一创建，自动根据 @RemoteService.protocol 选择 Invoker
* UserApi api = InvokerFactory.create(UserApi.class);
*
* // 按名称手动指定协议
* Invoker rpcInvoker = InvokerFactory.getInvoker("rpc");
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see Invoker
* @see HttpInvoker
 */
public class InvokerFactory {

    /** 创建 InvokerFactory 实例 */
    private InvokerFactory() {
    }

    /**
    * 获取当前环境最高优先级的 {@code Invoker} 实例。
    *
    * @return Invoker 实例，无可用的 SPI 实现时返回 null
     */
    public static Invoker getInvoker() {
        return ServiceProvider.of(Invoker.class).getPriority();
    }

    /**
    * 按 SPI 名称获取指定协议的 {@code Invoker} 实例。
    *
    * @param name SPI 名称，如 "http"、"rpc"、"ipc"
    * @return Invoker 实例，未找到时返回 null
     */
    public static Invoker getInvoker(String name) {
        return ServiceProvider.of(Invoker.class).getExtension(name);
    }

    /**
    * 为指定接口创建动态代理。
    *
    * <p>优先读取接口上的 {@code @RemoteService} 注解的 {@code protocol} 属性
    * 自动选择对应的 Invoker 实现；无注解时使用最高优先级 Invoker。</p>
    *
    * @param <T>      接口类型
    * @param apiClass 接口类
    * @return 动态代理实现
     */
    public static <T> T create(Class<T> apiClass) {
        Invoker invoker = resolveInvoker(apiClass);
        if (invoker == null) {
            throw new IllegalStateException("未找到可用的 Invoker SPI 实现");
        }
        return invoker.create(apiClass);
    }

    /**
    * 按 SPI 名称创建动态代理。
    *
    * @param <T>      接口类型
    * @param name     SPI 名称，如 "http"、"rpc"、"ipc"
    * @param apiClass 接口类
    * @return 动态代理实现
     */
    public static <T> T create(String name, Class<T> apiClass) {
        Invoker invoker = getInvoker(name);
        if (invoker == null) {
            throw new IllegalStateException("未找到名称为 " + name + " 的 Invoker SPI 实现");
        }
        return invoker.create(apiClass);
    }

    /**
    * 根据接口上的 {@code @RemoteService} 注解解析对应的 Invoker 实现。
    *
    * @param apiClass 接口类
    * @return Invoker 实例
     */
    private static Invoker resolveInvoker(Class<?> apiClass) {
        RemoteService rs = apiClass.getAnnotation(RemoteService.class);
        if (rs != null) {
            String protocol = rs.protocol();
            if (!"http".equals(protocol)) {
                Invoker invoker = getInvoker(protocol);
                if (invoker != null) {
                    return invoker;
                }
            }
        }
        return getInvoker();
    }
}