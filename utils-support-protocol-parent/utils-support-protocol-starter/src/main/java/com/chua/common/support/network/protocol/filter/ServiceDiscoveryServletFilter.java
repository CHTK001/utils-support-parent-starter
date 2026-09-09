package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.discovery.DefaultServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.core.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 服务发现 Servlet 过滤器
 * <p>
 * 提供服务发现功能的 Servlet 过滤器，支持：
 * 1. 服务注册与发现
 * 2. 负载均衡策略选择
 * 3. 协议过滤
 * 4. 请求路径解析
 * 5. 服务实例选择
 * 6. 统计与监控
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("serviceDiscovery")
@SpiDescribe("服务发现过滤器")
public class ServiceDiscoveryServletFilter  extends AbstractServletFilter implements ServletFilter {

    /**
     * 是否启用过滤器
     */
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    
    /**
     * 统计信息
     */
    private final AtomicLong requestCount = new AtomicLong(0); // 请求总数
    private final AtomicLong successCount = new AtomicLong(0); // 成功请求数
    private final AtomicLong failureCount = new AtomicLong(0); // 失败请求数
    
    /**
     * 服务发现实例
     */
    @Getter
    private ServiceDiscovery serviceDiscovery;
    
    /**
     * 默认负载均衡策略
     */
    @Getter
    private String defaultBalance = "weight";
    
    /**
     * 此过滤器的协议类型
     */
    @Getter
    private String protocolType = "http";
    
    /**
     * 默认协议
     */
    @Getter
    private String defaultProtocol = "http";
    
    /**
     * 默认超时时间（毫秒）
     */
    @Getter
    private int defaultTimeout = 30000; // 30秒
    
    /**
     * 连接超时时间（毫秒）
     */
    @Getter
    private int connectTimeout = 10000; // 10秒
    
    /**
     * 读取超时时间（毫秒）
     */
    @Getter
    private int readTimeout = 30000; // 30秒
    
    /**
     * 过滤器名称
     */
    private String filterName = "DiscoveryServletFilter";
    
    /**
     * 过滤器优先级
     */
    private int priority = 100;
    
    /**
     * 是否自动启动服务发现
     */
    @Getter
    private boolean autoStart = true;

    /**
     * 默认构造函数，使用默认的ServiceDiscovery实现，协议类型为HTTP
     */
    public ServiceDiscoveryServletFilter() {
        this("http", new DefaultServiceDiscovery(new DiscoveryOption()));
    }

    /**
     * 构造函数
     *
     * @param protocolType 协议类型（http, tcp, udp等）
     */
    public ServiceDiscoveryServletFilter(String protocolType) {
        this(protocolType, new DefaultServiceDiscovery(new DiscoveryOption()));
    }

    /**
     * 构造函数
     *
     * @param serviceDiscovery 服务发现实例
     */
    public ServiceDiscoveryServletFilter(ServiceDiscovery serviceDiscovery) {
        this("http", serviceDiscovery);
    }

