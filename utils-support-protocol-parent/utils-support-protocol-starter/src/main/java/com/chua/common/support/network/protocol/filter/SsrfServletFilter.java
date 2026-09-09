package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * SSRF 拦截过滤器
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("ssrf")
@SpiDescribe("SSRF拦截过滤器")
public class SsrfServletFilter extends UpgradeServletFilter<SsrfServletFilter.SsrfConfig> {

    public SsrfServletFilter() {
        super("SsrfFilter");
        SsrfConfig cfg = new SsrfConfig();
        cfg.setEnabled(true);
        cfg.setParamKeys(new LinkedHashSet<>(Arrays.asList("url", "target", "callback", "redirect")));
        cfg.setAllowedSchemes(new LinkedHashSet<>(Arrays.asList("http", "https")));
        cfg.setAllowedPorts(new LinkedHashSet<>(Arrays.asList(80, 443)));
        cfg.setBlockInternalIp(true);
        cfg.setBlockIpv6LinkLocal(true);
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, SsrfConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 检查参数中的 URL
        for (String key : Optional.ofNullable(config.getParamKeys()).orElse(Collections.emptySet())) {
            String val = request.getParameter(key);
            if (val == null || val.isEmpty()) {
                continue;
            }
            if (!validateUrl(val, config)) {
                reject(request, response, key, val);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean validateUrl(String urlStr, SsrfConfig cfg) {
        try {
            URI uri = URI.create(urlStr);
            String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
            if (!cfg.getAllowedSchemes().contains(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            // 端口
            int port = uri.getPort();
            if (port == -1) {
                port = scheme.equals("https") ? 443 : 80;
            }
            if (cfg.getAllowedPorts() != null && !cfg.getAllowedPorts().isEmpty() && !cfg.getAllowedPorts().contains(port)) {
                return false;
            }

            // 域名白/黑名单
            if (cfg.getDenyDomains() != null) {
                for (String d : cfg.getDenyDomains()) {
                    if (host.endsWith(d)) {
                        return false;
                    }
                }
            }
            if (cfg.getAllowDomains() != null && !cfg.getAllowDomains().isEmpty()) {
                boolean ok = false;
                for (String d : cfg.getAllowDomains()) {
                    if (host.endsWith(d)) {
                        ok = true; break;
                    }
                }
                if (!ok) return false;
            }

            // DNS 解析并检查内网
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (cfg.isBlockInternalIp() && isPrivateAddress(a)) {
                    return false;
                }
                if (cfg.isBlockIpv6LinkLocal() && (a instanceof Inet6Address) && a.isLinkLocalAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean isPrivateAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress();
    }

    private void reject(ServletRequest request, ServletResponse response, String key, String value) {
        response.setStatusCode(400);
        response.setStatusMessage("Bad Request");
        response.setContentType("application/json");
        response.setBodyString(String.format(Locale.ROOT,
                "{\"error\":\"SSRF blocked\",\"param\":\"%s\",\"value\":\"%s\"}", key, value.replace("\"", "")));
        response.addHeader("X-Blocked-Reason", "SSRF");
        response.setTerminateEarly(true);
    }

    @Override
    protected boolean validateConfigObject(SsrfConfig config) {
        return config != null;
    }

    @Override
    public String getFilterName() {
        return "SsrfServletFilter";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public String getDescription() {
        SsrfConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "SSRF拦截 (v%d) enabled=%s", getConfigVersion(), c != null && c.isEnabled());
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("参数名").key("paramKeys").type(Set.class).description("包含URL的参数名集合").required(false).build());
        options.add(FilterOption.builder().name("允许协议").key("allowedSchemes").type(Set.class).description("允许的协议：http/https").required(false).build());
        options.add(FilterOption.builder().name("允许端口").key("allowedPorts").type(Set.class).description("允许的端口：80/443等").required(false).build());
        options.add(FilterOption.builder().name("白名单域").key("allowDomains").type(Set.class).description("域名白名单（后缀匹配）").required(false).build());
        options.add(FilterOption.builder().name("黑名单域").key("denyDomains").type(Set.class).description("域名黑名单（后缀匹配）").required(false).build());
        options.add(FilterOption.builder().name("拦截内网IP").key("blockInternalIp").type(Boolean.class).description("是否拦截解析到内网/回环等地址").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("拦截IPv6链路本地").key("blockIpv6LinkLocal").type(Boolean.class).description("是否拦截IPv6链路本地地址").required(false).defaultValue(true).build());
        return options;
    }

    @Data
    public static class SsrfConfig {
        private boolean enabled = true;
        private Set<String> paramKeys;
        private Set<String> allowedSchemes;
        private Set<Integer> allowedPorts;
        private Set<String> allowDomains;
        private Set<String> denyDomains;
        private boolean blockInternalIp = true;
        private boolean blockIpv6LinkLocal = true;
    }
}


