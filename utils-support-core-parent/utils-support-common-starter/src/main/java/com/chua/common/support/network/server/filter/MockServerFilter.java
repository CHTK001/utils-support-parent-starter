// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;

import java.util.LinkedHashMap;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * Mock 响应过滤器（联调打桩）。
 *
 * <p>按通配路径返回固定响应，命中后不再向后转发——前端未就绪、第三方接口未开、
 * 验收要固定数据时，给节点挂上它就能对外吐出确定内容。</p>
 *
 * <p>路径匹配：{@code /a/b} 精确、{@code /a/*} 单段通配、{@code /a/**} 任意深度；
 * 每行 {@code 路径 = 状态码|content-type|响应体}（响应体内置 {@code \n} 转义）。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
@Spi("mock")
@SpiDescribe(value = "Mock 响应过滤器",
        desc = "按通配路径返回固定响应（联调打桩）；命中后不再转发",
        optional = {
                @SpiParam(value = "rules", defaultValue = "",
                        desc = "每行一条：路径 = 状态码|content-type|响应体，如 /api/demo = 200|application/json|{\"ok\":true}",
                        type = "text"),
                @SpiParam(value = "defaultStatus", defaultValue = "404",
                        desc = "未命中时的兜底状态码", type = "number")
        })
public class MockServerFilter implements ServerFilter {

    /**
     * 规则表：路径模式 → 响应
     */
    private final Map<String, MockRule> rules = new LinkedHashMap<>();
    /**
     * 未命中兜底状态码
     */
    private int defaultStatus = 404;

    /**
     * 无参构造：SPI 反射装配用。
     */
    public MockServerFilter() {
    }

    /**
     * 按配置初始化规则。
     *
     * <p>参数缺失时保留当前值：服务器启动阶段会以空配置对已初始化的过滤器二次 init
     * （{@code AbstractServer.start()} → {@code initFilters(SimpleServerFilterConfig)}），
     * 条件赋值保证节点级参数（rules/defaultStatus）不被空配置抹除。</p>
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        applyParams(config.getInitParameter("rules"), config.getInitParameter("defaultStatus"));
    }

    /**
     * 热更新参数（不重建过滤器实例、不重启服务器）。
     *
     * @param config 新的参数键值
     */
    @Override
    public void updateConfig(Map<String, Object> config) {
        if (CollectionUtils.isEmpty(config)) {
            return;
        }
        applyParams(toStringValue(config.get("rules")), toStringValue(config.get("defaultStatus")));
    }

    /**
     * 提取参数的字符串值，null 保持为 null（与「参数缺失」同义）。
     *
     * @param value 原始值
     * @return 字符串值
     */
    private static String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 应用参数：{@code rules} 传 null 表示保持已有规则（空配置二次 init 不清空），
     * 传空串表示清空规则表；{@code defaultStatus} 非空才覆盖。
     *
     * @param rulesText     规则文本，可为 null
     * @param defaultStatus 兜底状态码，可为 null
     */
    private void applyParams(String rulesText, String defaultStatus) {
        if (StringUtils.isNotBlank(defaultStatus)) {
            this.defaultStatus = (int) parseLong(defaultStatus, 404);
        }
        if (rulesText == null) {
            return;
        }
        Map<String, MockRule> parsed = new LinkedHashMap<>();
        for (String line : rulesText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int at = trimmed.indexOf('=');
            if (at <= 0) {
                continue;
            }
            String pattern = trimmed.substring(0, at).trim();
            String spec = trimmed.substring(at + 1).trim();
            MockRule rule = MockRule.parse(spec);
            if (rule != null && !pattern.isEmpty()) {
                parsed.put(pattern, rule);
            }
        }
        rules.clear();
        rules.putAll(parsed);
    }

    /**
     * 请求未命中任何规则时不再走链，直接返回兜底状态。
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        for (Map.Entry<String, MockRule> entry : rules.entrySet()) {
            if (match(entry.getKey(), path)) {
                entry.getValue().writeTo(response);
                return;
            }
        }
        response.setStatus(defaultStatus);
        response.end();
    }

    /**
     * 通配匹配：{@code *} 单段、{@code **} 任意深度。
     *
     * @param pattern 模式
     * @param path    实际路径
     * @return 是否命中
     */
    static boolean match(String pattern, String path) {
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1));
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return path.startsWith(prefix) && !path.substring(prefix.length()).contains("/");
        }
        return false;
    }

    /**
     * 解析数字参数。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private static long parseLong(String value, long defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 10;
    }

    /**
     * SupportProtocols：空数组表示所有协议。
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * 一条 mock 规则。
     */
    private static final class MockRule {
        /**
         * HTTP 状态码
         */
        private final int status;
        /**
         * Content-Type
         */
        private final String contentType;
        /**
         * 响应体
         */
        private final String body;

        /**
         * 创建规则。
         *
         * @param status      状态码
         * @param contentType 内容类型
         * @param body        响应体
         */
        private MockRule(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        /**
         * 解析「状态码|content-type|响应体」片段；三段都可省略（缺省 200/text/plain/空体）。
         *
         * @param spec 原始片段
         * @return 规则；格式非法返回 null
         */
        static MockRule parse(String spec) {
            String[] parts = spec.split("\\|", 3);
            int status = 200;
            String type = "text/plain";
            String body = "";
            try {
                if (parts.length > 0 && !parts[0].isBlank()) {
                    status = Integer.parseInt(parts[0].trim());
                }
            } catch (NumberFormatException e) {
                return null;
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                type = parts[1].trim();
            }
            if (parts.length > 2) {
                body = parts[2].replace("\\n", "\n");
            }
            return new MockRule(status, type, body);
        }

        /**
         * 把规则写到响应上。
         *
         * @param response 响应
         */
        void writeTo(ServerResponse response) {
            response.setStatus(status);
            if (StringUtils.isNotBlank(contentType)) {
                response.setContentType(contentType);
            }
            response.setBody(body);
            response.end();
        }
    }
}