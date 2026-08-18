package com.chua.common.support.network.ssl;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK keytool 的证书生成提供者实现。
 *
 * <p>使用 JDK 自带的 keytool 命令行工具生成自签名证书，
 * 无需连接外部 ACME 服务器，适用于开发、测试和内网环境。</p>
 *
 * <p><b>特性说明：</b></p>
 * <ul>
 *   <li>基于 JDK keytool 命令生成自签名证书</li>
 *   <li>支持 RSA 和 EC 密钥算法</li>
 *   <li>支持 JKS 和 PKCS12 密钥库类型</li>
 *   <li>支持自定义有效期和 SAN 扩展</li>
 *   <li>无需外部 ACME 服务器，离线可用</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
@Slf4j
@Spi("jdk")
public class JdkCertificateProvider implements AcmeProvider {

    /** Keytool */
    private static final String KEYTOOL = "keytool";
    /** Default_key_alg */
    private static final String DEFAULT_KEY_ALG = "RSA";
    /** Default_key_size */
    private static final int DEFAULT_KEY_SIZE = 2048;
    /** Default_keystore_type */
    private static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
    /** Default_validity_days */
    private static final int DEFAULT_VALIDITY_DAYS = 365;

    /** 密钥ALG */
    private String keyAlg = DEFAULT_KEY_ALG;
    /** 密钥尺寸 */
    private int keySize = DEFAULT_KEY_SIZE;
    /** Keystore类型 */
    private String keystoreType = DEFAULT_KEYSTORE_TYPE;
    /** Validitydays */
    private int validityDays = DEFAULT_VALIDITY_DAYS;
    /** Keystore路径 */
    private String keystorePath;
    /** Keystore密码 */
    private String keystorePassword = "changeit";

    /** Temp目录 */
    private Path tempDir;
    /** Account私有密钥PEM */
    private String accountPrivateKeyPem;

