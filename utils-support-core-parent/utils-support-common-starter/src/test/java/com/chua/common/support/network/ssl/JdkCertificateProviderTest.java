package com.chua.common.support.network.ssl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * JDK 证书生成器的参数数组化验证：keytool 必须收到完整参数，域名载荷不得被 shell 执行。
 *
 * <p>直接 {@code main()} 运行，不依赖 JUnit。</p>
 */
public class JdkCertificateProviderTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        JdkCertificateProvider provider = new JdkCertificateProvider();
        // 与 SslUtils 的默认值一致：keytool 要求口令至少 6 位，留空无法建库
        provider.setKeystorePassword("changeit");
        check("connect 初始化成功", provider.connect(null, null, null, null, null).isSuccess());

        AcmeCertificateResult result = provider.requestCertificate(List.of("qoder.test"), "http-01");
        check("自签名证书生成成功: " + result.getError(), result.isSuccess());
        check("PEM 证书非空", result.getCertificatePem() != null
                && result.getCertificatePem().contains("BEGIN CERTIFICATE"));

        if (result.isSuccess()) {
            X509Certificate cert = parse(result.getCertificatePem());
            check("-dname 的 CN 落入证书主体",
                    cert.getSubjectX500Principal().getName().contains("qoder.test"));
            check("-ext san 扩展被 keytool 接受", hasSan(cert, "qoder.test"));
        }

        Path marker = Paths.get(System.getProperty("java.io.tmpdir"), "qoder-keytool-marker.txt");
        Files.deleteIfExists(marker);
        // 闭合并重开引号：拼接进 cmd.exe 的旧实现会在此落 marker
        String payload = "a\" & echo pwned > \"" + marker + "\" & \"";
        provider.requestCertificate(List.of(payload), "http-01");
        check("域名中的 shell 载荷未被执行", !Files.exists(marker));
        Files.deleteIfExists(marker);

        provider.close();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static X509Certificate parse(String pem) throws Exception {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean hasSan(X509Certificate cert, String domain) throws Exception {
        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        return names != null && names.stream()
                .anyMatch(entry -> entry.size() > 1 && domain.equals(entry.get(1)));
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
