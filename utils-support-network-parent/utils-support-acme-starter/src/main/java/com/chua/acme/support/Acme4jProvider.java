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
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 acme4j 的 ACME 提供者实现，对接 Let's Encrypt、ZeroSSL 等兼容 RFC 8555 的 CA。
 *
 * <p>核心流程：{@link #connect} 建立账户会话（复用已保存账户私钥，支持 EAB 外部账户绑定），
 * {@link #getValidationInfo} 创建订单并返回 HTTP-01/DNS-01 验证信息，
 * 调用方完成验证部署后由 {@link #requestCertificate} 复用同一订单触发验证、提交 CSR 并下载完整证书链。</p>
 *
 * <p>注意：本实例为单次操作对象（SPI 每次获取新实例），订单状态仅在同一次申请流程内复用；
 * 跨请求（如用户手动部署后重新验证）需通过 {@link #resumeCertificate} 基于订单地址恢复。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.1.0
 */
@Slf4j
@Component
public class Acme4jProvider implements AcmeProvider {

    /**
    * 对外暴露的 HTTP-01 挑战类型名称
    */
    private static final String CHALLENGE_NAME_HTTP_01 = "HTTP-01";

    /**
    * 对外暴露的 DNS-01 挑战类型名称
    */
    private static final String CHALLENGE_NAME_DNS_01 = "DNS-01";

    /**
    * 账户 RSA 密钥长度
    */
    private static final int ACCOUNT_KEY_SIZE = 2048;

    /**
    * 等待 CA 域名验证与订单签发的超时时长（秒）
    */
    private static final long POLL_TIMEOUT_SECONDS = 90L;

    /**
    * PEM 内容识别标识
    */
    private static final String PEM_BEGIN_MARKER = "-----BEGIN";

    /**
    * PEM 证书块匹配模式，证书链时捕获首个叶子证书块
    */
    private static final Pattern PEM_CERT_PATTERN =
            Pattern.compile("-----BEGIN CERTIFICATE-----\\s*(.*?)\\s*-----END CERTIFICATE-----", Pattern.DOTALL);

    /**
    * ACME 会话
    */
    private Session session;

    /**
    * ACME 登录态（订单恢复、证书吊销均依赖该对象）
    */
    private Login login;

    /**
    * ACME 账户
    */
    private Account account;

    /**
    * 账户密钥对
    */
    private KeyPair accountKeyPair;

    /**
    * 当前订单，由 getValidationInfo 创建、requestCertificate 复用，避免重复下单
    */
    private Order currentOrder;

    /**
    * 当前订单对应的域名密钥对，CSR 使用该密钥，签发后作为证书私钥返回
    */
    private KeyPair domainKeyPair;

    /**
    * 账户私钥 PEM 内容
    */
    private String accountPrivateKeyPem;

    @Override
    public AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                        String eabKid, String eabHmacKey) {
        try {
            // 优先加载已保存的账户私钥，不存在或为历史占位值时新生成密钥对
            accountKeyPair = loadOrCreateAccountKeyPair(privateKeyPem);

            // 创建 ACME 会话
            session = new Session(URI.create(serverUrl));

            // 构建账户：同意服务条款、绑定密钥、可选邮箱与 EAB 外部账户绑定
            AccountBuilder accountBuilder = new AccountBuilder()
                    .agreeToTermsOfService()
                    .useKeyPair(accountKeyPair);
            if (email != null && !email.isBlank()) {
                accountBuilder.addEmail(email);
            }
            if (eabKid != null && !eabKid.isBlank() && eabHmacKey != null && !eabHmacKey.isBlank()) {
                accountBuilder.withKeyIdentifier(eabKid, eabHmacKey);
            }

            // createLogin 对新账户执行注册，对已存在账户自动绑定
            login = accountBuilder.createLogin(session);
            account = login.getAccount();

            // 返回真实账户私钥 PEM，供首次注册后持久化
            accountPrivateKeyPem = writeKeyPair(accountKeyPair);

            log.info("ACME 账户连接成功: serverUrl={}, accountUrl={}", serverUrl, account.getLocation());
            return AcmeConnectionResult.success(account.getLocation().toString(), accountPrivateKeyPem);
        } catch (Exception e) {
            log.error("ACME 账户连接失败: serverUrl={}", serverUrl, e);
            return AcmeConnectionResult.fail(e.getMessage());
        }
    }

    @Override
    public List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType) {
        List<AcmeValidationInfo> result = new ArrayList<>();
        try {
            // 创建订单并生成域名密钥对，后续 requestCertificate 必须复用该订单
            prepareOrder(domains);

            String acme4jChallengeType = normalizeChallengeType(challengeType);
            String orderUrl = currentOrder.getLocation().toString();

            for (Authorization auth : currentOrder.getAuthorizations()) {
                // 授权仍有效（通常 30 天内重复申请）时无需再次验证
                if (auth.getStatus() == Status.VALID) {
                    continue;
                }
                String domain = auth.getIdentifier().getDomain();
                if (Dns01Challenge.TYPE.equals(acme4jChallengeType)) {
                    Optional<Dns01Challenge> challengeOptional = auth.findChallenge(Dns01Challenge.class);
                    if (challengeOptional.isPresent()) {
                        Dns01Challenge challenge = challengeOptional.get();
                        AcmeValidationInfo info = new AcmeValidationInfo();
                        info.setOrderUrl(orderUrl);
                        info.setDomain(domain);
                        info.setChallengeType(CHALLENGE_NAME_DNS_01);
                        info.setDnsName(Dns01Challenge.toRRName(domain));
                        info.setDnsValue(challenge.getDigest());
                        result.add(info);
                    }
                } else {
                    Optional<Http01Challenge> challengeOptional = auth.findChallenge(Http01Challenge.class);
                    if (challengeOptional.isPresent()) {
                        Http01Challenge challenge = challengeOptional.get();
                        AcmeValidationInfo info = new AcmeValidationInfo();
                        info.setOrderUrl(orderUrl);
                        info.setDomain(domain);
                        info.setChallengeType(CHALLENGE_NAME_HTTP_01);
                        info.setToken(challenge.getToken());
                        info.setHttpPath("/.well-known/acme-challenge/" + challenge.getToken());
                        info.setHttpContent(challenge.getAuthorization());
                        result.add(info);
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取域名验证信息失败: domains={}", domains, e);
        }
        return result;
    }

    @Override
    public AcmeCertificateResult requestCertificate(List<String> domains, String challengeType) {
        try {
            if (currentOrder == null) {
                // 未提前获取验证信息直接申请：内部完成下单，适用于授权仍有效或验证已由外部部署的场景
                prepareOrder(domains);
            }
            return finalizeOrder(domains, normalizeChallengeType(challengeType));
        } catch (Exception e) {
            restoreInterruptFlag(e);
            log.error("证书申请失败: domains={}", domains, e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    @Override
    public AcmeCertificateResult renewCertificate(List<String> domains, String challengeType) {
        // ACME 续签本质是基于同一账户重新走完整下单流程，调用方需先获取验证信息并完成部署
        log.info("ACME 续签证书: domains={}", domains);
        return requestCertificate(domains, challengeType);
    }

    @Override
    public AcmeCertificateResult resumeCertificate(String orderUrl, List<String> domains, String challengeType) {
        try {
            URL location = URI.create(orderUrl).toURL();
            currentOrder = login.bindOrder(location);
            currentOrder.update();
            domainKeyPair = KeyPairUtils.createKeyPair(ACCOUNT_KEY_SIZE);
            log.info("恢复 ACME 历史订单: orderUrl={}", orderUrl);
            return finalizeOrder(domains, normalizeChallengeType(challengeType));
        } catch (Exception e) {
            restoreInterruptFlag(e);
            log.error("恢复 ACME 历史订单失败: orderUrl={}", orderUrl, e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    @Override
    public boolean revokeCertificate(String certificatePem) {
        if (login == null) {
            log.error("证书吊销失败：尚未连接 ACME 服务器");
            return false;
        }
        try {
            X509Certificate x509Certificate = parseCertificate(certificatePem);
            Certificate.revoke(login, x509Certificate, org.shredzone.acme4j.RevocationReason.UNSPECIFIED);
            log.info("证书吊销成功: subject={}", x509Certificate.getSubjectX500Principal());
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
        login = null;
        account = null;
        accountKeyPair = null;
        currentOrder = null;
        domainKeyPair = null;
        accountPrivateKeyPem = null;
    }

    /**
    * 创建 ACME 订单并生成域名密钥对。
    *
    * @param domains 域名列表（主域名在前，SAN 在后）
    * @throws AcmeException 下单失败时抛出
    */
    private void prepareOrder(List<String> domains) throws AcmeException {
        currentOrder = account.newOrder()
                .domains(domains.toArray(new String[0]))
                .create();
        domainKeyPair = KeyPairUtils.createKeyPair(ACCOUNT_KEY_SIZE);
    }

    /**
    * 完成订单收尾：触发并等待域名验证、提交 CSR、等待签发、下载完整证书链。
    *
    * @param domains              域名列表
    * @param acme4jChallengeType  acme4j 规范的挑战类型（http-01/dns-01）
    * @return 证书申请结果
    * @throws Exception 验证或签发过程中发生异常时抛出
    */
    private AcmeCertificateResult finalizeOrder(List<String> domains, String acme4jChallengeType) throws Exception {
        Duration timeout = Duration.ofSeconds(POLL_TIMEOUT_SECONDS);

        // 逐个触发未完成授权的挑战并等待 CA 验证通过
        for (Authorization auth : currentOrder.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) {
                continue;
            }
            String domain = auth.getIdentifier().getDomain();
            Challenge challenge = auth.findChallenge(acme4jChallengeType).orElse(null);
            if (challenge == null) {
                return AcmeCertificateResult.fail("域名 " + domain + " 不支持 " + acme4jChallengeType + " 验证方式");
            }
            if (challenge.getStatus() != Status.VALID) {
                challenge.trigger();
                Status challengeStatus = challenge.waitForCompletion(timeout);
                if (challengeStatus != Status.VALID) {
                    return AcmeCertificateResult.fail("域名 " + domain + " 验证未通过，当前状态: " + challengeStatus);
                }
            }
        }

        // 提交 CSR（acme4j 根据订单标识符自动构建 CSR），密钥与返回的证书私钥为同一把
        currentOrder.execute(domainKeyPair);
        Status orderStatus = currentOrder.waitForCompletion(timeout);
        if (orderStatus != Status.VALID) {
            return AcmeCertificateResult.fail("订单未在超时时间内完成签发，当前状态: " + orderStatus);
        }

        Certificate certificate = currentOrder.getCertificate();
        if (certificate == null) {
            return AcmeCertificateResult.fail("订单已完成但无法获取证书");
        }
        return buildSuccessResult(certificate, domains);
    }

    /**
    * 组装签发成功结果，证书链与私钥均输出标准 PEM。
    *
    * @param certificate acme4j 证书资源（含完整链）
    * @param domains     域名列表
    * @return 成功结果
    * @throws IOException 私钥序列化失败时抛出
    */
    private AcmeCertificateResult buildSuccessResult(Certificate certificate, List<String> domains) throws IOException {
        // writeCertificate 输出叶子证书及中间证书组成的完整 PEM 链
        StringWriter chainWriter = new StringWriter(2048);
        certificate.writeCertificate(chainWriter);
        String chainPem = chainWriter.toString();

        // 输出域名密钥对的标准 PKCS#8 PEM
        String keyPem = writeKeyPair(domainKeyPair);

        X509Certificate leafCertificate = certificate.getCertificate();
        String primaryDomain = domains.getFirst();
        String san = String.join(",", domains);

        return AcmeCertificateResult.success(
                chainPem,
                keyPem,
                primaryDomain,
                san,
                leafCertificate.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                leafCertificate.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
        );
    }

    /**
    * 加载已持久化的账户私钥；为空、历史占位值或解析失败时重新生成。
    *
    * @param privateKeyPem 账户私钥 PEM
    * @return 可用的账户密钥对
    * @throws IOException 新密钥对生成失败时抛出
    */
    private KeyPair loadOrCreateAccountKeyPair(String privateKeyPem) throws IOException {
        if (privateKeyPem != null && privateKeyPem.contains(PEM_BEGIN_MARKER)) {
            try (StringReader reader = new StringReader(privateKeyPem)) {
                return KeyPairUtils.readKeyPair(reader);
            } catch (Exception e) {
                log.warn("解析已保存的账户私钥失败，将重新生成密钥对: {}", e.getMessage());
            }
        }
        return KeyPairUtils.createKeyPair(ACCOUNT_KEY_SIZE);
    }

    /**
    * 将密钥对序列化为标准 PEM 文本。
    *
    * @param keyPair 密钥对
    * @return PEM 文本
    * @throws IOException 序列化失败时抛出
    */
    private String writeKeyPair(KeyPair keyPair) throws IOException {
        StringWriter writer = new StringWriter(1024);
        KeyPairUtils.writeKeyPair(keyPair, writer);
        return writer.toString();
    }

    /**
    * 解析 PEM 格式证书（证书链时取首个叶子证书块）。
    *
    * @param certificatePem 证书 PEM
    * @return X509 证书
    * @throws CertificateException 解析失败或未找到证书块时抛出
    */
    private X509Certificate parseCertificate(String certificatePem) throws CertificateException {
        Matcher matcher = PEM_CERT_PATTERN.matcher(certificatePem);
        if (!matcher.find()) {
            throw new CertificateException("证书 PEM 格式不正确，未找到 CERTIFICATE 块");
        }
        String base64Content = matcher.group(1).replaceAll("\\s", "");
        byte[] derBytes = Base64.getDecoder().decode(base64Content);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(derBytes));
    }

    /**
    * 将外部挑战类型名称归一化为 acme4j 挑战类型常量。
    *
    * @param challengeType 挑战类型（HTTP-01/DNS-01，大小写不敏感）
    * @return acme4j 挑战类型常量
    */
    private String normalizeChallengeType(String challengeType) {
        if (CHALLENGE_NAME_DNS_01.equalsIgnoreCase(challengeType)) {
            return Dns01Challenge.TYPE;
        }
        return Http01Challenge.TYPE;
    }

    /**
    * 异常为中断异常时恢复线程中断标记。
    *
    * @param e 异常
    */
    private void restoreInterruptFlag(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
