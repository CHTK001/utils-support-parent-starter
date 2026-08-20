package com.chua.acme.support;

import com.chua.common.support.network.ssl.AcmeCertificateResult;
import com.chua.common.support.network.ssl.AcmeConnectionResult;
import com.chua.common.support.network.ssl.AcmeProvider;
import com.chua.common.support.network.ssl.AcmeValidationInfo;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 基于 ACME4J 的 ACME 提供者实现。
 *
 * <p>支持 Let's Encrypt、ZeroSSL 等标准 ACME 服务器，验证方式支持 HTTP-01 与 DNS-01。
 * 申请流程采用「先获取验证信息，后完成签发」的两阶段模式：</p>
 * <ol>
 *     <li>{@link #getValidationInfo} 创建订单并返回域名验证信息（token / 文件路径 / DNS 记录）；</li>
 *     <li>{@link #requestCertificate} 复用同一订单，在验证通过后完成 CSR 提交与证书下载。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
@Slf4j
public class Acme4jProvider implements AcmeProvider {

    /**
     * 最大等待验证次数
     */
    private static final int MAX_ATTEMPTS = 30;

    /**
     * 轮询间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MS = 3000L;

    /**
     * ACME 会话
     */
    private Session session;
    /**
     * ACME 账户
     */
    private Account account;
    /**
     * 账户密钥对
     */
    private KeyPair accountKeyPair;
    /**
     * 账户私钥 PEM 内容
     */
    private String accountPrivateKeyPem;
    /**
     * 当前订单（getValidationInfo 创建，requestCertificate 复用）
     */
    private Order currentOrder;
    /**
     * 证书私钥（申请时生成，返回给调用方）
     */
    private String certificatePrivateKeyPem;

    @Override
    public AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                        String eabKid, String eabHmacKey) {
        try {
            accountKeyPair = loadOrGenerateKeyPair(privateKeyPem);
            accountPrivateKeyPem = writeKeyPairPem(accountKeyPair);

            session = new Session(URI.create(serverUrl));

            AccountBuilder accountBuilder = new AccountBuilder()
                    .agreeToTermsOfService()
                    .useKeyPair(accountKeyPair);

            if (email != null && !email.isEmpty()) {
                accountBuilder.addEmail(email);
            }
            if (eabKid != null && !eabKid.isEmpty() && eabHmacKey != null && !eabHmacKey.isEmpty()) {
                accountBuilder.withKeyIdentifier(eabKid, eabHmacKey);
            }

            account = accountBuilder.create(session);

            log.info("ACME 账户连接成功: {}", serverUrl);
            return AcmeConnectionResult.success(account.getLocation().toString(), accountPrivateKeyPem);

        } catch (Exception e) {
            log.error("ACME 连接失败: {}", serverUrl, e);
            return AcmeConnectionResult.fail(e.getMessage());
        }
    }

    @Override
    public List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType) {
        List<AcmeValidationInfo> result = new ArrayList<>();

        try {
            currentOrder = account.newOrder()
                    .domains(domains.toArray(new String[0]))
                    .create();

            for (Authorization auth : currentOrder.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) {
                    continue;
                }
                String domain = auth.getIdentifier().getDomain();

                for (Challenge challenge : auth.getChallenges()) {
                    AcmeValidationInfo info = buildValidationInfo(domain, challenge);
                    if (info != null) {
                        result.add(info);
                    }
                }
            }

        } catch (Exception e) {
            log.error("获取验证信息失败: domains={}", domains, e);
        }

        return result;
    }

    @Override
    public AcmeCertificateResult requestCertificate(List<String> domains, String challengeType) {
        try {
            if (account == null) {
                return AcmeCertificateResult.fail("未连接 ACME 服务器，请先调用 connect");
            }

            // 复用 getValidationInfo 创建的订单；若不存在则新建
            Order order = currentOrder;
            if (order == null) {
                order = account.newOrder()
                        .domains(domains.toArray(new String[0]))
                        .create();
                currentOrder = order;
            }

            List<AcmeValidationInfo> pending = collectPendingValidations(order, challengeType);
            if (!pending.isEmpty()) {
                return AcmeCertificateResult.needValidation(pending);
            }

            // 所有授权已验证通过，提交 CSR 并等待签发
            return executeAndFetchCertificate(order, domains);

        } catch (Exception e) {
            log.error("证书申请失败: domains={}", domains, e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    @Override
    public AcmeCertificateResult renewCertificate(List<String> domains, String challengeType) {
        currentOrder = null;
        return requestCertificate(domains, challengeType);
    }

    @Override
    public boolean revokeCertificate(String certificatePem) {
        try {
            if (account == null) {
                return false;
            }
            // 从 PEM 解析证书并吊销
            List<X509Certificate> certs = parseCertificates(certificatePem);
            if (certs.isEmpty()) {
                log.warn("证书吊销失败：无法解析证书 PEM");
                return false;
            }
            Certificate.revoke(session, accountKeyPair, certs.get(0), null);
            log.info("证书吊销成功");
            return true;
        } catch (Exception e) {
            log.error("证书吊销失败", e);
            return false;
        }
    }

    @Override
    public String getAccountPrivateKeyPem() {
        return accountPrivateKeyPem;
    }

    @Override
    public void close() {
        session = null;
        account = null;
        accountKeyPair = null;
        currentOrder = null;
        certificatePrivateKeyPem = null;
    }

    /**
     * 构建单条验证信息。
     *
     * @param domain    域名
     * @param challenge 挑战对象
     * @return 验证信息，不支持的挑战类型返回 null
     */
    private AcmeValidationInfo buildValidationInfo(String domain, Challenge challenge) {
        AcmeValidationInfo info = new AcmeValidationInfo();
        if (challenge instanceof Http01Challenge http) {
            info.setDomain(domain);
            info.setChallengeType("HTTP-01");
            info.setToken(http.getToken());
            info.setHttpPath("/.well-known/acme-challenge/" + http.getToken());
            info.setHttpContent(http.getAuthorization());
            return info;
        }
        if (challenge instanceof Dns01Challenge dns) {
            info.setDomain(domain);
            info.setChallengeType("DNS-01");
            info.setToken(null);
            info.setDnsName("_acme-challenge." + domain);
            info.setDnsValue(dns.getDigest());
            return info;
        }
        return null;
    }

    /**
     * 收集订单中尚未通过验证的授权信息。
     *
     * @param order         订单
     * @param challengeType 首选挑战类型（HTTP-01 / DNS-01），不匹配时回退到任一种
     * @return 待验证信息列表，为空表示所有授权均已通过验证
     */
    private List<AcmeValidationInfo> collectPendingValidations(Order order, String challengeType) {
        List<AcmeValidationInfo> pending = new ArrayList<>();
        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) {
                continue;
            }
            String domain = auth.getIdentifier().getDomain();
            AcmeValidationInfo matched = null;
            for (Challenge challenge : auth.getChallenges()) {
                AcmeValidationInfo info = buildValidationInfo(domain, challenge);
                if (info == null) {
                    continue;
                }
                if (challengeType != null && !challengeType.isEmpty()
                        && info.getChallengeType().equalsIgnoreCase(challengeType)) {
                    pending.add(info);
                    matched = null;
                    break;
                }
                if (matched == null) {
                    matched = info;
                }
            }
            if (matched != null) {
                pending.add(matched);
            }
        }
        return pending;
    }

    /**
     * 提交 CSR 并等待签发，下载完整证书链。
     *
     * @param order   订单
     * @param domains 域名列表
     * @return 证书结果
     */
    private AcmeCertificateResult executeAndFetchCertificate(Order order, List<String> domains) {
        try {
            KeyPair certKeyPair = generateKeyPair();
            certificatePrivateKeyPem = writeKeyPairPem(certKeyPair);

            CSRBuilder csrBuilder = new CSRBuilder();
            for (String domain : domains) {
                csrBuilder.addDomain(domain);
            }
            csrBuilder.sign(certKeyPair);

            order.execute(csrBuilder.getEncoded());
            order.update();

            // 等待订单变为 VALID（签发完成）
            int attempts = MAX_ATTEMPTS;
            while (order.getStatus() != Status.VALID && attempts-- > 0) {
                Thread.sleep(POLL_INTERVAL_MS);
                order.update();
            }

            if (order.getStatus() != Status.VALID) {
                return AcmeCertificateResult.fail("订单未在超时时间内完成签发，当前状态: " + order.getStatus());
            }

            Certificate certificate = order.getCertificate();
            if (certificate == null) {
                return AcmeCertificateResult.fail("无法获取证书");
            }

            X509Certificate x509Cert = certificate.getCertificate();
            String chainPem = certificate.getCertificateChain().stream()
                    .map(this::toPem)
                    .reduce("", (a, b) -> a + b);

            String primaryDomain = domains.get(0);
            String san = String.join(",", domains);

            return AcmeCertificateResult.success(
                    chainPem,
                    certificatePrivateKeyPem,
                    primaryDomain,
                    san,
                    x509Cert.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    x509Cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            );

        } catch (Exception e) {
            log.error("提交 CSR 或下载证书失败", e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    /**
     * 加载已有账户私钥或生成新密钥对。
     *
     * @param privateKeyPem 账户私钥 PEM，可为空（生成新密钥）
     * @return 密钥对
     * @throws Exception 加载或生成失败
     */
    private KeyPair loadOrGenerateKeyPair(String privateKeyPem) throws Exception {
        if (privateKeyPem != null && !privateKeyPem.isEmpty()) {
            try {
                return KeyPairUtils.readKeyPair(new StringReader(privateKeyPem));
            } catch (Exception e) {
                log.warn("账户私钥 PEM 解析失败，将重新生成: {}", e.getMessage());
            }
        }
        return generateKeyPair();
    }

    /**
     * 序列化密钥对为 PEM 文本。
     *
     * @param keyPair 密钥对
     * @return PEM 文本
     */
    private String writeKeyPairPem(KeyPair keyPair) {
        try {
            StringWriter writer = new StringWriter();
            KeyPairUtils.writeKeyPair(keyPair, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("密钥对序列化失败", e);
            return null;
        }
    }

    /**
     * 生成 RSA 2048 密钥对。
     *
     * @return 密钥对
     * @throws Exception 生成失败
     */
    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    /**
     * 解析证书 PEM 中的证书列表。
     *
     * @param pem 证书 PEM
     * @return 证书列表
     */
    private List<X509Certificate> parseCertificates(String pem) {
        List<X509Certificate> certs = new ArrayList<>();
        if (pem == null || pem.isEmpty()) {
            return certs;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            byte[] der = pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var stream = new java.io.ByteArrayInputStream(
                    der[0] == '-' ? normalizePemForParsing(pem) : der);
            for (var cert : cf.generateCertificates(stream)) {
                certs.add((X509Certificate) cert);
            }
        } catch (Exception e) {
            log.warn("证书解析失败: {}", e.getMessage());
        }
        return certs;
    }

    /**
     * 将 PEM 字符串转为标准 DER 字节流。
     *
     * @param pem PEM 内容
     * @return DER 字节流
     */
    private byte[] normalizePemForParsing(String pem) {
        return java.util.Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", ""));
    }

    /**
     * X509 证书转 PEM。
     *
     * @param cert 证书
     * @return PEM 内容
     */
    private String toPem(X509Certificate cert) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getEncoder().encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            log.error("证书编码失败", e);
            return "";
        }
    }
}