    /**
     * 构造函数
     *
     * @param protocolType     协议类型（http, tcp, udp等）
     * @param serviceDiscovery 服务发现实例
     */
    public ServiceDiscoveryServletFilter(String protocolType, ServiceDiscovery serviceDiscovery) {
        this.protocolType = protocolType != null ? protocolType.toLowerCase() : "http";
        this.filterName = "DiscoveryServletFilter-" + this.protocolType;
        this.serviceDiscovery = serviceDiscovery;
        if (autoStart) {
            startServiceDiscovery();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        if (!enabled.get()) {
            if (log.isDebugEnabled()) {
                log.debug("DiscoveryServletFilter禁用，跳过处理");
            }
            chain.doFilter(request, response);
            return;
        }

        requestCount.incrementAndGet();

        try {
            if (log.isDebugEnabled()) {
                log.debug("DiscoveryServletFilter开始处理请求: {} {}", request.getMethod(), request.getPath());
            }

            // 根据协议类型进行不同的处理
            Discovery discovery = null;
            String servicePath = null;
            String balance = getParameterWithDefault(request, "balance", defaultBalance);
            String protocol = getParameterWithDefault(request, "protocol", defaultProtocol);

            if ("http".equalsIgnoreCase(protocolType)) {
                // HTTP协议：根据URL路径进行服务发现
                servicePath = extractServicePath(request.getPath());
                if (StringUtils.isEmpty(servicePath)) {
                    if (log.isDebugEnabled()) {
                        log.debug("无法从路径 {} 提取服务名称，跳过服务发现", request.getPath());
                    }
                    chain.doFilter(request, response);
                    return;
                }

                if (log.isDebugEnabled()) {
                    log.debug("开始HTTP服务发现: 路径={}, 负载均衡={}, 协议={}", servicePath, balance, protocol);
                }

                // 从服务发现中查找服务实例
                discovery = serviceDiscovery.getService(servicePath, balance, protocol);

                // 如果没有找到，尝试从集群服务中查找
                if (discovery == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("在常规服务中未找到 {}，尝试从集群服务中查找", servicePath);
                    }
                    discovery = serviceDiscovery.getService("/cluster-services", balance, protocol);
                    if (discovery != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("在集群服务中找到服务: {} -> {}", servicePath, discovery.toFullString());
                        }
                    }
                }

            } else {
                // 非HTTP协议：根据目标主机和端口进行服务发现
                String targetHost = getParameterWithDefault(request, "target.host", null);
                String targetPort = getParameterWithDefault(request, "target.port", null);

                if (StringUtils.isNotEmpty(targetHost) && StringUtils.isNotEmpty(targetPort)) {
                    try {
                        // 构造服务路径
                        servicePath = "/" + targetHost + ":" + targetPort;

                        if (log.isDebugEnabled()) {
                            log.debug("开始{}协议服务发现: 目标={}:{}", protocolType.toUpperCase(), targetHost, targetPort);
                        }

                        // 尝试从服务发现中查找
                        discovery = serviceDiscovery.getService(servicePath, balance, protocolType);

                        // 如果没有找到，尝试从集群服务中查找
                        if (discovery == null) {
                            if (log.isDebugEnabled()) {
                                log.debug("在常规服务中未找到 {}，尝试从集群服务中查找", servicePath);
                            }
                            discovery = serviceDiscovery.getService("/cluster-services", balance, protocolType);
                            if (discovery != null) {
                                if (log.isDebugEnabled()) {
                                    log.debug("在集群服务中找到服务: {} -> {}", servicePath, discovery.toFullString());
                                }
                            }
                        }

                        // 如果服务发现中没有找到，创建默认的Discovery对象
                        if (discovery == null) {
                            discovery = Discovery.builder()
                                    .host(targetHost)
                                    .port(Integer.parseInt(targetPort))
                                    .protocol(protocol)
                                    .timeout(defaultTimeout)
                                    .build();

                            if (log.isDebugEnabled()) {
                                log.debug("创建默认Discovery对象: {}:{}", targetHost, targetPort);
                            }
                        }

                    } catch (NumberFormatException e) {
                        log.warn("无效的目标端口: {}", targetPort);
                        failureCount.incrementAndGet();
                        chain.doFilter(request, response);
                        return;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("{}协议缺少目标主机或端口信息，跳过服务发现", protocolType.toUpperCase());
                    }
                    chain.doFilter(request, response);
                    return;
                }
            }

            if (discovery != null) {
                // 将发现的服务实例设置到请求属性中
                request.setAttribute("ServiceDiscoveryServletFilter:discovery", discovery);
                request.setAttribute("ServiceDiscoveryServletFilter:servicePath", servicePath);
                request.setAttribute("ServiceDiscoveryServletFilter:balance", balance);
                request.setAttribute("ServiceDiscoveryServletFilter:protocol", protocol);
                request.setAttribute("ServiceDiscoveryServletFilter:protocolType", protocolType);

                // 设置超时时间
                int timeout = getTimeoutFromDiscovery(discovery, request);
                request.setAttribute("ServiceDiscoveryServletFilter:timeout", timeout);

                successCount.incrementAndGet();
                log.debug("服务发现成功: {} -> {} (protocolType={}, timeout={}ms)",
                        servicePath, discovery.toFullString(), protocolType, timeout);
            } else {
                failureCount.incrementAndGet();
                log.warn("服务发现失败: 未找到服务 {} (balance={}, protocol={})", servicePath, balance, protocol);

                // 可以选择设置错误响应或继续处理
                // response.setStatusCode(503);
                // response.setStatusMessage("Service Unavailable");
                // response.setBodyString("{\"error\":\"服务不可用\",\"service\":\"" + servicePath +
                // "\"}");
                // response.setContentType("application/json");
                // response.setTerminateEarly(true);
                // return;
            }

        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("服务发现处理异常: {}", e.getMessage(), e);
            // 异常情况下继续处理，不中断请求
        }

