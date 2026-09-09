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

import java.util.*;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 爬虫拦截过滤器
 * <p>依据 User-Agent、Referer、路径后缀等规则拦截常见爬虫或可疑请求。</p>
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("crawlerDetect")
@SpiDescribe("爬虫拦截过滤器")
public class CrawlerDetectServletFilter extends UpgradeServletFilter<CrawlerDetectServletFilter.CrawlerConfig> {

    private volatile List<Pattern> allowAgentPatterns = new ArrayList<>();
    private volatile List<Pattern> denyAgentPatterns = new ArrayList<>();
    private volatile List<Pattern> allowPathPatterns = new ArrayList<>();
    private volatile List<Pattern> denyPathPatterns = new ArrayList<>();

    public CrawlerDetectServletFilter() {
        super("CrawlerDetectFilter");

        CrawlerConfig cfg = new CrawlerConfig();
        cfg.setEnabled(true);
        cfg.setBlockEmptyAgent(true);
        cfg.setBlockUnknownAgents(false);
        cfg.setDenyAgents(new LinkedHashSet<>(Arrays.asList(
                "(?i)python-requests", "(?i)curl/", "(?i)wget/", "(?i)httpclient",
                "(?i)spider", "(?i)crawler", "(?i)bot", "(?i)scrapy", "(?i)go-http-client"
        )));
        cfg.setDenyPaths(new LinkedHashSet<>(Arrays.asList(
                ".*\\.(db|bak|zip|rar|7z)$", ".*/wp-admin/.*", ".*/admin/.*", ".*/\n.*" // 粗略示例
        )));
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, CrawlerConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String userAgent = safeLower(request.getUserAgent());
        String path = Optional.ofNullable(request.getPath()).orElse("");
        String referer = safeLower(request.getReferer());

        // 1) 空UA拦截
        if (config.isBlockEmptyAgent() && (userAgent == null || userAgent.isEmpty())) {
            rejectAsCrawler(request, response, "Empty User-Agent");
            return;
        }

        // 2) 允许/拒绝 UA 规则
        if (!allowAgentPatterns.isEmpty() && !matchesAny(userAgent, allowAgentPatterns)) {
            if (config.isBlockUnknownAgents()) {
                rejectAsCrawler(request, response, "Unknown User-Agent");
                return;
            }
        }
        if (!denyAgentPatterns.isEmpty() && matchesAny(userAgent, denyAgentPatterns)) {
            rejectAsCrawler(request, response, "Denied User-Agent");
            return;
        }

        // 3) 路径规则
        if (!allowPathPatterns.isEmpty() && !matchesAny(path, allowPathPatterns)) {
            rejectAsCrawler(request, response, "Path not allowed");
            return;
        }
        if (!denyPathPatterns.isEmpty() && matchesAny(path, denyPathPatterns)) {
            rejectAsCrawler(request, response, "Path denied");
            return;
        }

        // 4) 简单启发：Referer缺失但路径疑似敏感
        if ((referer == null || referer.isEmpty()) && looksSensitive(path)) {
            rejectAsCrawler(request, response, "Sensitive path without Referer");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean looksSensitive(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return p.contains("admin") || p.contains("wp-admin") || p.endsWith(".db") || p.contains("/api/");
    }

    private boolean matchesAny(String value, List<Pattern> patterns) {
        if (value == null) {
            value = "";
        }
        for (Pattern p : patterns) {
            if (p.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private String safeLower(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private void rejectAsCrawler(ServletRequest request, ServletResponse response, String reason) {
        response.setStatusCode(403);
        response.setStatusMessage("Forbidden");
        response.setContentType("application/json");

        String json = String.format(Locale.ROOT,
                "{\"error\":\"Blocked as crawler\",\"reason\":\"%s\",\"path\":\"%s\"}",
                reason, Optional.ofNullable(request.getPath()).orElse(""));
        response.setBodyString(json);
        response.addHeader("X-Blocked-Reason", "CRAWLER_DETECTED");
        response.setTerminateEarly(true);

        try {
            com.chua.common.support.network.protocol.event.ServletEventDispatcher.publishAsync(
                    com.chua.common.support.network.protocol.event.ServletEvent.builder()
                            .clientIp(request.findClientIp())
                            .requestId(request.getRequestId())
                            .path(request.getPath())
                            .method(request.getMethod())
                            .duration(0L)
                            .status(com.chua.common.support.network.protocol.event.ServletRequestStatus.REJECTED)
                            .statusCode(403)
                            .terminated(true)
                            .build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onConfigurationObjectChanged(CrawlerConfig oldConfig, CrawlerConfig newConfig) {
        allowAgentPatterns = compilePatterns(newConfig.getAllowAgents());
        denyAgentPatterns = compilePatterns(newConfig.getDenyAgents());
        allowPathPatterns = compilePatterns(newConfig.getAllowPaths());
        denyPathPatterns = compilePatterns(newConfig.getDenyPaths());
        log.info("CrawlerDetect 配置已更新，allowAgents={}, denyAgents={}, allowPaths={}, denyPaths={}",
                allowAgentPatterns.size(), denyAgentPatterns.size(), allowPathPatterns.size(), denyPathPatterns.size());
    }

    private List<Pattern> compilePatterns(Set<String> src) {
        List<Pattern> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (String s : src) {
            try {
                out.add(Pattern.compile(s));
            } catch (Exception e) {
                log.warn("无效的正则表达式: {}", s, e);
            }
        }
        return out;
    }

    @Override
    protected boolean validateConfigObject(CrawlerConfig config) {
        return config != null;
    }

    @Override
    public String getFilterName() {
        return "CrawlerDetectServletFilter";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public String getDescription() {
        CrawlerConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "Crawler拦截 (v%d) enabled=%s", getConfigVersion(), c != null && c.isEnabled());
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用")
                .required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("拦截空UA").key("blockEmptyAgent").type(Boolean.class)
                .description("User-Agent 为空时是否拦截").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("拦截未知UA").key("blockUnknownAgents").type(Boolean.class)
                .description("未命中允许UA时是否拦截").required(false).defaultValue(false).build());
        options.add(FilterOption.builder().name("允许UA(正则)").key("allowAgents").type(Set.class)
                .description("白名单UA正则集合").required(false).build());
        options.add(FilterOption.builder().name("拒绝UA(正则)").key("denyAgents").type(Set.class)
                .description("黑名单UA正则集合").required(false).build());
        options.add(FilterOption.builder().name("允许路径(正则)").key("allowPaths").type(Set.class)
                .description("白名单路径正则集合").required(false).build());
        options.add(FilterOption.builder().name("拒绝路径(正则)").key("denyPaths").type(Set.class)
                .description("黑名单路径正则集合").required(false).build());
        return options;
    }

    @Data
    public static class CrawlerConfig {
        private boolean enabled = true;
        private boolean blockEmptyAgent = true;
        private boolean blockUnknownAgents = false;
        private Set<String> allowAgents;
        private Set<String> denyAgents;
        private Set<String> allowPaths;
        private Set<String> denyPaths;
    }
}


