// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
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
     * 管理端点配置引用;仅当通过 {@link #HealthCheckServerFilter(ServerSetting.ManagementConfig)}
     * 构造时非 null,此时每次请求实时读取 {@code healthEnabled} 开关。
     */
    private final ServerSetting.ManagementConfig config;

    /**
     * 创建健康检查过滤器(使用默认路径:/healthz、/ping、/readyz)。
     *
     * <p>该构造器创建的实例始终启用(不受 ManagementConfig 开关控制),
     * 供使用方手动 {@code addFilter} 时使用。</p>
     */
    public HealthCheckServerFilter() {
        this.healthPaths = DEFAULT_HEALTH_PATHS;
        this.config = null;
    }

    /**
     * 创建健康检查过滤器(自定义路径)。
     *
     * <p>该构造器创建的实例始终启用(不受 ManagementConfig 开关控制)。</p>
     *
     * @param healthPaths 健康检查路径集合(精确匹配)
     */
    public HealthCheckServerFilter(Set<String> healthPaths) {
        this.healthPaths = Set.copyOf(healthPaths);
        this.config = null;
    }

    /**
     * 创建健康检查过滤器(由 {@link ServerSetting.ManagementConfig} 驱动)。
     *
     * <p>供 {@code AbstractServer} 挂载内建探针使用:过滤器在服务构造期挂载,
     * 而配置可能在构造后才回填,因此持有配置引用、每次请求实时读取开关;
     * {@code healthEnabled=false}(默认)时探针路径不短路,直接放行进入后续链路。</p>
     *
     * @param config 管理端点配置,可为 null(视为未启用)
     */
    public HealthCheckServerFilter(ServerSetting.ManagementConfig config) {
        this.config = config;
        Set<String> configured = config != null ? config.getHealthPaths() : null;
        this.healthPaths = (configured != null && !configured.isEmpty())
                ? Set.copyOf(configured) : DEFAULT_HEALTH_PATHS;
    }

    /**
     * 判断探针端点当前是否启用。
     *
     * <p>构造期未绑定配置时恒为启用;绑定配置后按 {@code healthEnabled} 实时判定。</p>
     *
     * @return true 表示启用
     */
    private boolean isEndpointEnabled() {
        return config == null || config.isHealthEnabled();
    }

    /**
     * 获取Order:最先执行,保证探针请求短路整条链
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 10;
    }

    /**
     * SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突)
     */
    @Override
    public String supportPath() {
        return null;
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 探针未启用或路径未命中:放行进入后续链路(路由匹配/业务处理)
        if (isEndpointEnabled() && isHealthPath(request)) {
            respondOk(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (isEndpointEnabled() && isHealthPath(request)) {
            respondOk(response);
            return CompletableFuture.completedFuture(null);
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