        // 继续过滤器链
        chain.doFilter(request, response);
    }

    /**
     * 从Discovery和请求中获取超时时间
     *
     * @param discovery 服务发现实例
     * @param request   请求对象
     * @return 超时时间（毫秒）
     */
    private int getTimeoutFromDiscovery(Discovery discovery, ServletRequest request) {
        // 1. 优先从请求参数中获取
        String timeoutParam = getParameterWithDefault(request, "timeout", null);
        if (timeoutParam != null) {
            try {
                int timeout = Integer.parseInt(timeoutParam);
                if (timeout > 0) {
                    return timeout;
                }
            } catch (NumberFormatException e) {
                log.warn("无效的超时参数: {}", timeoutParam);
            }
        }

        // 2. 从Discovery实例中获取
        if (discovery.getTimeout() > 0) {
            return discovery.getTimeout();
        }

        // 3. 使用默认超时时间
        return defaultTimeout;
    }

    /**
     * 从请求路径中提取服务名称
     *
     * @param path 请求路径
     * @return 服务名称，如果无法提取则返回null
     */
    private String extractServicePath(String path) {
        if (StringUtils.isEmpty(path)) {
            return null;
        }

        // 确保路径以/开头
        path = StringUtils.startWithAppend(path, "/");

        // 简单的路径解析：取第一个路径段作为服务名称
        // 例如：/user/profile -> /user
        String[] segments = path.split("/");
        if (segments.length >= 2 && StringUtils.isNotEmpty(segments[1])) {
            return "/" + segments[1];
        }

        return null;
    }

    /**
     * 获取请求参数，不存在时返回默认值
     *
     * @param request      请求对象
     * @param paramName    参数名称
     * @param defaultValue 默认值
     * @return 参数值或默认值
     */
    private String getParameterWithDefault(ServletRequest request, String paramName, String defaultValue) {
        String value = request.getParameter(paramName);
        return StringUtils.isNotEmpty(value) ? value : defaultValue;
    }

    /**
     * 启动服务发现
     */
    private void startServiceDiscovery() {
        try {
            if (serviceDiscovery != null) {
                serviceDiscovery.start();
                log.info("✅ 服务发现启动成功: {}", serviceDiscovery.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("服务发现启动失败", e);
        }
    }

    /**
     * 注册服务到服务发现中心
     *
     * @param path      服务路径
     * @param discovery 服务实例
     * @return 当前过滤器实例，支持链式调用
     */
    public ServiceDiscoveryServletFilter registerService(String path, Discovery discovery) {
        if (serviceDiscovery != null) {
            try {
                serviceDiscovery.registerService(path, discovery);
                log.info("服务注册成功: {} -> {}", path, discovery.toFullString());
            } catch (Exception e) {
                log.error("服务注册失败: {} -> {}", path, discovery, e);
            }
        } else {
            log.warn("ServiceDiscovery未初始化，无法注册服务");
        }
        return this;
    }

    /**
     * 注册当前服务实例
     *
     * @param path 服务路径
     * @param host 主机地址
     * @param port 端口
     * @return 当前过滤器实例，支持链式调用
     */
    public ServiceDiscoveryServletFilter registerCurrentService(String path, String host, int port) {
        Discovery discovery = Discovery.builder()
                .host(host)
                .port(port)
                .protocol(defaultProtocol)
                .weight(1.0)
                .build();
        return registerService(path, discovery);
    }

    // Getter和Setter方法
    @Override
    public String getFilterName() {
        return filterName;
    }

    public ServiceDiscoveryServletFilter setFilterName(String filterName) {
        this.filterName = filterName;
        return this;
    }

    @Override
    public int getOrder() {
        return priority;
    }

    public ServiceDiscoveryServletFilter setPriority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    public ServiceDiscoveryServletFilter setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        return this;
    }

    public ServiceDiscoveryServletFilter setDefaultBalance(String defaultBalance) {
        this.defaultBalance = defaultBalance;
        return this;
    }

    public ServiceDiscoveryServletFilter setDefaultProtocol(String defaultProtocol) {
        this.defaultProtocol = defaultProtocol;
        return this;
    }

    public ServiceDiscoveryServletFilter setProtocolType(String protocolType) {
        this.protocolType = protocolType != null ? protocolType.toLowerCase() : "http";
        this.filterName = "DiscoveryServletFilter-" + this.protocolType;
        return this;
    }

    public ServiceDiscoveryServletFilter setDefaultTimeout(int defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
        return this;
    }

    public ServiceDiscoveryServletFilter setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public ServiceDiscoveryServletFilter setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    public ServiceDiscoveryServletFilter setServiceDiscovery(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
        if (autoStart) {
            startServiceDiscovery();
        }
        return this;
    }

    public ServiceDiscoveryServletFilter setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
        return this;
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息字符串
     */
    public String getStatistics() {
        return String.format("DiscoveryServletFilter统计: 总请求=%d, 成功=%d, 失败=%d, 成功率=%.2f%%",
                requestCount.get(), successCount.get(), failureCount.get(),
                requestCount.get() > 0 ? (successCount.get() * 100.0 / requestCount.get()) : 0.0);
    }

    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        requestCount.set(0);
        successCount.set(0);
        failureCount.set(0);
        log.info("DiscoveryServletFilter统计信息已重置");
    }

    /**
     * 过滤器销毁方法
     * <p>
     * 在过滤器被移除或服务器关闭时调用，清理资源
     */
    @Override
    public void destroy() {
        try {
            if (serviceDiscovery != null) {
                serviceDiscovery.close();
                log.info("ServiceDiscovery关闭: {}", serviceDiscovery.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("关闭ServiceDiscovery失败", e);
        }

        // 重置统计信息
        resetStatistics();

        log.info("DiscoveryServletFilter已销毁");
    }

    /**
     * 获取过滤器描述
     *
     * @return 过滤器描述
     */
    @Override
    public String getDescription() {
        return String.format("DiscoveryServletFilter: 服务发现过滤器 (type=%s, balance=%s, protocol=%s)",
                serviceDiscovery != null ? serviceDiscovery.getClass().getSimpleName() : "null",
                defaultBalance, defaultProtocol);
    }

    /**
     * 检查是否匹配请求
     * <p>
     * 只处理能够提取服务路径的请求
     *
     * @param request 请求对象
     * @return 如果匹配返回true，否则返回false
     */
    @Override
    public boolean matches(ServletRequest request) {
        if (!enabled.get()) {
            return false;
        }

        String servicePath = extractServicePath(request.getPath());
        return StringUtils.isNotEmpty(servicePath);
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true;
    }
}