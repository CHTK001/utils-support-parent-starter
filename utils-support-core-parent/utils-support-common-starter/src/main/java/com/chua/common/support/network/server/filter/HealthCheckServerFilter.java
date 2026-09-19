package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 健康检查过滤器。
 *
 * <p>对健康探针路径(/healthz、/ping、/readyz)短路返回 200,
 * 不进入路由匹配与业务 handler,提供零开销探针端点;
 * 压测场景也可将其作为裸传输层吞吐基准(ping 路径)。</p>
 *
 * <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
 * 两种链接口:阻塞传输走同步链,NIO/AIO 响应式传输走响应式链。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class HealthCheckServerFilter implements ServerFilter, ReactiveServerFilter {

    /**
     * 默认健康检查路径集合
     */
    private static final Set<String> DEFAULT_HEALTH_PATHS =
            Set.of("/healthz", "/ping", "/readyz");

    /**
     * HTTP 200 状态码
     */
    private static final int STATUS_OK = 200;

    /**
     * 健康检查路径集合(精确匹配)
    */
    private final Set<String> healthPaths;

    /**
     * 创建健康检查过滤器(使用默认路径:/healthz、/ping、/readyz)。
     */
    public HealthCheckServerFilter() {
        this.healthPaths = DEFAULT_HEALTH_PATHS;
    }

    /**
     * 创建健康检查过滤器(自定义路径)。
     *
     * @param healthPaths 健康检查路径集合(精确匹配)
     */
    public HealthCheckServerFilter(Set<String> healthPaths) {
        this.healthPaths = Set.copyOf(healthPaths);
    }

    @Override
    /**
     * 获取Order:最先执行,保证探针请求短路整条链
    */
    public int getOrder() {
        return Integer.MIN_VALUE + 10;
    }

    @Override
    /**
     * SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突)
     */
    public String supportPath() {
        return null;
    }

    @Override
    /**
     * SupportProtocols
    */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 探针路径命中:直接响应并终止链,不进入路由与业务处理
        if (isHealthPath(request)) {
            respondOk(response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (isHealthPath(request)) {
            respondOk(response);
            return CompletableFuture.completedStage(null);
        }
        return chain.doFilter(request, response);
    }

    /**
     * 判断请求路径是否为健康探针路径。
     *
     * @param request 请求对象
     * @return true 表示命中探针路径
     */
    private boolean isHealthPath(ServerRequest request) {
        return healthPaths.contains(request.getPath());
    }

    /**
     * 输出 200 OK 探针响应。
     *
     * @param response 响应对象
     */
    private void respondOk(ServerResponse response) {
        if (!response.isEnded()) {
            response.setStatus(STATUS_OK);
            response.setBody("OK");
            response.end();
        }
    }
}
