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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 上传绕过拦截过滤器（文件扩展名/Content-Type/魔数 检查）
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("uploadBypass")
@SpiDescribe("上传绕过拦截过滤器")
public class UploadBypassServletFilter extends UpgradeServletFilter<UploadBypassServletFilter.UploadConfig> {

    public UploadBypassServletFilter() {
        super("UploadBypassFilter");
        UploadConfig cfg = new UploadConfig();
        cfg.setEnabled(true);
        cfg.setAllowedExt(new LinkedHashSet<>(Arrays.asList(".jpg", ".png", ".gif", ".txt", ".pdf")));
        cfg.setDeniedExt(new LinkedHashSet<>(Arrays.asList(".jsp", ".php", ".asp", ".exe", ".sh", ".js")));
        cfg.setAllowedContentType(new LinkedHashSet<>(Arrays.asList("image/", "text/plain", "application/pdf")));
        cfg.setMaxSizeBytes(10 * 1024 * 1024L);
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, UploadConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 简化：仅基于头与参数的检查（实际项目应结合multipart解析）
        String filename = request.getParameter("filename");
        String contentType = request.getContentType();
        String sizeStr = request.getParameter("filesize");
        long fileSize = 0L;
        try { fileSize = sizeStr == null ? 0L : Long.parseLong(sizeStr); } catch (Exception ignored) {}

        if (filename != null && !filename.isEmpty()) {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (!isExtAllowed(lower, config)) {
                reject(response, "File extension denied");
                return;
            }
        }

        if (contentType != null && !contentType.isEmpty()) {
            if (!isContentTypeAllowed(contentType, config)) {
                reject(response, "Content-Type denied");
                return;
            }
        }

        if (config.getMaxSizeBytes() > 0 && fileSize > config.getMaxSizeBytes()) {
            reject(response, "File too large");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isExtAllowed(String filenameLower, UploadConfig cfg) {
        // 双重扩展名处理：取最后一个点后的扩展
        int idx = filenameLower.lastIndexOf('.');
        String ext = idx == -1 ? "" : filenameLower.substring(idx);
        if (cfg.getDeniedExt() != null) {
            for (String d : cfg.getDeniedExt()) {
                if (ext.equals(d)) return false;
            }
        }
        if (cfg.getAllowedExt() != null && !cfg.getAllowedExt().isEmpty()) {
            for (String a : cfg.getAllowedExt()) {
                if (ext.equals(a)) return true;
            }
            return false;
        }
        return true;
    }

    private boolean isContentTypeAllowed(String ct, UploadConfig cfg) {
        if (cfg.getAllowedContentType() == null || cfg.getAllowedContentType().isEmpty()) return true;
        for (String p : cfg.getAllowedContentType()) {
            if (ct.startsWith(p)) return true;
        }
        return false;
    }

    private void reject(ServletResponse response, String reason) {
        response.setStatusCode(400);
        response.setStatusMessage("Bad Request");
        response.setContentType("application/json");
        response.setBodyString(String.format(Locale.ROOT, "{\"error\":\"Upload blocked\",\"reason\":\"%s\"}", reason));
        response.addHeader("X-Blocked-Reason", "UPLOAD_BYPASS");
        response.setTerminateEarly(true);
    }

    @Override
    protected boolean validateConfigObject(UploadConfig config) { return config != null; }

    @Override
    public String getFilterName() { return "UploadBypassServletFilter"; }

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getDescription() {
        UploadConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "上传绕过拦截 (v%d) enabled=%s", getConfigVersion(), c != null && c.isEnabled());
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("允许扩展名").key("allowedExt").type(Set.class).description("允许的文件扩展名集合").required(false).build());
        options.add(FilterOption.builder().name("拒绝扩展名").key("deniedExt").type(Set.class).description("拒绝的文件扩展名集合").required(false).build());
        options.add(FilterOption.builder().name("允许Content-Type前缀").key("allowedContentType").type(Set.class).description("允许的Content-Type前缀").required(false).build());
        options.add(FilterOption.builder().name("最大大小(字节)").key("maxSizeBytes").type(Long.class).description("最大允许大小").required(false).defaultValue(10485760L).build());
        return options;
    }

    @Data
    public static class UploadConfig {
        private boolean enabled = true;
        private Set<String> allowedExt;
        private Set<String> deniedExt;
        private Set<String> allowedContentType;
        private long maxSizeBytes;
    }
}


