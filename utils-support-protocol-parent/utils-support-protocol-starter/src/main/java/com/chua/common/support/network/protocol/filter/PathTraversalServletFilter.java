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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 路径穿越拦截过滤器
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("pathTraversal")
@SpiDescribe("路径穿越拦截过滤器")
public class PathTraversalServletFilter extends UpgradeServletFilter<PathTraversalServletFilter.PathTraversalConfig> {

    /**
     * 预解析后的根目录（提升性能；配置变更时刷新）
     */
    private volatile Path rootPath;

    /**
     * 预解析后的参数名集合（提升性能；配置变更时刷新）
     */
    private volatile Set<String> paramKeys;

    public PathTraversalServletFilter() {
        super("PathTraversalFilter");
        PathTraversalConfig cfg = new PathTraversalConfig(
                true,
                "/var/www/data",
                new LinkedHashSet<>(Arrays.asList("file", "path", "name"))
        );
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, PathTraversalConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        Path rp = this.rootPath;
        if (rp == null) {
            // 兜底：理论上应由 onConfigurationObjectChanged 初始化
            String root = Optional.ofNullable(config.rootDirectory()).orElse("/");
            rp = Paths.get(root).normalize().toAbsolutePath();
            this.rootPath = rp;
        }

        Set<String> keys = this.paramKeys;
        if (keys == null) {
            keys = Optional.ofNullable(config.paramKeys()).orElse(Collections.emptySet());
            this.paramKeys = keys;
        }

        for (String key : keys) {
            String val = request.getParameter(key);
            if (val == null || val.isEmpty()) {
                continue;
            }
            if (!validatePath(rp, val)) {
                reject(request, response, key, val);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean validatePath(Path root, String userPath) {
        try {
            Path resolved = root.resolve(userPath).normalize().toAbsolutePath();
            return resolved.startsWith(root);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void reject(ServletRequest request, ServletResponse response, String key, String value) {
        response.setStatusCode(400);
        response.setStatusMessage("Bad Request");
        response.setContentType("application/json");
        response.setBodyString(String.format(Locale.ROOT,
                "{\"error\":\"Path traversal blocked\",\"param\":\"%s\"}", key));
        response.addHeader("X-Blocked-Reason", "PATH_TRAVERSAL");
        response.setTerminateEarly(true);
    }

    @Override
    protected boolean validateConfigObject(PathTraversalConfig config) {
        return config != null && config.rootDirectory() != null;
    }

    @Override
    protected void onConfigurationObjectChanged(PathTraversalConfig oldConfig, PathTraversalConfig newConfig) {
        if (newConfig == null || newConfig.getRootDirectory() == null) {
            return;
        }
        this.rootPath = Paths.get(newConfig.getRootDirectory()).normalize().toAbsolutePath();
        this.paramKeys = Optional.ofNullable(newConfig.getParamKeys()).orElse(Collections.emptySet());
        if (log.isDebugEnabled()) {
            log.debug("[协议][安全] 路径穿越拦截配置已更新: root={}, keys={}", this.rootPath, this.paramKeys.size());
        }
    }

    @Override
    public String getFilterName() { return "PathTraversalServletFilter"; }

    @Override
    public int getOrder() { return 3; }

    @Override
    public String getDescription() {
        PathTraversalConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "路径穿越拦截 (v%d) enabled=%s root=%s", getConfigVersion(), c != null && c.isEnabled(), c != null ? c.getRootDirectory() : "");
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("根目录").key("rootDirectory").type(String.class).description("根目录，用户路径必须在其下").required(true).defaultValue("/var/www/data").build());
        options.add(FilterOption.builder().name("参数名").key("paramKeys").type(Set.class).description("包含路径的参数名集合").required(false).build());
        return options;
    }

    /**
     * 路径穿越配置
     */
    public record PathTraversalConfig(
            boolean enabled,
            String rootDirectory,
            Set<String> paramKeys
    ) {
        /**
         * 默认配置
         */
        public PathTraversalConfig() {
            this(true, null, null);
        }

        /**
         * 是否启用（兼容方法）
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 获取根目录（兼容方法）
         *
         * @return 根目录
         */
        public String getRootDirectory() {
            return rootDirectory;
        }

        /**
         * 获取参数键集合（兼容方法）
         *
         * @return 参数键集合
         */
        public Set<String> getParamKeys() {
            return paramKeys;
        }
    }
}


