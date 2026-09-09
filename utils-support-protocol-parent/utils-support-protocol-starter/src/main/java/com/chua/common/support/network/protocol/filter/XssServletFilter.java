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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * XSS/脚本注入基础拦截过滤器
 *
 * 仅做通用、高性价比的规则过滤，复杂场景仍推荐在业务层做更细粒度的校验与转义。
 *
 * @author CH
 * @since 2026-01-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("xss")
@SpiDescribe("脚本注入拦截过滤器")
public class XssServletFilter extends UpgradeServletFilter<XssServletFilter.XssConfig> {

    /**
     * 预编译的危险模式列表
     */
    private volatile List<Pattern> compiledPatterns;

    public XssServletFilter() {
        super("XssFilter");
        XssConfig cfg = new XssConfig(
                true,
                true,
                true,
                false,
                4096,
                Collections.emptySet(),
                Collections.emptySet(),
                defaultPatternStrings()
        );
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
                                            ServletFilterChain chain, XssConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        List<Pattern> patterns = this.compiledPatterns;
        if (patterns == null || patterns.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // 参数扫描
        if (config.scanParameters()) {
            if (scanParameters(request, patterns, config)) {
                reject(response, "PARAM");
                return;
            }
        }

        // 头部扫描
        if (config.scanHeaders()) {
            if (scanHeaders(request, patterns)) {
                reject(response, "HEADER");
                return;
            }
        }

        // Body 扫描（仅对小体积文本请求打开，避免严重性能开销）
        if (config.scanBody() && request.getBody() != null) {
            byte[] body = request.getBody();
            if (body.length <= config.bodyMaxLength()) {
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                if (matchesAny(bodyStr, patterns)) {
                    reject(response, "BODY");
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean validateConfigObject(XssConfig config) {
        if (config == null) {
            return false;
        }
        if (config.bodyMaxLength() <= 0) {
            return false;
        }
        return true;
    }

    @Override
    protected void onConfigurationObjectChanged(XssConfig oldConfig, XssConfig newConfig) {
        List<String> defs = newConfig != null ? newConfig.getDangerousPatterns() : defaultPatternStrings();
        List<Pattern> list = new ArrayList<>();
        for (String s : defs) {
            try {
                list.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("[协议][安全] 无法编译 XSS 正则: {}", s, e);
                }
            }
        }
        this.compiledPatterns = list;
        if (log.isDebugEnabled()) {
            log.debug("[协议][安全] XSS 拦截配置已更新, pattern 数量={}", list.size());
        }
    }

    @Override
    public String getFilterName() {
        return "XssServletFilter";
    }

    @Override
    public int getOrder() {
        // 放在认证和业务映射之前执行
        return 4;
    }

    @Override
    public String getDescription() {
        XssConfig c = getConfigurationObject();
        return String.format(Locale.ROOT,
                "XSS拦截 (v%d) enabled=%s",
                getConfigVersion(),
                c != null && c.isEnabled());
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class)
                .description("是否启用 XSS 拦截").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("扫描参数").key("scanParameters").type(Boolean.class)
                .description("是否扫描请求参数").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("扫描头部").key("scanHeaders").type(Boolean.class)
                .description("是否扫描请求头").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("扫描Body").key("scanBody").type(Boolean.class)
                .description("是否扫描请求体（仅小体积文本）").required(true).defaultValue(false).build());
        options.add(FilterOption.builder().name("Body最大长度").key("bodyMaxLength").type(Integer.class)
                .description("扫描Body时允许的最大长度（字节）").required(true).defaultValue(4096).build());
        options.add(FilterOption.builder().name("包含参数名").key("includeParams").type(Set.class)
                .description("仅扫描的参数名集合，空表示全部").required(false).build());
        options.add(FilterOption.builder().name("排除参数名").key("excludeParams").type(Set.class)
                .description("不扫描的参数名集合").required(false).build());
        options.add(FilterOption.builder().name("危险正则").key("dangerousPatterns").type(List.class)
                .description("自定义危险脚本正则列表").required(false).build());
        return options;
    }

    private boolean scanParameters(ServletRequest request, List<Pattern> patterns, XssConfig cfg) {
        Map<String, String[]> pm = request.getParameterMap();
        if (pm == null || pm.isEmpty()) {
            return false;
        }
        Set<String> include = cfg.includeParams();
        Set<String> exclude = cfg.excludeParams();
        for (Map.Entry<String, String[]> e : pm.entrySet()) {
            String name = e.getKey();
            if (exclude != null && exclude.contains(name)) {
                continue;
            }
            if (include != null && !include.isEmpty() && !include.contains(name)) {
                continue;
            }
            String[] values = e.getValue();
            if (values == null) {
                continue;
            }
            for (String v : values) {
                if (v != null && matchesAny(v, patterns)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[协议][安全] 参数命中 XSS 规则: {}={}", name, v);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean scanHeaders(ServletRequest request, List<Pattern> patterns) {
        if (request.getHeaders() == null || request.getHeaders().isEmpty()) {
            return false;
        }
        for (String h : request.getHeaders().keySet()) {
            List<String> values = request.getHeaders().getAll(h);
            if (values == null) {
                continue;
            }
            for (String v : values) {
                if (v != null && matchesAny(v, patterns)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[协议][安全] 头部命中 XSS 规则: {}={}", h, v);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesAny(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private void reject(ServletResponse response, String where) {
        response.setStatusCode(400);
        response.setStatusMessage("Bad Request");
        response.setContentType("application/json");
        response.setBodyString("{\"error\":\"Request blocked by XSS filter\",\"where\":\"" + where + "\"}");
        response.addHeader("X-Blocked-Reason", "XSS");
        response.setTerminateEarly(true);
    }

    private List<String> defaultPatternStrings() {
        List<String> list = new ArrayList<>();
        // 基本 <script> 标签
        list.add("(?i)<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>");
        // 行内事件处理 onXXX=
        list.add("(?i)on[a-zA-Z]+\\s*=");
        // javascript:/vbscript:/data: 协议
        list.add("(?i)(javascript:|vbscript:|data:text/html)");
        // iframe/object 等常见注入标签
        list.add("(?i)<\\s*(iframe|object|embed|link|style)[^>]*>");
        // 简单的表达式注入，如 ${...}、#{...}
        list.add("\\$\\{[^}]+}"); 
        list.add("#\\{[^}]+}");
        return list;
    }

    /**
     * XSS配置
     */
    public record XssConfig(
            /**
             * 是否启用
             */
            boolean enabled,
            /**
             * 是否扫描参数
             */
            boolean scanParameters,
            /**
             * 是否扫描头部
             */
            boolean scanHeaders,
            /**
             * 是否扫描Body
             */
            boolean scanBody,
            /**
             * Body 最大扫描长度（字节）
             */
            int bodyMaxLength,
            /**
             * 仅扫描的参数名集合（空表示全部）
             */
            Set<String> includeParams,
            /**
             * 排除扫描的参数名集合
             */
            Set<String> excludeParams,
            /**
             * 危险模式正则字符串集合
             */
            List<String> dangerousPatterns
    ) {
        /**
         * 默认配置
         */
        public XssConfig() {
            this(true, true, true, false, 4096, Collections.emptySet(), Collections.emptySet(), new ArrayList<>());
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
         * 获取危险模式正则字符串集合（兼容方法）
         *
         * @return 危险模式正则字符串集合
         */
        public List<String> getDangerousPatterns() {
            return dangerousPatterns;
        }
    }
}


