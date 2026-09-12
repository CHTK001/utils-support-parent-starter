package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletionStage;

/**
* 响应式服务器过滤器接口。
*
* <p>与同步 {@link ServerFilter} 不同，本接口的 {@link #doFilter} 返回
* {@link CompletionStage}，调用方无需阻塞等待过滤链执行结果。</p>
*
* <p>同步 {@link ServerFilter} 也可通过 <b>包装</b> 接入响应式链：
* 只需在同步 {@code doFilter} 内部调用 {@code chain.doFilter(...)} 并将返回值包装为
* {@code CompletableFuture.completedStage(null)} 即可。</p>
*
* @author CH
* @since 2026/07/16
 */
public interface ReactiveServerFilter {

    /**
    * 执行响应式过滤器逻辑。
    *
    * <p>若需继续执行后续过滤器，必须调用 {@code chain.doFilter(request, response)} 并返回其结果。
    * 若已处理完毕（如直接返回响应），则无需调用该方法。</p>
    *
    * @param request  当前请求对象
    * @param response 当前响应对象
    * @param chain    响应式过滤器链，用于传递到下一个过滤器
    * @return 过滤链执行完成的阶段
     */
    CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response, ReactiveFilterChain chain);

    /**
    * 获取过滤器的执行顺序。
    *
    * <p>数值越小，优先级越高，越先执行。默认值为 100。</p>
    *
    * @return 执行顺序值
     */
    default int getOrder() {
        return 100;
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
    * @return 路径模式，null 表示每次请求都触发（Access Filter）
     */
    default String supportPath() {
        return null;
    }
}