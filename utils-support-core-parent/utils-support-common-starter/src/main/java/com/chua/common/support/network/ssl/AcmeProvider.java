package com.chua.common.support.network.ssl;

import java.util.List;

/**
* ACME 证书服务提供者接口。
*
* <p>定义证书申请、续签、吊销等操作的统一契约，
* 支持真实 ACME 服务器（如 Let's Encrypt）和 JDK 自签名证书两种模式。</p>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
public interface AcmeProvider {

    /**
    * 连接 ACME 服务器
     */
    default AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem) {
        return connect(serverUrl, email, privateKeyPem, null, null);
    }

    /**
    * 连接 ACME 服务器（含 EAB 绑定）
     */
    AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                  String eabKid, String eabHmacKey);

    /**
    * 获取域名验证信息
     */
    List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType);

    /**
    * 申请证书
     */
    AcmeCertificateResult requestCertificate(List<String> domains, String challengeType);

    /**
    * 续签证书
     */
    AcmeCertificateResult renewCertificate(List<String> domains, String challengeType);

    /**
    * 吊销证书
     */
    boolean revokeCertificate(String certificatePem);

    /**
    * 获取账户私钥 PEM
     */
    String getAccountPrivateKeyPem();

    /**
    * 关闭连接，释放资源
     */
    void close();
}