    @Override
    public AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                         String eabKid, String eabHmacKey) {
        try {
            // JDK 证书提供者不需要连接外部服务器
            tempDir = Files.createTempDirectory("jdk-cert-");
            log.info("JDK 证书提供者初始化成功，临时目录: {}", tempDir);
            return AcmeConnectionResult.success("jdk://local", "generated-by-jdk");
        } catch (Exception e) {
            log.error("JDK 证书提供者初始化失败", e);
            return AcmeConnectionResult.fail(e.getMessage());
        }
    }

    @Override
    public List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType) {
        // JDK 自签名证书无需域名验证
        log.info("JDK 自签名证书无需域名验证，直接返回空列表");
        return Collections.emptyList();
    }

    @Override
    public AcmeCertificateResult requestCertificate(List<String> domains, String challengeType) {
        if (domains == null || domains.isEmpty()) {
            return AcmeCertificateResult.fail("域名列表不能为空");
        }

        String primaryDomain = domains.get(0);
        String san = String.join(",", domains);

        try {
            // 构建密钥库文件路径
            String keystoreFile = getKeystorePath(primaryDomain);

            // 构建 keytool 命令
            String command = buildKeytoolCommand(primaryDomain, domains, keystoreFile);

            // 执行 keytool 命令
            CmdResult cmdResult = CmdExecutors.execute(command, 60, TimeUnit.SECONDS);

            if (!cmdResult.isSuccess()) {
                log.error("keytool 命令执行失败: {}", cmdResult.getStderr());
                return AcmeCertificateResult.fail("keytool 命令执行失败，退出码: " + cmdResult.getExitCode());
            }

            // 从密钥库中读取证书和私钥
            return extractCertificateFromKeystore(keystoreFile, primaryDomain, san);

        } catch (Exception e) {
            log.error("JDK 证书生成失败", e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    @Override
    public AcmeCertificateResult renewCertificate(List<String> domains, String challengeType) {
        // 续签即重新生成
        log.info("JDK 自签名证书续签，将重新生成证书");
        return requestCertificate(domains, challengeType);
    }

    @Override
    public boolean revokeCertificate(String certificatePem) {
        // JDK 自签名证书无需吊销
        log.info("JDK 自签名证书无需吊销操作");
        return true;
    }

    @Override
    public String getAccountPrivateKeyPem() {
        return accountPrivateKeyPem;
    }

    @Override
    public void close() {
        // 清理临时目录
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
            tempDir = null;
        }
    }

    /**
     * 构建 keytool 命令字符串
     */
    private String buildKeytoolCommand(String primaryDomain, List<String> domains, String keystoreFile) {
        StringBuilder cmd = new StringBuilder(KEYTOOL);
        cmd.append(" -genkeypair");
        cmd.append(" -alias ").append(primaryDomain);
        cmd.append(" -keyalg ").append(keyAlg);
        cmd.append(" -keysize ").append(keySize);
        cmd.append(" -sigalg ").append(getSigAlg());
        cmd.append(" -dname \"").append(buildDName(primaryDomain)).append("\"");
        cmd.append(" -validity ").append(validityDays);
        cmd.append(" -storetype ").append(keystoreType);
        cmd.append(" -keystore \"").append(keystoreFile).append("\"");
        cmd.append(" -storepass ").append(keystorePassword);
        cmd.append(" -keypass ").append(keystorePassword);

        // 添加 SAN 扩展
        if (domains.size() > 1 || !isIpAddress(primaryDomain)) {
            cmd.append(" -ext san=").append(buildSanExtension(domains));
        }

        return cmd.toString();
    }

    /**
     * 构建 SAN 扩展字符串
     */
    private String buildSanExtension(List<String> domains) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < domains.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String domain = domains.get(i);
            if (isIpAddress(domain)) {
                sb.append("ip:").append(domain);
            } else {
                sb.append("dns:").append(domain);
            }
        }
        return sb.toString();
    }

    /**
     * 构建 DN (Distinguished Name)
     */
    private String buildDName(String domain) {
        return "CN=" + domain + ",OU=Self-Signed,O=JDK-Certificate,L=Unknown,ST=Unknown,C=CN";
    }

    /**
     * 获取签名算法
     */
    private String getSigAlg() {
        if ("EC".equalsIgnoreCase(keyAlg)) {
            return "SHA256withECDSA";
        }
        return "SHA256withRSA";
    }

    /**
     * 判断是否为 IP 地址
     */
    private boolean isIpAddress(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // IPv4 简单判断
        String[] parts = value.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                try {
                    int num = Integer.parseInt(part);
                    if (num < 0 || num > 255) {
                        return false;
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        // IPv6 简单判断
        return value.contains(":");
    }

    /**
     * 从密钥库中提取证书信息
     */
    private AcmeCertificateResult extractCertificateFromKeystore(String keystoreFile,
                                                                   String primaryDomain, String san) {
        try (InputStream is = new FileInputStream(keystoreFile)) {
            KeyStore ks = KeyStore.getInstance(keystoreType);
            ks.load(is, keystorePassword.toCharArray());

            // 获取证书
            java.security.cert.Certificate cert = ks.getCertificate(primaryDomain);
            if (!(cert instanceof X509Certificate x509Cert)) {
                return AcmeCertificateResult.fail("无法获取 X509 证书");
            }

            // 转换为 PEM 格式
            String certPem = convertToPem(x509Cert);

            // 获取私钥 PEM（简化处理，标记为 JDK 生成）
            String privateKeyPem = "JDK-Generated-Private-Key";
            accountPrivateKeyPem = privateKeyPem;

            LocalDateTime notBefore = x509Cert.getNotBefore().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime notAfter = x509Cert.getNotAfter().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();

            log.info("JDK 证书生成成功，域名: {}，有效期: {} ~ {}", primaryDomain, notBefore, notAfter);

            return AcmeCertificateResult.success(
                    certPem,
                    privateKeyPem,
                    primaryDomain,
                    san,
                    notBefore,
                    notAfter
            );

        } catch (Exception e) {
            log.error("从密钥库提取证书失败", e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    /**
     * 获取密钥库文件路径
     */
    public String getKeystorePath(String domain) {
        if (keystorePath != null && !keystorePath.isEmpty()) {
            return keystorePath;
        }
        if (tempDir != null) {
            return tempDir.resolve(domain + ".p12").toString();
        }
        return domain + ".p12";
    }

    /**
     * 将 X509 证书转换为 PEM 格式
     */
    private String convertToPem(X509Certificate cert) throws Exception {
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN CERTIFICATE-----\n");
        pem.append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(cert.getEncoded()));
        pem.append("\n-----END CERTIFICATE-----\n");
        return pem.toString();
    }

    // ==================== 配置方法 ====================

    /**
     * 设置密钥算法
     *
     * @param keyAlg 密钥算法（RSA / EC）
     * @return this
     */
    public JdkCertificateProvider setKeyAlg(String keyAlg) {
        this.keyAlg = keyAlg;
        return this;
    }

    /**
     * 设置密钥大小
     *
     * @param keySize 密钥大小
     * @return this
     */
    public JdkCertificateProvider setKeySize(int keySize) {
        this.keySize = keySize;
        return this;
    }

    /**
     * 设置密钥库类型
     *
     * @param keystoreType 密钥库类型（JKS / PKCS12）
     * @return this
     */
    public JdkCertificateProvider setKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
        return this;
    }

    /**
     * 设置证书有效期（天）
     *
     * @param validityDays 有效期天数
     * @return this
     */
    public JdkCertificateProvider setValidityDays(int validityDays) {
        this.validityDays = validityDays;
        return this;
    }

    /**
     * 设置密钥库路径
     *
     * @param keystorePath 密钥库文件路径
     * @return this
     */
    public JdkCertificateProvider setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
        return this;
    }

    /**
     * 设置密钥库密码
     *
     * @param keystorePassword 密钥库密码
     * @return this
     */
    public JdkCertificateProvider setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
        return this;
    }
}
