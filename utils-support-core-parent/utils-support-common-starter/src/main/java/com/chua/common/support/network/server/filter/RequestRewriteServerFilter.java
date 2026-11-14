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
     * 无参构造：SPI 反射装配用。
     */
    public RequestRewriteServerFilter() {
    }

    /**
     * 按配置初始化。
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        stripPrefix = (int) parseLong(config.getInitParameter("stripPrefix"), 0);
        addPrefix = config.getInitParameter("addPrefix");
        if (addPrefix == null) {
            addPrefix = "";
        } else {
            addPrefix = addPrefix.trim();
            if (!addPrefix.isEmpty() && !addPrefix.startsWith("/")) {
                addPrefix = "/" + addPrefix;
            }
            if (addPrefix.endsWith("/")) {
                addPrefix = addPrefix.substring(0, addPrefix.length() - 1);
            }
        }
        setHeaders.clear();
        removeHeaders.clear();
        pathVars.clear();
        for (Map.Entry<String, String> entry : config.getInitParameters().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.startsWith("setHeader.") && key.length() > "setHeader.".length()) {
                setHeaders.put(key.substring("setHeader.".length()), value);
            } else if (key.startsWith("removeHeader.") && key.length() > "removeHeader.".length()) {
                removeHeaders.add(key.substring("removeHeader.".length()));
            } else if (key.startsWith("pathVar.") && key.length() > "pathVar.".length() && value != null && !value.isBlank()) {
                try {
                    pathVars.put(key.substring("pathVar.".length()), Pattern.compile(value));
                } catch (Exception ignored) {
                    // 非法正则忽略，避免节点因此起不来
                }
            }
        }
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
            request.setAttribute("_setHeader:" + header.getKey(), header.getValue());
        }
        for (String name : removeHeaders) {
            request.setAttribute("_removeHeader:" + name, Boolean.TRUE);
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
            // 去掉开头的 "/" 再切分：否则 "/a/b/c".split() 会多出一个空段，
            // 让 stripPrefix=1 少去一段（/a/b/c 去 1 段应得 /b/c）
            String body = result.startsWith("/") ? result.substring(1) : result;
            String[] segments = body.split("/", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = stripPrefix; i < segments.length; i++) {
                if (segments[i].isEmpty()) {
                    continue;
                }
                sb.append('/').append(segments[i]);
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
        if (value == null || value.isBlank()) {
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