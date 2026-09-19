package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * SSRF 防护过滤器，阻止服务端请求伪造攻击。
 *
 * <p>检查请求头中的 {@code Referer} 和 {@code Origin} 是否指向内网地址，
 * 防止攻击者利用服务器发起对内网资源的请求。
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>{@code ssrf.allowedDomains} — 逗号分隔的允许域名白名单</li>
 *   <li>{@code ssrf.blockInternal} — 是否阻止内网地址，默认 true</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class SsrfServerFilter implements ServerFilter {

    /**
     * 允许的域名白名单
     */
    private final Set<String> allowedDomains = new HashSet<>();

    /**
     * 是否阻止内网地址
     */
    private boolean blockInternal = true;

    @Override
    /**
     * 初始化
    */
    public void init(ServerFilterConfig config) throws Exception {
        String domains = config.getInitParameter("ssrf.allowedDomains");
        if (domains != null) {
            for (String domain : domains.split(",")) {
                String trimmed = domain.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    allowedDomains.add(trimmed);
                }
            }
        }
        String block = config.getInitParameter("ssrf.blockInternal");
        if ("false".equalsIgnoreCase(block)) {
            this.blockInternal = false;
        }
    }

    @Override
    /**
     * 执行过滤
    */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty() && !isAllowedHost(referer)) {
            response.end(403, "{\"error\":\"Forbidden\",\"message\":\"非法的 Referer\"}");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty() && !isAllowedHost(origin)) {
            response.end(403, "{\"error\":\"Forbidden\",\"message\":\"非法的 Origin\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 获取订单
    */
    public int getOrder() {
        return 12;
    }

    @Override
    /**
     * 获取过滤标识
    */
    public String getFilterId() {
        return "SsrfServerFilter";
    }

    /**
     * 检查 URL 中的主机是否在白名单中且不是内网地址。
     * @param url url
     * @return 是否allowed主机的结果
     */
    private boolean isAllowedHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return true;
            }
            host = host.toLowerCase();
            if (blockInternal && isInternalAddress(host)) {
                return false;
            }
            if (!allowedDomains.isEmpty()) {
                for (String allowed : allowedDomains) {
                    if (host.equals(allowed) || host.endsWith("." + allowed)) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            // URL 解析失败，视为非法
            return false;
        }
    }

    /**
     * 判断是否为内网地址。
     * @param host 主机
     * @return 是否内部地址的结果
     */
    private boolean isInternalAddress(String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            // DNS 解析失败，保守处理，放行
            return false;
        }
    }
}
