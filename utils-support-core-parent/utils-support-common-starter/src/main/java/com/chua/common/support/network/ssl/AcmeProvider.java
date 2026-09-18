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
    * @param serverUrl 服务端URL，不允许为 null
    * @param email 方法入参 email
    * @param privateKeyPem private键Pem，不允许为 null
    * @return Acme连接结果 对象
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
    * @param domains 方法入参 domains
    * @param challengeType challenge类型，不允许为 null
    * @return 结果列表，无数据时为空列表
    */
    List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType);

    /**
    * 申请证书
    * @param domains 方法入参 domains
    * @param challengeType challenge类型，不允许为 null
    * @return AcmeCertificate结果 对象
    */
    AcmeCertificateResult requestCertificate(List<String> domains, String challengeType);

    /**
    * 续签证书
    * @param domains 方法入参 domains
    * @param challengeType challenge类型，不允许为 null
    * @return AcmeCertificate结果 对象
    */
    AcmeCertificateResult renewCertificate(List<String> domains, String challengeType);

    /**
    * 基于历史订单地址继续完成证书申请（跨请求恢复订单）。
    *
    * <p>适用于用户手动部署验证文件后重新验证的场景：首次申请时已创建订单，
    * 用户部署完成后在新的请求中根据订单地址恢复并触发验证、提交 CSR。</p>
    *
    * @param orderUrl      ACME 订单地址
    * @param domains       域名列表
    * @param challengeType 挑战类型（HTTP-01/DNS-01）
    * @return 证书申请结果
    */
    default AcmeCertificateResult resumeCertificate(String orderUrl, List<String> domains, String challengeType) {
        return AcmeCertificateResult.fail("当前 ACME 提供者不支持恢复历史订单");
    }

    /**
    * 吊销证书
    * @param certificatePem 方法入参 certificatePem
    * @return 是否成功（true 表示成功）
    */
    boolean revokeCertificate(String certificatePem);

    /**
    * 获取账户私钥 PEM
    * @return 结果字符串
    */
    String getAccountPrivateKeyPem();

    /**
    * 关闭连接，释放资源
    */
    void close();
}
