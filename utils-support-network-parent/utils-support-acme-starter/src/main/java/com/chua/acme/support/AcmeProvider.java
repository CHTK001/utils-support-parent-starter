package com.chua.acme.support;

import java.util.List;

/**
 * ACME 证书服务提供者接口
 *
 * @author CH
 * @version 1.0.0
 * @since 2025-12-09
 */
public interface AcmeProvider {

    /**
     * 连接到 ACME 服务器
     *
     * @param serverUrl     ACME 服务器地址
     * @param email         账户邮箱
     * @param privateKeyPem 私钥 PEM（可选，为空则自动生成）
     * @return 连接结果
     */
    default AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem) {
        return connect(serverUrl, email, privateKeyPem, null, null);
    }

    /**
     * 连接到 ACME 服务器（支持 EAB）
     *
     * @param serverUrl     ACME 服务器地址
     * @param email         账户邮箱
     * @param privateKeyPem 私钥 PEM（可选，为空则自动生成）
     * @param eabKid        External Account Binding Key ID（ZeroSSL 等需要）
     * @param eabHmacKey    External Account Binding HMAC Key (Base64)
     * @return 连接结果
     */
    AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem, String eabKid, String eabHmacKey);

    /**
     * 获取域名验证信息
     *
     * @param domains       域名列表
     * @param challengeType 挑战类型（HTTP-01 / DNS-01）
     * @return 验证信息列表
     */
    List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType);

    /**
     * 申请证书
     *
     * @param domains       域名列表
     * @param challengeType 挑战类型
     * @return 证书结果
     */
    AcmeCertificateResult requestCertificate(List<String> domains, String challengeType);

    /**
     * 续签证书
     *
     * @param domains       域名列表
     * @param challengeType 挑战类型
     * @return 证书结果
     */
    AcmeCertificateResult renewCertificate(List<String> domains, String challengeType);

    /**
     * 吊销证书
     *
     * @param certificatePem 证书 PEM
     * @return 是否成功
     */
    boolean revokeCertificate(String certificatePem);

    /**
     * 获取账户私钥 PEM
     *
     * @return 私钥 PEM
     */
    String getAccountPrivateKeyPem();

    /**
     * 关闭连接
     */
    void close();
}
