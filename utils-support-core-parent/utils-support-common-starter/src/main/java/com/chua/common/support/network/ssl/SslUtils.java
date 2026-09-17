package com.chua.common.support.network.ssl;

import com.chua.common.support.network.server.ServerSetting;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * SSL/TLS 工具类，统一 KeyStore 加载、SSLContext 创建、自签名证书生成等逻辑。
 *
 * <p>消除 {@code JdkHttpServer}、{@code NioHttpServer}、{@code NettyHttpServer}
 * 中的 SSL 代码重复，提供统一的入口方法。</p>
 *
 * <h2>典型用法</h2>
 * <pre>
 *   // 一键 SSL（推荐）
 *   SSLContext ctx = SslUtils.autoSsl(ssl);
 *   if (ctx != null) { // HTTPS } else { // HTTP }
 *
 *   // Netty 场景：仅预处理，自行创建 SslContext
 *   if (SslUtils.autoPrepare(ssl)) {
 *       sslContext = createSslContext(ssl);  // Netty SslContextBuilder
 *   }
 *
 *   // 低级 API：手动分步调用
 *   if (SslUtils.isSslEnabled(ssl)) {
 *       SslUtils.prepareSslConfig(ssl);
 *       SSLContext ctx = SslUtils.createSslContext(ssl);
 *   }
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
public final class SslUtils {

    /** 创建 SslUtils 实例 */
    private SslUtils() {
    }

    // ==================== 一键 SSL ====================

    /**
    * 一键 SSL：判断是否启用 → 预处理配置 → 创建 {@link SSLContext}。
    *
    * <p>将 {@link #isSslEnabled} + {@link #prepareSslConfig} + {@link #createSslContext}
    * 合并为单次调用，适用于 JDK HttpsServer / NioHttpServer 等使用
    * {@code javax.net.ssl.SSLContext} 的场景。</p>
    *
    * <h3>典型用法</h3>
    * <pre>
    *   SSLContext ctx = SslUtils.autoSsl(ssl);
    *   if (ctx != null) {
    *       // 启用 HTTPS
    *   } else {
    *       // 普通 HTTP
    *   }
    * </pre>
    *
    * @param ssl SSL 配置，可为 null
    * @return 已初始化的 SSLContext；未启用 SSL 时返回 null
    * @throws RuntimeException SSL 配置或证书加载失败
    */
    public static SSLContext autoSsl(ServerSetting.SslConfig ssl) {
        if (!isSslEnabled(ssl)) {
            return null;
        }
        try {
            prepareSslConfig(ssl);
            return createSslContext(ssl);
        } catch (Exception e) {
            throw new RuntimeException("自动 SSL 配置失败", e);
        }
    }

    /**
    * 一键预处理：判断 SSL 是否启用 → 预处理配置。
    *
    * <p>适用于需要自行创建 SSL 上下文的场景（如 Netty 使用
    * {@code io.netty.handler.ssl.SslContext}），仅需判断和预处理，
    * 不负责创建最终的 SSL 上下文。</p>
    *
    * @param ssl SSL 配置，可为 null
    * @return true 表示 SSL 已启用且配置已预处理完成
    */
    public static boolean autoPrepare(ServerSetting.SslConfig ssl) {
        if (!isSslEnabled(ssl)) {
            return false;
        }
        prepareSslConfig(ssl);
        return true;
    }

    // ==================== 配置预处理 ====================

    /**
    * 判断 SSL 是否需要启用（考虑 selfSignedAuto）。
    *
    * @param ssl SSL 配置，可为 null
    * @return true 表示应启用 SSL
    */
    public static boolean isSslEnabled(ServerSetting.SslConfig ssl) {
        return ssl != null && (ssl.isEnabled() || ssl.isSelfSignedAuto());
    }

    /**
    * 预处理 SslConfig：当 {@code selfSignedAuto=true} 时自动设置
    * {@code enabled=true} + {@code selfSigned=true}。
    *
    * <p>应在 SSL 初始化之前调用。</p>
    *
    * @param ssl SSL 配置，可为 null
    */
    public static void prepareSslConfig(ServerSetting.SslConfig ssl) {
        if (ssl != null && ssl.isSelfSignedAuto()) {
            ssl.setEnabled(true);
            ssl.setSelfSigned(true);
        }
    }

    // ==================== KeyStore 加载 ====================

    /**
    * 加载 KeyStore，按优先级尝试：KeyStore 文件 → PEM 证书 → 自签名证书自动生成。
    *
    * @param ssl SSL 配置
    * @return 已加载的 KeyStore
    * @throws Exception 加载失败
    * @throws IllegalArgumentException SSL 已启用但未配置任何证书来源
    */
    public static KeyStore loadKeyStore(ServerSetting.SslConfig ssl) throws Exception {
        if (ssl.getKeyStorePath() != null) {
            return loadKeyStoreFile(ssl);
        }
        if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
            return loadPemKeyStore(ssl);
        }
        if (ssl.isSelfSigned()) {
            return generateSelfSignedKeyStore(ssl);
        }
        throw new IllegalArgumentException("SSL 已启用但未配置 KeyStore 或 Cert/Key 文件");
    }

    /**
    * 从 KeyStore 文件（JKS/PKCS12）加载。
    */
    private static KeyStore loadKeyStoreFile(ServerSetting.SslConfig ssl) throws Exception {
        String type = ssl.getKeyStorePath().toLowerCase().endsWith(".p12") ? "PKCS12" : "JKS";
        KeyStore ks = KeyStore.getInstance(type);
        char[] password = getKeyStorePassword(ssl);
        try (FileInputStream in = new FileInputStream(ssl.getKeyStorePath())) {
            ks.load(in, password);
        }
        return ks;
    }

    /**
    * 从 PEM 证书文件和私钥文件加载为 KeyStore。
    */
    private static KeyStore loadPemKeyStore(ServerSetting.SslConfig ssl) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (FileInputStream in = new FileInputStream(ssl.getCertPath())) {
            cert = cf.generateCertificate(in);
        }

        byte[] keyBytes = Files.readAllBytes(Path.of(ssl.getKeyPath()));
        String keyContent = new String(keyBytes, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        java.security.PrivateKey privateKey = kf.generatePrivate(spec);

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null);
        ks.setKeyEntry("server", privateKey, getKeyPassword(ssl), new Certificate[]{cert});
        return ks;
    }

    /**
    * 使用 {@link JdkCertificateProvider} 自动生成自签名证书并加载为 KeyStore。
    */
    private static KeyStore generateSelfSignedKeyStore(ServerSetting.SslConfig ssl) throws Exception {
        JdkCertificateProvider provider = new JdkCertificateProvider();
        provider.setKeyAlg(ssl.getSelfSignedKeyAlg());
        provider.setKeySize(ssl.getSelfSignedKeySize());
        provider.setValidityDays(ssl.getSelfSignedValidity());
        provider.setKeystoreType("PKCS12");
        provider.setKeystorePassword(ssl.getKeyStorePassword() != null ? ssl.getKeyStorePassword() : "changeit");

        AcmeConnectionResult connResult = provider.connect(null, null, null);
        if (!connResult.isSuccess()) {
            throw new RuntimeException("自签名证书提供者初始化失败: " + connResult.getError());
        }

        try {
            List<String> domains = ssl.getSelfSignedDomains();
            if (domains == null || domains.isEmpty()) {
                domains = List.of("localhost", "127.0.0.1");
            }

            AcmeCertificateResult certResult = provider.requestCertificate(domains, null);
            if (!certResult.isSuccess()) {
                throw new RuntimeException("自签名证书生成失败: " + certResult.getError());
            }

            log.info("自签名证书生成成功，域名: {}，有效期: {} ~ {}",
                    certResult.getPrimaryDomain(), certResult.getNotBefore(), certResult.getNotAfter());

            String keystoreFile = provider.getKeystorePath(domains.getFirst());
            String keystorePassword = ssl.getKeyStorePassword() != null ? ssl.getKeyStorePassword() : "changeit";
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(keystoreFile)) {
                ks.load(in, keystorePassword.toCharArray());
            }
            return ks;
        } finally {
            provider.close();
        }
    }

    // ==================== SSLContext / KeyManagerFactory ====================

    /**
    * 创建 {@link SSLContext}（TLS 协议）。
    *
    * <p>内部调用 {@link #loadKeyStore} 和 {@link #createKeyManagerFactory}，
    * 适用于 JDK HttpsServer / NioHttpServer 等场景。</p>
    *
    * @param ssl SSL 配置
    * @return 已初始化的 SSLContext
    * @throws Exception 创建失败
    */
    public static SSLContext createSslContext(ServerSetting.SslConfig ssl) throws Exception {
        KeyManagerFactory kmf = createKeyManagerFactory(ssl);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /**
    * 创建 {@link KeyManagerFactory}，从 SslConfig 加载 KeyStore 后初始化。
    *
    * <p>适用于 Netty {@code SslContextBuilder.forServer(kmf).build()} 等场景。</p>
    *
    * @param ssl SSL 配置
    * @return 已初始化的 KeyManagerFactory
    * @throws Exception 创建失败
    */
    public static KeyManagerFactory createKeyManagerFactory(ServerSetting.SslConfig ssl) throws Exception {
        KeyStore ks = loadKeyStore(ssl);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, getKeyStorePassword(ssl));
        return kmf;
    }

    // ==================== 密码工具 ====================

    /**
    * 获取 KeyStore 密码字符数组。
    *
    * <p>未设置时返回默认密码 {@code "changeit"}，
    * 与 {@link #generateSelfSignedKeyStore} 生成自签名 KeyStore 时使用的默认密码保持一致，
    * 避免加载后 KeyManagerFactory 解密密钥条目失败（BadPaddingException）。</p>
    *
    * @return 密码字符数组
    */
    public static char[] getKeyStorePassword(ServerSetting.SslConfig ssl) {
        return ssl.getKeyStorePassword() != null
                ? ssl.getKeyStorePassword().toCharArray() : "changeit".toCharArray();
    }

    /**
    * 获取私钥密码字符数组。
    *
    * @return 密码字符数组，未设置时返回空数组
    */
    public static char[] getKeyPassword(ServerSetting.SslConfig ssl) {
        return ssl.getKeyPassword() != null
                ? ssl.getKeyPassword().toCharArray() : new char[0];
    }
}
