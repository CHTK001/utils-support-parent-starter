package com.chua.common.support.network.ssl;

import lombok.Data;

/**
 * ACME 连接结果。
 *
 * @author CH
 * @since 4.0.0.42
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

    /**
    * 创建成功结果
    * @param accountUrl accountURL，不允许为 null
    * @param privateKeyPem private键Pem，不允许为 null
    * @return Acme连接结果 对象
    */
    public static AcmeConnectionResult success(String accountUrl, String privateKeyPem) {
        AcmeConnectionResult result = new AcmeConnectionResult();
        result.setSuccess(true);
        result.setAccountUrl(accountUrl);
        result.setPrivateKeyPem(privateKeyPem);
        return result;
    }

    /**
    * 创建失败结果
    * @param error 方法入参 error
    * @return Acme连接结果 对象
    */
    public static AcmeConnectionResult fail(String error) {
        AcmeConnectionResult result = new AcmeConnectionResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }
}
