// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.ThreadLocalRandom;
import com.chua.common.support.utils.StringUtils;

/**
 * 请求 ID 过滤器，为每个请求生成唯一标识。
 *
 * <p>生成 32 位十六进制请求 ID 并设置到：</p>
 * <ul>
 *   <li>请求属性 {@code _requestId} — 供下游 filter/handler 使用</li>
 *   <li>响应头 {@code X-Request-Id} — 返回给客户端</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/18
 */
public class RequestIdServerFilter implements ServerFilter {

    /**
     * 十六进制字符表
     */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 20;
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        // e[0]; // 所有协议
        return new ProtocolType[] {};
    }

    /**
     * Do过滤
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 优先使用客户端传入的 X-Request-Id
        String requestId = request.getHeader("X-Request-Id");
        if (StringUtils.isEmpty(requestId)) {
            requestId = newRequestId();
        }

        request.setAttribute("_requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        chain.doFilter(request, response);
    }

    /**
     * 生成 32 位十六进制请求 ID（与去掉连字符的 UUID 同格式，对下游透明）。
     *
     * <p>不用 {@link java.util.UUID#randomUUID()}：它走共享的 SecureRandom，高并发下
     * 成为串行点，且 {@code toString().replace("-", "")} 需额外分配 3 个字符串。
     * 请求 ID 只需要「同一时刻基本不重复」，不需要密码学随机。</p>
     *
     * @return 32 位十六进制请求 ID
     */
    private static String newRequestId() {
        char[] buf = new char[32];
        toHex(ThreadLocalRandom.current().nextLong(), buf, 0);
        toHex(ThreadLocalRandom.current().nextLong(), buf, 16);
        return new String(buf);
    }

    /**
     * 把 long 的 64 位写成 16 位十六进制。
     *
     * @param value  数值
     * @param buf    目标缓冲
     * @param offset 写入起点
     */
    private static void toHex(long value, char[] buf, int offset) {
        for (int i = 0; i < 16; i++) {
            int digit = (int) ((value >>> ((15 - i) << 2)) & 0xF);
            buf[offset + i] = HEX_DIGITS[digit];
        }
    }

    /**
     * 从请求中获取 requestId。
     *
     * @param request 请求对象
     * @return requestId，不存在返回 null
     */
    public static String getRequestId(ServerRequest request) {
        Object value = request.getAttribute("_requestId");
        return value instanceof String ? (String) value : null;
    }
}