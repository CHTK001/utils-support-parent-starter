package com.chua.spider.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderUrlFilter;
import com.chua.spider.support.model.SpiderRequest;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 域名白名单 URL 过滤器。
 *
 * <p>只允许爬取指定域名内的 URL，过滤掉外部链接。
 * 用于控制爬虫的范围，避免爬到目标站点之外的页面。
 *
 * <p>SPI 名称：{@code filter:domain}
 *
 * <p>使用示例：
 * <pre>{@code
 * // 只爬取 example.com 及其子域名
 * DomainUrlFilter filter = new DomainUrlFilter("example.com");
 * filter.accept(request); // true 如果 URL 在 example.com 域名下
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("domain")
public class DomainUrlFilter implements SpiderUrlFilter {

    /**
     * 允许的域名集合
     */
    private final Set<String> allowedDomains = new HashSet<>();

    /**
     * 默认构造器，不限制域名（接受所有 URL）。
     */
    public DomainUrlFilter() {
    }

    /**
     * 构造器，指定允许爬取的域名。
     *
     * @param domains 允许的域名列表，如 "example.com"
     */
    public DomainUrlFilter(String... domains) {
        if (domains != null) {
            this.allowedDomains.addAll(Arrays.asList(domains));
        }
    }

    /**
     * 添加允许的域名。
     *
     * @param domain 域名，如 "example.com"
     * @return 当前过滤器实例
     */
    public DomainUrlFilter addDomain(String domain) {
        if (domain != null) {
            this.allowedDomains.add(domain.toLowerCase());
        }
        return this;
    }

    @Override
    public boolean accept(SpiderRequest request) {
        if (request == null || request.getUrl() == null) {
            return false;
        }

        // 未配置域名白名单时接受所有 URL
        if (allowedDomains.isEmpty()) {
            return true;
        }

        try {
            String host = URI.create(request.getUrl()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();

            // 检查域名是否在白名单中（精确匹配或子域名匹配）
            for (String domain : allowedDomains) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // URL 格式非法，拒绝
            return false;
        }

        return false;
    }
}
