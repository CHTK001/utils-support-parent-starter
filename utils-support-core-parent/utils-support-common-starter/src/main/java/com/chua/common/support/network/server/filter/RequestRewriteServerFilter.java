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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 请求改写过滤器（网关类节点常用）。
 *
 * <p>三种改写能力：</p>
 * <ul>
 *   <li>路径前缀：{@code stripPrefix=1} 去掉最前 N 段；{@code addPrefix=/api} 追加前缀。</li>
 *   <li>请求头：{@code setHeader.X=值} 设置/覆盖；{@code removeHeader.X} 删除。</li>
 *   <li>路径变量捕获：{@code pathVar.=pattern} 注册正则，命中片段写入请求属性，可被
 *       下游 {@code ServerHandler} 读取。</li>
 * </ul>
 *
 * <p>参数里除 {@code stripPrefix / addPrefix} 外，以 {@code setHeader.} / {@code removeHeader.} /
 * {@code pathVar.} 前缀的键会被分别解析。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
@Spi("request-rewrite")
@SpiDescribe(value = "请求改写过滤器",
        desc = "路径前缀裁剪/追加、请求头设置与删除、路径变量捕获；网关类节点常用",
        optional = {
                @SpiParam(value = "stripPrefix", defaultValue = "0",
                        desc = "去掉路径最前 N 段（如 /a/b/c 去 1 段后为 /b/c）", type = "number"),
                @SpiParam(value = "addPrefix", defaultValue = "",
                        desc = "在路径前追加前缀（如 /api）", type = "string")
        })
public class RequestRewriteServerFilter implements ServerFilter {

    /**
     * 去掉的路径段数
     */
    private int stripPrefix = 0;
    /**
     * 追加的前缀
     */
    private String addPrefix = "";
    /**
     * 设置的请求头：名 → 值
     */
    private final Map<String, String> setHeaders = new LinkedHashMap<>();
    /**
     * 删除的请求头
     */
    private final java.util.Set<String> removeHeaders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 路径变量：属性名 → 正则
     */
    private final Map<String, Pattern> pathVars = new LinkedHashMap<>();
    /**
     * setHeader 请求属性键前缀
     */
    private static final String SET_HEADER_PREFIX = "_setHeader:";
    /**
     * removeHeader 请求属性键前缀
     */
    private static final String REMOVE_HEADER_PREFIX = "_removeHeader:";
    /**
     * setHeader 属性键缓存：header 名 → 预拼好的请求属性键（init 时重建）
     */
    private final Map<String, String> setHeaderKeys = new LinkedHashMap<>();
    /**
     * removeHeader 属性键缓存：header 名 → 预拼好的请求属性键（init 时重建）
     */
    private final Map<String, String> removeHeaderKeys = new java.util.HashMap<>();

    /**
     * 无参构造：SPI 反射装配用。
     */
    public RequestRewriteServerFilter() {
    }

    /**
     * 按配置初始化。
     *
     * <p>参数缺失时保留当前值：服务器启动阶段会以空配置对已初始化的过滤器二次 init
     * （{@code AbstractServer.start()} → {@code initFilters(SimpleServerFilterConfig)}），
     * 条件赋值保证节点级参数（stripPrefix/addPrefix/头改写规则）不被空配置抹除。</p>
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        String strip = config.getInitParameter("stripPrefix");
        if (StringUtils.isNotBlank(strip)) {
            stripPrefix = (int) parseLong(strip, stripPrefix);
        }
        String prefix = config.getInitParameter("addPrefix");
        if (prefix != null) {
            addPrefix = normalizeAddPrefix(prefix);
        }
        Map<String, String> params = config.getInitParameters();
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        setHeaders.clear();
        removeHeaders.clear();
        pathVars.clear();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.startsWith("setHeader.") && key.length() > "setHeader.".length()) {
                setHeaders.put(key.substring("setHeader.".length()), value);
            } else if (key.startsWith("removeHeader.") && key.length() > "removeHeader.".length()) {
                removeHeaders.add(key.substring("removeHeader.".length()));
            } else if (key.startsWith("pathVar.") && key.length() > "pathVar.".length() && StringUtils.isNotBlank(value)) {
                try {
                    pathVars.put(key.substring("pathVar.".length()), Pattern.compile(value));
                } catch (Exception ignored) {
                    // 非法正则忽略，避免节点因此起不来
                }
            }
        }
        // 预生成请求属性键，避免每请求字符串拼接
        setHeaderKeys.clear();
        for (String name : setHeaders.keySet()) {
            setHeaderKeys.put(name, SET_HEADER_PREFIX + name);
        }
        removeHeaderKeys.clear();
        for (String name : removeHeaders) {
            removeHeaderKeys.put(name, REMOVE_HEADER_PREFIX + name);
        }
    }

    /**
     * 规整追加前缀：非「/」开头补「/」，去掉末尾「/」。
     *
     * @param raw 原始前缀
     * @return 规整后的前缀
     */
    private static String normalizeAddPrefix(String raw) {
        String prefix = raw.trim();
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * Do过滤：改写请求属性（首部/路径在实现层可写时不直接改，这里以属性方式透传意图）。
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        String rewritten = rewritePath(path);
        request.setAttribute("_rewrittenPath", rewritten);
        for (Map.Entry<String, String> header : setHeaders.entrySet()) {
            request.setAttribute(setHeaderKeys.get(header.getKey()), header.getValue());
        }
        for (String name : removeHeaders) {
            request.setAttribute(removeHeaderKeys.get(name), Boolean.TRUE);
        }
        for (Map.Entry<String, Pattern> var : pathVars.entrySet()) {
            Matcher matcher = var.getValue().matcher(path);
            if (matcher.find()) {
                // 正则有捕获组取第 1 组，否则取整段命中（pathVar.id=\d+ 这类写法没有分组）
                request.setAttribute(var.getKey(), matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 计算改写后的路径。
     *
     * @param path 原始路径
     * @return 改写后的路径（含前缀）
     */
    String rewritePath(String path) {
        String result = path;
        if (stripPrefix > 0) {
            // 去掉开头的 "/" 再切段：否则 "/a/b/c" 会多出一个空段，
            // 让 stripPrefix=1 少去一段（/a/b/c 去 1 段应得 /b/c）。
            // 索引扫描等价于 body.split("/", -1) 后跳过前 stripPrefix 段与空段。
            String body = result.startsWith("/") ? result.substring(1) : result;
            StringBuilder sb = new StringBuilder(body.length());
            int len = body.length();
            int i = 0;
            int segment = 0;
            // 先跳过前 stripPrefix 段
            while (i <= len && segment < stripPrefix) {
                int end = body.indexOf('/', i);
                if (end < 0) {
                    end = len;
                }
                segment++;
                i = end + 1;
            }
            // 拼接剩余段，跳过空段
            while (i <= len) {
                int end = body.indexOf('/', i);
                if (end < 0) {
                    end = len;
                }
                if (end > i) {
                    sb.append('/').append(body, i, end);
                }
                i = end + 1;
            }
            result = sb.length() == 0 ? "/" : sb.toString();
        }
        if (!addPrefix.isEmpty() && !"/".equals(result)) {
            result = addPrefix + result;
        }
        return result;
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
        return Integer.MIN_VALUE + 5;
    }

    /**
     * SupportProtocols：空数组表示所有协议。
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }
}