package com.chua.common.support.network.invoker.filter;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;

/**
* 内置注入过滤器，将 {@code @RemoteInject} 注解和 {@code addInject} 注册的注入规则
* 在每次调用时应用到请求头或共享上下文中。
*
* <p>自动通过 SPI 发现，无需手动注册。order 设为最高优先级（-1000），
* 确保在业务过滤器之前执行。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("invoker_inject")
public class InvokerInjectServerFilter implements ServerFilter {

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (request instanceof InvocationContext ctx) {
            SharedInvocationContext shared = getSharedContext(ctx);
            if (shared != null) {
                for (SharedInvocationContext.InjectRule rule : shared.getInjectRules()) {
                    String value = rule.callback().apply(ctx);
                    if (value != null) {
                        applyTarget(rule.target(), value, ctx, shared);
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 1000;
    }

    /**
     * 获取SharedContext
     * @param ctx 上下文，不允许为 null
     * @return SharedInvocation上下文 对象
     */
    private static SharedInvocationContext getSharedContext(InvocationContext ctx) {
        Object shared = ctx.getAttribute("sharedContext");
        return shared instanceof SharedInvocationContext s ? s : null;
    }

    /**
     * 应用Target
     * @param target 目标，不允许为 null
     * @param value 值，不允许为 null
     * @param ctx 上下文，不允许为 null
     * @param shared 方法入参 shared
     */
    private static void applyTarget(String target, String value, InvocationContext ctx, SharedInvocationContext shared) {
        if (target.startsWith("headers.")) {
            ctx.addHeader(target.substring(8), value);
            shared.addDefaultHeader(target.substring(8), value);
        } else if (target.startsWith("attributes.")) {
            ctx.setAttribute(target.substring(11), value);
            shared.setAttribute(target.substring(11), value);
        }
    }

    @Override
    /** 是否Enabled */
    public boolean isEnabled() {
        return true;
    }
}