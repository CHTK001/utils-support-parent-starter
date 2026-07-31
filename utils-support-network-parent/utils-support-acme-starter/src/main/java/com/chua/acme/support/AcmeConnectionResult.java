package com.chua.acme.support;

import lombok.Data;

/**
 * ACME 连接结果
 *
 * @author CH
 * @version 1.0.0
 */
@Data
public class AcmeConnectionResult {

    /** 是否成功 */
    private boolean success;

    /** 账户 URL */
    private String accountUrl;

    /** 账户私钥 PEM */
    private String privateKeyPem;

    /** 错误信息 */
    private String error;

    public static AcmeConnectionResult success(String accountUrl, String privateKeyPem) {
        AcmeConnectionResult result = new AcmeConnectionResult();
        result.setSuccess(true);
        result.setAccountUrl(accountUrl);
        result.setPrivateKeyPem(privateKeyPem);
        return result;
    }

    public static AcmeConnectionResult fail(String error) {
        AcmeConnectionResult result = new AcmeConnectionResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }
}
