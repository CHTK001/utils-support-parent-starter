package com.chua.common.support.network.ssl;

/**
 * ACME 域名验证信息。
 *
 * @param orderUrl ACME
 *                 订单地址（同一订单的全部验证信息
 *                 均携带该地址，用于跨请求恢复订单
 *                 ）
 * @param domain 域名
 * @param challengeType 验证类型（HTTP-01 / DNS-01）
 * @param token 验证 token
 * @param httpPath 验证 URL（HTTP-01）
 * @param httpContent 验证内容（HTTP-01）
 * @param dnsName DNS 记录名（DNS-01）
 * @param dnsValue DNS 记录值（DNS-01）
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public record AcmeValidationInfo(
        String orderUrl,
        String domain,
        String challengeType,
        String token,
        String httpPath,
        String httpContent,
        String dnsName,
        String dnsValue
) {
}
