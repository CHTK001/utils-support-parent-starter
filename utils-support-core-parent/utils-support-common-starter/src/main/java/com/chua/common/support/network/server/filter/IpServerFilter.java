// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 黑白名单过滤器（HTTP 走过滤器链，代理走 allow 判定）。
 *
 * <p>类名统一以 {@code ServerFilter} 结尾，与其它 ServerFilter 子类保持一致。</p>
 *
 * <p>支持两种模式：白名单（仅允许列表中的 IP）、黑名单（拒绝列表中的 IP）。
 * 参数经 {@link #init(ServerFilterConfig)} 注入：{@code mode}、{@code ips}。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Spi("ip")
@SpiDescribe(value = "IP 黑白名单过滤器",
        desc = "白名单只放行列表内 IP，黑名单拒绝列表内 IP；列表为空时不拦截",
        optional = {
                @SpiParam(value = "mode", defaultValue = "blacklist",
                        desc = "blacklist=拒绝列表内 IP，whitelist=只允许列表内 IP", type = "string"),
                @SpiParam(value = "ips", defaultValue = "",
                        desc = "逗号或空格分隔的 IP 列表，如 10.0.0.1,10.0.0.2", type = "string")
        })
public class IpServerFilter implements ServerFilter {

    /**
     * Whitelist
     */
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    /**
     * Blacklist
     */
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    /**
     * Whitelist模式
     */
    private boolean whitelistMode = false;

    /**
     * 创建黑名单模式过滤器。
     *
     * @return IP过滤 对象
     */
    public static IpServerFilter blacklist() {
        return new IpServerFilter(false);
    }

    /**
     * 创建白名单模式过滤器。
     *
     * @return IP过滤 对象
     */
    public static IpServerFilter whitelist() {
        return new IpServerFilter(true);
    }

    /**
     * 无参构造：SPI 反射装配与黑名单模式默认值都走这里。
     */
    public IpServerFilter() {
        this(false);
    }

    /**
     * 创建 IpFilter 实例
     *
     * @param whitelistMode whitelistMode
     */
    private IpServerFilter(boolean whitelistMode) {
        this.whitelistMode = whitelistMode;
    }

    /**
     * 按配置初始化：{@code mode} 决定黑白名单，{@code ips} 是逗号/空格分隔的列表。
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        String mode = config.getInitParameter("mode");
        if (mode != null && !mode.isBlank()) {
            whitelistMode = "whitelist".equalsIgnoreCase(mode.trim());
        }
        String ips = config.getInitParameter("ips");
        if (ips == null || ips.isBlank()) {
            return;
        }
        whitelist.clear();
        blacklist.clear();
        for (String raw : ips.split("[,;\\s]+")) {
            String ip = raw.trim();
            if (ip.isEmpty()) {
                continue;
            }
            if (whitelistMode) {
                whitelist.add(ip);
            } else {
                blacklist.add(ip);
            }
        }
    }

    /**
     * 添加Whitelist
     *
     * @param ip 字符串
     */
    public void addWhitelist(String ip) {
        whitelist.add(ip);
    }

    /**
     * 添加Blacklist
     *
     * @param ip 字符串
     */
    public void addBlacklist(String ip) {
        blacklist.add(ip);
    }

    /**
     * 判断某个客户端 IP 是否放行。
     *
     * @param clientIp 客户端 IP
     * @return true 表示放行
     */
    public boolean allows(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return !whitelistMode;
        }
        if (whitelistMode) {
            return whitelist.isEmpty() || whitelist.contains(clientIp);
        }
        return !blacklist.contains(clientIp);
    }

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 40;
    }

    /**
     * SupportProtocols
     *
     * <p>返回空数组表示「所有协议」：防火墙要在 http / tcp / 各类 proxy 上都生效。</p>
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * Do过滤
     *
     * @param request  request
     * @param response response
     * @param chain    chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String clientIp = extractIp(request.getRemoteAddress());
        if (!allows(clientIp)) {
            deny(response, clientIp);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Deny
     *
     * @param response 响应，不允许为 null
     * @param clientIp 客户端IP，不允许为 null
     */
    private void deny(ServerResponse response, String clientIp) {
        response.setStatus(403);
        response.setBody("{\"error\":\"Forbidden\",\"ip\":\"" + clientIp + "\"}");
        response.end();
    }

    /**
     * 从 remoteAddress 提取 IP（去除端口）。
     *
     * @param remoteAddress remote地址，不允许为 null
     * @return 结果字符串
     */
    static String extractIp(String remoteAddress) {
        if (remoteAddress == null) {
            return "";
        }
        int colon = remoteAddress.lastIndexOf(':');
        return colon > 0 ? remoteAddress.substring(0, colon) : remoteAddress;
    }
}
