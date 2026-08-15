package com.chua.common.support.network.server;

import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.*;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.http.ConfigServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.resolver.HandlerMethodArgumentResolver;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 服务器抽象基类，协议无关。
 * <p>提供：
 * <ul>
 *   <li>生命周期 — {@link #start()} / {@link #stop()} / {@link #doStart()} / {@link #doStop()}</li>
 *   <li>过滤器管理 — 内置 GzipFilter / AccessLogFilter / CorsFilter + SPI 发现 + 用户自定义</li>
 *   <li>请求处理 — {@link #handleRequest(ServerRequest, ServerResponse)} 统一执行链 + 指标 + 限流</li>
 *   <li>运行时指标 — {@link #getMetrics()} 提供请求计数、活跃数、错误数</li>
 * </ul>
 *
 * @author CH
 * @version 2.1
 * @since 2026/07/16
 */
@Slf4j
public abstract class AbstractServer implements ConfigServer {

    /**
     * 服务器配置设置。
     */
    protected final ServerSetting setting;

    /**
     * 过滤器管理器。
     * -- GETTER --
     * 获取指定事件类型的监听器列表，供协议 Server 在合适时机派发。
     * <p>例如 MqttServer 在客户端连接时派发 "open" 事件，
     * WebSocket Server 在会话建立时派发 "open" 事件等。</p>
     *
     * @return 监听器条目列表，可能为 null
     */
    @Getter
    protected final ServerFilterManager filterManager;

    /**
     * URL映射过滤器，用于路由匹配。
     */
    protected UrlMappingServerFilter urlMappingFilter;

    /**
     * 服务器运行指标统计。
     */
    @Getter
    protected final ServerMetrics metrics = new ServerMetrics();

    /**
     * 服务器是否正在运行。
     */
    protected volatile boolean running;

    /**
     * 对象上下文，用于依赖注入和 Bean 管理。
     */
    protected ObjectContext objectContext;

    /**
     * 并发限制信号量。
     */
    private Semaphore concurrencyLimiter;

    /**
     * 构造函数，初始化服务器基础组件。
     *
     * @param setting 服务器配置
     */
    protected AbstractServer(ServerSetting setting) {
        this.setting = setting != null ? setting : ServerSetting.defaults();
        this.filterManager = new ServerFilterManager(getProtocolType());
        initConcurrencyLimit();

        // 默认创建一个轻量 ObjectContext，使 registerBean() / @AutoInject 等能力开箱即用
        if (this.objectContext == null) {
            this.objectContext = new DefaultObjectContext(ObjectContextConfig.defaults());
            this.objectContext.init();
        }
        setObjectContext(objectContext);
        initBuiltinFilters();
    }

    /**
     * 初始化内置过滤器。
     */
    private void initBuiltinFilters() {
        addFilter(new GzipFilter());
        addFilter(new TracingFilter());
        addFilter(new AccessLogFilter());
        addFilter(new CorsFilter());
        if (urlMappingFilter != null) {
            addFilter(urlMappingFilter);
        }
    }

    /**
     * 初始化并发限制器。
     */
    private void initConcurrencyLimit() {
        int max = setting.getMaxConcurrency();
        if (max > 0) {
            this.concurrencyLimiter = new Semaphore(max);
        } else {
            this.concurrencyLimiter = null;
        }
    }

    @Override
    public boolean supportsReactor() {
        return false;
    }

    /**
     * 处理请求的统一入口。
     *
     * @param request  请求对象
     * @param response 响应对象
     */
    protected void handleRequest(ServerRequest request, ServerResponse response) {
        metrics.incrementRequests();
        request.setAttribute("_server", this);
        if (concurrencyLimiter != null && !concurrencyLimiter.tryAcquire()) {
            response.setStatus(503);
            response.setBody("Service Unavailable: too many concurrent requests");
            if (!response.isEnded()) {
                response.end();
            }
            metrics.incrementErrors();
            return;
        }
        metrics.incrementActive();
        long start = System.nanoTime();
        try {
            if (supportsReactor() && setting.isReactor()) {
                handleReactive(request, response);
            } else {
                handleBlocking(request, response);
            }
        } finally {
            metrics.recordLatency(System.nanoTime() - start);
            metrics.decrementActive();
            if (concurrencyLimiter != null) {
                concurrencyLimiter.release();
            }
        }
    }

    /**
     * 处理响应式请求。
     *
     * @param request  请求
     * @param response 响应
     */
    protected void handleReactive(ServerRequest request, ServerResponse response) {
@SuppressWarnings("unchecked")
        List<FilterChainListener> listeners = (List<FilterChainListener>) request.getAttribute("_chainListeners");

        DefaultReactiveFilterChain reactiveChain = new DefaultReactiveFilterChain(
                filterManager.getMergedReactiveFilters(), DEFAULT_404_HANDLER);
        reactiveChain.doFilter(request, response).whenComplete((result, ex) -> {
            if (ex != null) {
                metrics.incrementErrors();
                log.warn("响应式请求处理异常: {}", ex.getMessage(), ex);
                if (!response.isEnded()) {
                    try {
                        response.sendError(500, "Internal Server Error");
                    } catch (Exception e) {
                        log.warn("发送 500 错误失败: {}", e.getMessage(), e);
                    }
                }
            } else if (!response.isEnded()) {
                // 路径变量 / 查询参数尚未在此处理，交由具体 Handler 负责
                if (response.getResult() != null) {
                    convertResult(response);
                }
                try {
                    response.end();
                } catch (Exception e) {
                    log.warn("响应 end() 失败: {}", e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 默认的 404 处理器。
     */
    private static final ServerHandler DEFAULT_404_HANDLER = (req, res) -> {
        if (!res.isEnded()) {
            res.sendError(404, "Not Found");
        }
    };

    /**
     * 处理阻塞请求。
     *
     * @param request  请求
     * @param response 响应
     */
    private void handleBlocking(ServerRequest request, ServerResponse response) {
        try {
            List<FilterChainListener> listeners = (List<FilterChainListener>) request.getAttribute("_chainListeners");
            DefaultServerFilterChain chain = new DefaultServerFilterChain(
                    filterManager.getMergedFilters(), DEFAULT_404_HANDLER, listeners);
            chain.doFilter(request, response);

            // 链回卷后统一处理：结果转换 → end()
            if (!response.isEnded()) {
                convertResult(response);
            }
        } catch (Exception e) {
            metrics.incrementErrors();
            log.warn("请求处理异常: {}", e.getMessage(), e);
            if (!response.isEnded()) {
                try {
                    response.sendError(500, "Internal Server Error");
                } catch (Exception ex) {
                    log.warn("发送 500 错误失败: {}", ex.getMessage(), ex);
                }
            }
        } finally {
            if (!response.isEnded()) {
                try {
                    response.end();
                } catch (Exception e) {
                    log.warn("响应 end() 失败: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 将 response.getResult() 转换为响应体并 end()。
     * <p>优先走 {@link ResponseConverter} SPI，
     * 找不到则直接 toString()。</p>
     */
    private void convertResult(ServerResponse response) {
        Object result = response.getResult();
        if (result == null) {
            return;
        }
        // SPI 转换器
        var converters = com.chua.common.support.spi.ServiceProvider.of(ResponseConverter.class).list();
        for (var converter : converters.values().stream()
                .sorted(java.util.Comparator.comparingInt(ResponseConverter::getOrder)).toList()) {
            if (converter.support(result)) {
                try {
                    converter.convert(response, result);
                } catch (Exception e) {
                    log.warn("ResponseConverter 转换失败: {}", e.getMessage(), e);
                }
                return;
            }
        }
        // 兜底：String 直接写，其他 toString()
        if (result instanceof String s) {
            response.setBody(s);
        } else if (result instanceof byte[] b) {
            response.setBody(b);
        } else {
            response.setBody(result.toString());
        }
    }

    @Override
    public final synchronized void start() {
        if (running) {
            return;
        }
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
                for (ServerFilter filter : filters.values()) {
                    if (filter instanceof ReactiveServerFilter reactiveFilter) {
                        filterManager.addReactiveFilter(reactiveFilter);
                    }
                }
            }
        }
        try {
            filterManager.initFilters(new SimpleServerFilterConfig(setting));
            doStart();
            running = true;
            log.info("服务器启动成功：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
        } catch (RuntimeException e) {
            filterManager.destroyFilters();
            throw e;
        }
    }

    /**
     * 启动服务器的逻辑。
     * <p>默认实现为空，由具体实现类完成启动逻辑。</p>
     */
    protected abstract void doStart();

    @Override
    public final synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        doStopAccepting();
        int quietPeriod = setting.getShutdownQuietPeriod();
        if (quietPeriod > 0) {
            log.info("服务器开始优雅关闭，等待活跃请求完成（最长 {}s）...", quietPeriod);
            long deadline = System.currentTimeMillis() + quietPeriod * 1000L;
            while (metrics.getActiveRequests() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long remaining = metrics.getActiveRequests();
            if (remaining > 0) {
                log.warn("优雅关闭超时，尚有 {} 个请求未完成", remaining);
            } else {
                log.info("所有活跃请求已处理完成");
            }
        }
        filterManager.destroyFilters();
        doStop();
        log.info("服务器已停止：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
    }

    /**
     * 停止服务器的逻辑。
     * <p>默认实现为空，由具体实现类完成停止逻辑。</p>
     */
    protected abstract void doStop();

    /**
     * 停止接收新的请求。
     * <p>默认实现为空，传输层可在优雅关闭等待开始前关闭监听端口，
     * 避免等待活跃请求期间继续接收新请求。</p>
     */
    protected void doStopAccepting() {
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public int getPort() {
        return setting.getPort();
    }

    @Override
    public ServerSetting getSetting() {
        return setting;
    }

    @Override
    public ObjectContext getObjectContext() {
        return objectContext;
    }

    @Override
    public void setObjectContext(ObjectContext objectContext) {
        this.objectContext = objectContext;
        this.urlMappingFilter = new UrlMappingServerFilter(objectContext);
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
                for (ServerFilter filter : filters.values()) {
                    if (filter instanceof ReactiveServerFilter reactiveFilter) {
                        filterManager.addReactiveFilter(reactiveFilter);
                    }
                }
            }
        }
    }

    @Override
    public List<ServerFilter> getFilters() {
        return filterManager.getStaticFilters();
    }

    @Override
    public Server addFilter(ServerFilter filter) {
        filterManager.addFilter(filter);
        if (filter instanceof ReactiveServerFilter reactiveFilter) {
            filterManager.addReactiveFilter(reactiveFilter);
        }
        return this;
    }

    @Override
    public Server removeFilter(ServerFilter filter) {
        filterManager.removeFilter(filter);
        return this;
    }

    @Override
    public Server registerMapping(String path, HttpMethod method, ServerHandler handler) {
        urlMappingFilter.route(path, method, handler);
        return this;
    }

    @Override
    public Server registerMapping(String path, ServerHandler handler) {
        urlMappingFilter.route(path, handler);
        return this;
    }

    @Override
    public Server removeMapping(String path) {
        urlMappingFilter.removeRoute(path);
        return this;
    }

    @Override
    public Server refreshFilters() {
        filterManager.refreshSpiFilters();
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
            }
        }
        return this;
    }

    @Override
    public Server registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        objectContext.registerBean(bean);
        return this;
    }

    /**
     * 通过 SPI 发现 {@link HandlerMethodArgumentResolver} 实现并注册到 {@link ObjectContext}，
     * 同时将 {@link UrlMappingServerFilter} 注入容器。
     */

    @Override
    public Server unregisterBean(Object bean) {
        if (bean == null || objectContext == null) {
            return this;
        }
        objectContext.unregisterBean(bean);
        return this;
    }

    /**
     * 简单的 ServerFilterConfig 实现，用于内置过滤器。
     */
    private static class SimpleServerFilterConfig implements ServerFilterConfig {
        private final ServerSetting setting;

        SimpleServerFilterConfig(ServerSetting setting) {
            this.setting = setting;
        }

        @Override
        public String getFilterName() {
            return "default";
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Map<String, String> getInitParameters() {
            return Map.of();
        }

        @Override
        public ServerSetting getServerSetting() {
            return setting;
        }
    }
}
