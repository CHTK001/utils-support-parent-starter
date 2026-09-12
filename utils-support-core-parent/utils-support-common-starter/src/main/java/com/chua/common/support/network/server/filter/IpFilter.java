package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
* IP 黑白名单过滤器。
*
* <p>支持两种模式：</p>
* <ul>
*   <li>白名单模式 — 仅允许列表中的 IP 访问</li>
*   <li>黑名单模式 — 拒绝列表中的 IP 访问</li>
* </ul>
*
* @author CH
* @since 2026/07/18
 */
public class IpFilter implements ServerFilter {

    /** Whitelist */
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    /** Blacklist */
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    /** Whitelist模式 */
    private boolean whitelistMode = false;

    /**
    * 创建黑名单模式过滤器。
     */
    public static IpFilter blacklist() {
        return new IpFilter(false);
    }

    /**
    * 创建白名单模式过滤器。
     */
    public static IpFilter whitelist() {
        return new IpFilter(true);
    }

    /**
    * 创建 IpFilter 实例
    * @param whitelistMode whitelistMode
     */
    private IpFilter(boolean whitelistMode) {
        this.whitelistMode = whitelistMode;
    }

    /** 添加Whitelist */
    public void addWhitelist(String ip) { whitelist.add(ip); }
    /** 添加Blacklist */
    public void addBlacklist(String ip) { blacklist.add(ip); }
    /** 移除Whitelist */
    public void removeWhitelist(String ip) { whitelist.remove(ip); }
    /** 移除Blacklist */
    public void removeBlacklist(String ip) { blacklist.remove(ip); }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 40;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /**
    * Do过滤
    * @param request request
    * @param response response
    * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String clientIp = extractIp(request.getRemoteAddress());

        if (whitelistMode) {
            if (!whitelist.isEmpty() && !whitelist.contains(clientIp)) {
                deny(response, clientIp);
                return;
            }
        } else {
            if (blacklist.contains(clientIp)) {
                deny(response, clientIp);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /** Deny */
    private void deny(ServerResponse response, String clientIp) {
        response.setStatus(403);
        response.setBody("{\"error\":\"Forbidden\",\"ip\":\"" + clientIp + "\"}");
        response.end();
    }

    /**
    * 从 remoteAddress 提取 IP（去除端口）。
     */
    private String extractIp(String remoteAddress) {
        if (remoteAddress == null) {
            return "";
        }
        int colon = remoteAddress.lastIndexOf(':');
        return colon > 0 ? remoteAddress.substring(0, colon) : remoteAddress;
    }
}
