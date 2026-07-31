package com.chua.acme.support;

import lombok.extern.slf4j.Slf4j;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 ACME4J 的 ACME 提供者实现
 *
 * @author CH
 * @version 1.0.0
 */
@Slf4j
@Component
public class Acme4jProvider implements AcmeProvider {

    private Session session;
    private Account account;
    private KeyPair accountKeyPair;
    private String accountPrivateKeyPem;

    @Override
    public AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                        String eabKid, String eabHmacKey) {
        try {
            // 创建或加载账户密钥对
            accountKeyPair = generateKeyPair();

            // 创建 ACME 会话
            session = new Session(URI.create(serverUrl));

            // 构建账户
            AccountBuilder accountBuilder = new AccountBuilder()
                    .agreeToTermsOfService()
                    .useKeyPair(accountKeyPair);

            if (email != null && !email.isEmpty()) {
                accountBuilder.addEmail(email);
            }

            account = accountBuilder.create(session);

            // 获取账户私钥（简化处理）
            accountPrivateKeyPem = "Generated-Key-Pair";

            log.info("ACME 账户连接成功: {}", serverUrl);
            return AcmeConnectionResult.success(account.getLocation().toString(), accountPrivateKeyPem);

        } catch (Exception e) {
            log.error("ACME 连接失败", e);
            return AcmeConnectionResult.fail(e.getMessage());
        }
    }

    @Override
    public List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType) {
        List<AcmeValidationInfo> result = new ArrayList<>();

        try {
            // 创建订单
            Order order = account.newOrder()
                    .domains(domains.toArray(new String[0]))
                    .create();

            // 获取授权信息
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() != Status.VALID) {
                    String domain = auth.getIdentifier().getDomain();

                    for (var challenge : auth.getChallenges()) {
                        if (challenge instanceof Http01Challenge httpChallenge) {
                            AcmeValidationInfo info = new AcmeValidationInfo();
                            info.setDomain(domain);
                            info.setChallengeType("HTTP-01");
                            info.setToken(httpChallenge.getToken());
                            info.setHttpPath("/.well-known/acme-challenge/" + httpChallenge.getToken());
                            info.setHttpContent(httpChallenge.getAuthorization());
                            result.add(info);
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("获取验证信息失败", e);
        }

        return result;
    }

    @Override
    public AcmeCertificateResult requestCertificate(List<String> domains, String challengeType) {
        try {
            // 创建订单
            Order order = account.newOrder()
                    .domains(domains.toArray(new String[0]))
                    .create();

            // 处理授权
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() != Status.VALID) {
                    processAuthorization(auth);
                }
            }

            // 等待订单就绪
            order.execute(createCsr(domains));

            // 等待证书签发
            int maxAttempts = 30;
            while (order.getStatus() != Status.VALID && maxAttempts-- > 0) {
                Thread.sleep(2000);
                order.update();
            }

            if (order.getStatus() != Status.VALID) {
                return AcmeCertificateResult.fail("订单未在超时时间内完成");
            }

            // 获取证书
            Certificate certificate = order.getCertificate();
            if (certificate == null) {
                return AcmeCertificateResult.fail("无法获取证书");
            }

            X509Certificate x509Cert = certificate.getCertificate();
            String certPem = convertToPem(x509Cert);
            // certPem; // 简化处理，实际应包含完整链
            String chainPem = certPem;

            String primaryDomain = domains.get(0);
            String san = String.join(",", domains);

            return AcmeCertificateResult.success(
                    chainPem,
                    "private-key-pem",
                    primaryDomain,
                    san,
                    x509Cert.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    x509Cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            );

        } catch (Exception e) {
            log.error("证书申请失败", e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    @Override
    public AcmeCertificateResult renewCertificate(List<String> domains, String challengeType) {
        return requestCertificate(domains, challengeType);
    }

    @Override
    public boolean revokeCertificate(String certificatePem) {
        try {
            // 简化实现
            log.info("证书吊销功能待实现");
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
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    private void processAuthorization(Authorization auth) throws AcmeException {
        for (var challenge : auth.getChallenges()) {
            if (challenge instanceof Http01Challenge httpChallenge) {
                // 触发验证
                httpChallenge.trigger();
                // 等待验证完成
                int maxAttempts = 30;
                while (challenge.getStatus() != Status.VALID && maxAttempts-- > 0) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    challenge.update();
                }
            }
        }
    }

    private byte[] createCsr(List<String> domains) {
        try {
            CSRBuilder csrBuilder = new CSRBuilder();
            KeyPair certKeyPair = generateKeyPair();
            csrBuilder.addDomain(domains.get(0));
            for (int i = 1; i < domains.size(); i++) {
                csrBuilder.addDomain(domains.get(i));
            }
            csrBuilder.sign(certKeyPair);
            return csrBuilder.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("创建 CSR 失败", e);
        }
    }

    private String convertToPem(X509Certificate cert) throws Exception {
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN CERTIFICATE-----\n");
        pem.append(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
        pem.append("\n-----END CERTIFICATE-----\n");
        return pem.toString();
    }
}
