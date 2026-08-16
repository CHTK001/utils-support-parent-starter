package com.chua.common.support.network.ssl;

import lombok.Data;

/**
 * ACME 域名验证信息。
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
@Data
public class AcmeValidationInfo {

    /** 域名 */
    private String domain;

    /** 验证类型（HTTP-01 / DNS-01） */
    private String challengeType;

    /** 验证 token */
    private String token;

    /** 验证 URL（HTTP-01） */
    private String httpPath;

    /** 验证内容（HTTP-01） */
    private String httpContent;

    /** DNS 记录名（DNS-01） */
    private String dnsName;

    /** DNS 记录值（DNS-01） */
    private String dnsValue;
}
