package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* 远程服务标记注解，标注在接口上表示该接口可通过 {@code Invoker} 创建远程代理。
*
* <p>这是使用 {@code Invoker} 的<b>统一入口注解</b>。类级别指定服务地址、路径前缀、协议和负载均衡策略。</p>
*
* <p><b>注解优先级（从高到低）：</b></p>
* <ol>
*   <li><b>协议私有注解</b> — 如 {@code @RequestMapping}、{@code @RequestMethod}（类级 URL）
*      和 {@code @GetMapping}、{@code @PostMapping}、{@code @IpcMethod}、{@code @RemoteMethod}（方法级路径）</li>
*   <li><b>@RemoteService</b> — 本注解类级配置</li>
*   <li><b>@InvokerService</b> — 简化版兼容注解</li>
* </ol>
*
* <p>协议私有注解的优先级最高，当存在时优先使用其中的 URL 或路径信息。
* {@code @RemoteService} 作为兜底，为未使用私有注解的场景提供统一配置入口。</p>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* // HTTP 调用（使用 @GetMapping 等私有注解，优先级高）
* \@RemoteService(url = "http://api.example.com", path = "/api")
* public interface UserApi {
*     \@GetMapping("/users/{id}")
*     User getUser(@PathVariable("id") Long id);
* }
*
* // RPC 调用（无协议私有注解，使用 @RemoteMethod）
* \@RemoteService(url = "http://localhost:8080/jsonrpc", protocol = "rpc")
* public interface UserApi {
*     \@RemoteMethod("/users/{id}")
*     User getUser(Long id);
* }
*
* // IPC 调用（使用 @IpcMethod 私有注解，优先级高）
* \@RemoteService(url = "http://localhost:8080", protocol = "ipc")
* public interface UserApi {
*     \@IpcMethod("getUserById")
*     User getUser(Long id);
* }
*
* // 统一创建，自动根据 protocol 选择 Invoker
* UserApi api = InvokerFactory.create(UserApi.class);
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see InvokerFactory
* @see RemoteMethod
* @see RemoteParameter
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteService {

    /**
    * 服务地址（基础 URL）。
    *
    * <p>优先级低于 {@code @RequestMapping} 和 {@code @RequestMethod} 类级注解。</p>
    *
    * @return 基础 URL
    */
    String url() default "";

    /**
    * 路径前缀，与方法级别路径组合成完整地址。
    *
    * <p>优先级低于 {@code @GetMapping}、{@code @PostMapping} 等协议私有注解中的路径。</p>
    *
    * @return 路径前缀
    */
    String path() default "";

    /**
    * 调用协议，决定使用哪个 Invoker 实现。
    *
    * <p>可选值：</p>
    * <ul>
    *   <li>{@code "http"}（默认）— HTTP 调用，支持 {@code @GetMapping}、{@code @PostMapping} 等</li>
    *   <li>{@code "rpc"} — RPC 调用，通过 {@code RpcClient} SPI 实现</li>
    *   <li>{@code "ipc"} — IPC 调用，通过 HTTP 请求 IPC 服务端</li>
    * </ul>
    *
    * @return 协议名称
    */
    String protocol() default "http";

    /**
    * RpcClient SPI 协议名（仅当 {@link #protocol()} 为 {@code "rpc"} 时生效）。
    *
    * <p>指定 {@code RpcClient.createClient()} 使用的具体协议实现，可选值：
    * {@code json}（JSON-RPC）、{@code dubbo}、{@code sofa}、{@code zmq}、{@code native} 等。
    * 未配置时默认 {@code json}，与既有 {@code RpcInvoker} 行为一致。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * // 通过 ZMQ RPC 调用
    * \@RemoteService(url = "tcp://127.0.0.1:5555", protocol = "rpc", client = "zmq")
    * public interface ZmqApi {
    *     String echo(String message);
    * }
    * }</pre>
    *
    * @return RPC 客户端协议名
    */
    String client() default "";

    /**
    * 负载均衡策略 SPI 名称。
    *
    * <p>复用 {@code com.chua.common.support.lang.balance.LoadBalance} SPI 机制：</p>
    * <ul>
    *   <li>{@code "random"}（默认）— 随机</li>
    *   <li>{@code "round"} / {@code "polling"} — 轮询</li>
    *   <li>{@code "weight"} — 权重</li>
    * </ul>
    *
    * @return 负载均衡策略名称
    */
    String balance() default "random";
}
