package com.chua.alibaba.support.cas;

import com.chua.common.support.network.ssl.AcmeCertificateResult;
import com.chua.common.support.network.ssl.AcmeConnectionResult;
import com.chua.common.support.network.ssl.AcmeValidationInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AlibabaCasProvider 契约测试（main 风格，兼容工程测试约定）。
 *
 * <p>覆盖以下契约：
 * <ul>
 *   <li>connect 在参数缺失时返回 fail，不抛异常</li>
 *   <li>未连接时调用 getValidationInfo 返回空列表</li>
 *   <li>未连接时调用 requestCertificate 返回 fail</li>
 *   <li>未连接时调用 revokeCertificate 返回 false</li>
 *   <li>getAccountPrivateKeyPem 始终返回 null（CAS 无 EAB）</li>
 *   <li>close 后状态被清理（幂等）</li>
 * </ul>
 *
 * <p>真实 API 路径（创建订单/触发签发等）需要阿里云 AccessKey 才能跑，
 * 应作为集成测试单独写在 integration-test 目录，本测试只验证契约。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class AlibabaCasProviderTest {

    public static void main(String[] args) {
        AlibabaCasProvider provider = new AlibabaCasProvider();
        Result r = new Result();

        run(r, "connect 缺 RegionId 应返回 fail", () -> {
            AcmeConnectionResult res = provider.connect(null, "user@example.com", "akId", "akSecret", "digicert-free-1y");
            check(!res.isSuccess() && res.getError().contains("RegionId"),
                    "expected fail RegionId, got: " + res.getError());
        });

        run(r, "connect 缺 AccessKeyId 应返回 fail", () -> {
            AcmeConnectionResult res = provider.connect("cn-hangzhou", "user@example.com", null, "akSecret", "digicert-free-1y");
            check(!res.isSuccess() && res.getError().contains("AccessKeyId"),
                    "expected fail AccessKeyId, got: " + res.getError());
        });

        run(r, "connect 缺 AccessKeySecret 应返回 fail", () -> {
            AcmeConnectionResult res = provider.connect("cn-hangzhou", "user@example.com", "akId", null, "digicert-free-1y");
            check(!res.isSuccess() && res.getError().contains("AccessKeySecret"),
                    "expected fail AccessKeySecret, got: " + res.getError());
        });

        run(r, "未连接时 getValidationInfo 返回空列表", () -> {
            List<AcmeValidationInfo> info = provider.getValidationInfo(Arrays.asList("example.com"), "DNS-01");
            check(info != null && info.isEmpty(), "expected empty list, got: " + info);
        });

        run(r, "未连接时 getValidationInfo 空域名返回空列表", () -> {
            List<AcmeValidationInfo> info = provider.getValidationInfo(Collections.emptyList(), "DNS-01");
            check(info != null && info.isEmpty(), "expected empty list, got: " + info);
        });

        run(r, "未连接时 requestCertificate 返回 fail", () -> {
            AcmeCertificateResult res = provider.requestCertificate(Arrays.asList("example.com"), "DNS-01");
            check(!res.isSuccess() && res.getError().contains("未连接"),
                    "expected fail 未连接, got: " + res.getError());
        });

        run(r, "未连接时 requestCertificate 空域名返回 fail", () -> {
            AcmeCertificateResult res = provider.requestCertificate(Collections.emptyList(), "DNS-01");
            check(!res.isSuccess(), "expected fail, got success");
            check(res.getError() != null && (res.getError().contains("域名") || res.getError().contains("未连接")),
                    "expected 域名/未连接, got: " + res.getError());
        });

        run(r, "未连接时 renewCertificate 缺 CSR 返回 fail", () -> {
            AcmeCertificateResult res = provider.renewCertificate(Arrays.asList("example.com"), "DNS-01");
            check(!res.isSuccess(), "expected fail, got success");
            check(res.getError() != null && (res.getError().contains("CSR") || res.getError().contains("未连接")),
                    "expected CSR/未连接, got: " + res.getError());
        });

        run(r, "未连接时 revokeCertificate 返回 false", () -> {
            boolean ok = provider.revokeCertificate("-----BEGIN CERTIFICATE-----\nxxx\n-----END CERTIFICATE-----");
            check(!ok, "expected false");
        });

        run(r, "getAccountPrivateKeyPem 始终返回 null（CAS 无 EAB）", () -> {
            String pem = provider.getAccountPrivateKeyPem();
            check(pem == null, "expected null, got: " + pem);
        });

        run(r, "close 幂等且不抛异常", () -> {
            provider.close();
            provider.close();
        });

        System.out.println();
        System.out.println("===== 测试汇总 =====");
        System.out.println("Passed: " + r.passed + " / Failed: " + r.failed);
        if (r.failed > 0) {
            System.err.println("FAILED");
            System.exit(1);
        } else {
            System.out.println("ALL PASSED");
        }
    }

    /** 简单断言辅助 */
    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    /** 执行测试用例，通过/失败累加到 result。 */
    private static void run(Result result, String name, Runnable body) {
        try {
            body.run();
            System.out.println("[PASS] " + name);
            result.passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] " + name + " -> " + t.getMessage());
            result.failed++;
        }
    }

    /** 闭包替代品 */
    private static class Result {
        int passed;
        int failed;
    }
}