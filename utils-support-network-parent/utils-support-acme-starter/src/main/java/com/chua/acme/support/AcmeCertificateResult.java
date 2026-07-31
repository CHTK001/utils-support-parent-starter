package com.chua.acme.support;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ACME 证书结果
 *
 * @author CH
 * @version 1.0.0
 */
@Data
public class AcmeCertificateResult {

    /** 是否成功 */
    private boolean success;

    /** 证书 PEM（含链） */
    private String certificatePem;

    /** 私钥 PEM */
    private String privateKeyPem;

    /** 主域名 */
    private String primaryDomain;

    /** 备用域名（逗号分隔） */
    private String san;

    /** 有效期起 */
    private LocalDateTime notBefore;

    /** 有效期止 */
    private LocalDateTime notAfter;

    /** 错误信息 */
    private String error;

    /** 需要验证（首次申请时） */
    private boolean needsValidation;

    /** 验证信息列表 */
    private java.util.List<AcmeValidationInfo> validationInfos;

    public static AcmeCertificateResult success(String certificatePem, String privateKeyPem,
                                                 String primaryDomain, String san,
                                                 LocalDateTime notBefore, LocalDateTime notAfter) {
        AcmeCertificateResult result = new AcmeCertificateResult();
        result.setSuccess(true);
        result.setCertificatePem(certificatePem);
        result.setPrivateKeyPem(privateKeyPem);
        result.setPrimaryDomain(primaryDomain);
        result.setSan(san);
        result.setNotBefore(notBefore);
        result.setNotAfter(notAfter);
        return result;
    }

    public static AcmeCertificateResult needValidation(java.util.List<AcmeValidationInfo> validationInfos) {
        AcmeCertificateResult result = new AcmeCertificateResult();
        result.setSuccess(false);
        result.setNeedsValidation(true);
        result.setValidationInfos(validationInfos);
        return result;
    }

    public static AcmeCertificateResult fail(String error) {
        AcmeCertificateResult result = new AcmeCertificateResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }
}
