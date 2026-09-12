package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.Map;

/**
* 服务器过滤器接口，用于实现协议无关的请求处理逻辑。
* <p>
* {@code ServerFilter} 是 {@link Server} 的核心扩展点，允许在请求处理和响应发送过程中插入自定义逻辑。
* 通过 {@link #supportProtocols()} 方法声明该过滤器支持的协议类型。
* </p>
* <p>
* 控制流方式：
* <ul>
*   <li><b>放行</b>：调用 {@link ServerFilterChain#doFilter(ServerRequest, ServerResponse)} 以继续执行链中的下一个过滤器。</li>
*   <li><b>终止</b>：调用 {@link ServerResponse#end()} 结束响应并阻止链中后续过滤器的执行。</li>
* </ul>
* </p>
*
* @author CH
* @version 2.0
* @since 2026/07/16
 */
public interface ServerFilter {

    /**
    * 执行过滤器逻辑。
    * <p>
    * 在此方法中处理请求和响应。若需继续执行后续过滤器，必须调用 {@code chain.doFilter(request, response)}。
    * 若已处理完毕（如直接返回响应），则无需调用该方法，防止重复处理。
    * </p>
    *
    * @param request  当前请求对象
    * @param response 当前响应对象
    * @param chain    过滤器链，用于传递到下一个过滤器
    * @throws Exception 处理过程中可能抛出的异常
     */
    void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception;

    /**
    * 获取过滤器的执行顺序。
    * <p>
    * 数值越小，优先级越高，越先执行。默认值为 100。
    * </p>
    *
    * @return 执行顺序值
     */
    default int getOrder() {
        return 100;
    }

    /**
    * 判断该过滤器是否启用。
    * <p>
    * 返回 false 时，该过滤器将被跳过。
    * </p>
    *
    * @return true 表示启用，false 表示禁用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
    * 获取过滤器的唯一标识符。
    * <p>
    * 默认使用全限定类名作为 ID。
    * </p>
    *
    * @return 过滤器 ID
     */
    default String getFilterId() {
        return getClass().getName();
    }

    /**
    * 初始化过滤器。
    * <p>
    * 在过滤器被添加到过滤器链之前调用，可用于读取配置或初始化资源。
    * </p>
    *
    * @param config 过滤器配置信息
    * @throws Exception 初始化失败时抛出异常
     */
    default void init(ServerFilterConfig config) throws Exception {
    }

    /**
    * 销毁过滤器。
    * <p>
    * 当服务器关闭或过滤器被移除时调用，用于释放资源。
    * </p>
     */
    default void destroy() {
        // NOTHING
    }

    /**
    * 更新过滤器配置。
    * <p>
    * 支持动态配置更新。
    * </p>
    *
    * @param config 新的配置参数
     */
    default void updateConfig(Map<String, Object> config) {
    }

    /**
    * 返回该过滤器支持的协议类型数组。
    * <p>
    * 如果返回空数组，表示该过滤器适用于所有协议类型。
    * </p>
    *
    * @return 支持的协议类型数组
     */
    default ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
    * 返回该过滤器绑定的路径模式。
    *
    * <p>用于区分过滤器的执行范围：</p>
    * <ul>
    *   <li>{@code null}（默认）— Access Filter，每次请求都触发</li>
    *   <li>具体路径 — Endpoint Filter，仅匹配路径时触发</li>
    * </ul>
    *
    * <h2>路径模式</h2>
    * <ul>
    *   <li>{@code /health} — 精确匹配</li>
    *   <li>{@code /api/**} — 前缀匹配</li>
    *   <li>{@code /api/*} — 单级匹配</li>
    * </ul>
    *
    * @return 路径模式，null 表示每次请求都触发（Access Filter）
     */
    default String supportPath() {
        return null;
    }

    /**
    * 判断该过滤器是否为 Access Filter（每次请求都触发）。
    *
    * @return true 表示是 Access Filter
     */
    default boolean isAccessFilter() {
        return supportPath() == null;
    }

    /**
    * 判断该过滤器是否支持指定的协议类型。
    * <p>
    * 内部逻辑基于 {@link #supportProtocols()} 的实现。
    * </p>
    *
    * @param protocol 待检查的协议类型
    * @return 如果支持返回 true，否则返回 false
     */
    default boolean supportProtocol(ProtocolType protocol) {
        ProtocolType[] types = supportProtocols();
        if (types.length == 0) {
            return true;
        }
        for (ProtocolType t : types) {
            if (t == protocol) {
                return true;
            }
            // KCP 底层基于 UDP 传输：声明支持 UDP 的过滤器对 KCP 消息同样生效（KCP 独立分类，但与 UDP 兼容）
            if (protocol == ProtocolType.KCP && t == ProtocolType.UDP) {
                return true;
            }
        }
        return false;
    }
}