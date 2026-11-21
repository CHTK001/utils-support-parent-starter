// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;

/**
 * 真实 IP 解析过滤器：从反向代理附加的请求头解析客户端真实 IP。
 *
 * <p>解析顺序：{@code X-Forwarded-For}（取第一个有效条目）→ {@code X-Real-IP}。
 * 解析结果写入请求属性 {@link #ATTR_REAL_IP}，供下游过滤器 / 处理器读取；
 * 未命中代理头时不写属性，保持原始 remoteAddress 语义。</p>
 *
 * <p>典型用法：部署在 Nginx / SLB 之后的服务器启用本过滤器，
 * 下游用 {@link #getRealIp(ServerRequest)} 取客户端真实 IP 做审计或限流。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi("real-ip")
@SpiDescribe(value = "真实 IP 解析过滤器",
        desc = "从 X-Forwarded-For / X-Real-IP 头解析客户端真实 IP，写入请求属性 _realIp；未命中代理头时不改写")
public class RealIpServerFilter implements ServerFilter {

    /**
     * 请求属性名：解析出的客户端真实 IP
     */
    public static final String ATTR_REAL_IP = "_realIp";

    /**
     * 无参构造：SPI 反射装配走这里。
     */
    public RealIpServerFilter() {
    }

    /**
     * 获取Order
     *
     * <p>排在 IP 黑白名单（MIN+40）之前，保证下游按真实 IP 判定。</p>
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 30;
    }

    /**
     * SupportProtocols
     *
     * <p>代理头只存在于 HTTP 家族协议（含 WebSocket 升级请求）。</p>
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.WS};
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
        String realIp = resolve(request);
        if (StringUtils.isNotEmpty(realIp)) {
            request.setAttribute(ATTR_REAL_IP, realIp);
        }
        chain.doFilter(request, response);
    }

    /**
     * 从代理头解析客户端真实 IP。
     *
     * @param request 请求对象
     * @return 真实 IP，解析不到返回 null
     */
    private String resolve(ServerRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            for (String raw : xff.split(",")) {
                String ip = raw.trim();
                if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(realIp) && !"unknown".equalsIgnoreCase(realIp.trim())) {
            return realIp.trim();
        }
        return null;
    }

    /**
     * 从请求中获取客户端真实 IP。
     *
     * <p>优先取本过滤器写入的 {@link #ATTR_REAL_IP} 属性；
     * 未启用本过滤器时回退为 remoteAddress 去端口。</p>
     *
     * @param request 请求对象
     * @return 客户端 IP，取不到返回空串
     */
    public static String getRealIp(ServerRequest request) {
        Object value = request.getAttribute(ATTR_REAL_IP);
        if (value instanceof String && StringUtils.isNotEmpty((String) value)) {
            return (String) value;
        }
        return IpServerFilter.extractIp(request.getRemoteAddress());
    }
}
