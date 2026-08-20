package com.chua.alibaba.cas.support;

import com.aliyun.cas20200407.Client;
import com.aliyun.cas20200407.models.CreateCertificateForPackageRequestRequest;
import com.aliyun.cas20200407.models.CreateCertificateForPackageRequestResponse;
import com.aliyun.cas20200407.models.DescribeCertificateStateRequest;
import com.aliyun.cas20200407.models.DescribeCertificateStateResponse;
import com.aliyun.cas20200407.models.DescribeCertificateStateResponseBody;
import com.aliyun.cas20200407.models.GetCertificateDetailRequest;
import com.aliyun.cas20200407.models.GetCertificateDetailResponse;
import com.aliyun.cas20200407.models.GetCertificateDetailResponseBody;
import com.aliyun.cas20200407.models.ApplyCertificateRequest;
import com.aliyun.cas20200407.models.ApplyCertificateResponse;
import com.aliyun.cas20200407.models.RenewCertificateOrderForPackageRequestRequest;
import com.aliyun.cas20200407.models.RenewCertificateOrderForPackageRequestResponse;
import com.aliyun.cas20200407.models.RevokeCertificateRequest;
import com.aliyun.cas20200407.models.RevokeCertificateResponse;
import com.aliyun.teaopenapi.models.Config;
import com.chua.common.support.network.ssl.AcmeCertificateResult;
import com.chua.common.support.network.ssl.AcmeConnectionResult;
import com.chua.common.support.network.ssl.AcmeProvider;
import com.chua.common.support.network.ssl.AcmeValidationInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阿里云数字证书管理服务（CAS 2020-04-07）的 AcmeProvider SPI 实现。
 *
 * <p>将阿里云 OpenAPI 客户端封装为统一证书接口：
 * <ul>
 *   <li>{@link #connect} 使用阿里云 AccessKey + Region 创建 CAS 客户端</li>
 *   <li>{@link #getValidationInfo} 走 {@code CreateCertificateForPackageRequest} 下单，
 *       再 {@code DescribeCertificateState} 拉取 DNS/HTTP 验证记录</li>
 *   <li>{@link #requestCertificate} 走 {@code ApplyCertificate} 触发签发并拉取证书详情</li>
 *   <li>{@link #renewCertificate} 走 {@code RenewCertificateOrderForPackageRequest}</li>
 *   <li>{@link #revokeCertificate} 走 {@code RevokeCertificate}</li>
 *   <li>{@link #getAccountPrivateKeyPem} 阿里云账号体系无 EAB 私钥，固定返回 null</li>
 * </ul>
 *
 * <p>注意：CAS SDK 通过 {@code provided} 方式引入，由用户在运行时自行加依赖。</p>
 *
 * <p>连接参数映射：
 * <ul>
 *   <li>{@code serverUrl} → CAS Region（如 cn-hangzhou）</li>
 *   <li>{@code email} → 联系人邮箱</li>
 *   <li>{@code privateKeyPem} → AccessKeyId</li>
 *   <li>{@code eabKid} → AccessKeySecret</li>
 *   <li>{@code eabHmacKey} → productCode（证书产品编码，例如 digicert-free-1y）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
@Slf4j
@Component
public class AlibabaCasProvider implements AcmeProvider {

    /**
     * 阿里云 CAS 客户端
     */
    private Client client;
    /**
     * 联系邮箱
     */
    private String email;
    /**
     * 证书产品编码
     */
    private String productCode;
    /**
     * 缓存 domain → orderId 的映射，避免重复下单
     */
    private final Map<String, Long> orderIdCache = new ConcurrentHashMap<>();
    /**
     * 缓存 orderId → instanceId 的映射（CAS 签发需要 instanceId）
     */
    private final Map<Long, String> instanceIdCache = new ConcurrentHashMap<>();
    /**
     * 缓存 orderId → csr（续签用）
     */
    private final Map<Long, String> csrCache = new ConcurrentHashMap<>();

    /**
     * 连接阿里云 CAS。
     *
     * <p>参数语义：{@code serverUrl} 当作 RegionId，{@code privateKeyPem} 当作 AccessKeyId，
     * {@code eabKid} 当作 AccessKeySecret，{@code eabHmacKey} 当作 productCode。</p>
     *
     * @param serverUrl CAS 区域（如 cn-hangzhou）
     * @param email 联系邮箱（同时用作联系人 username）
     * @param privateKeyPem 阿里云 AccessKeyId
     * @param eabKid AccessKeySecret
     * @param eabHmacKey 证书产品编码（productCode），如 digicert-free-1y
     * @return 连接结果
     */
    @Override
    public AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                        String eabKid, String eabHmacKey) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) {
                return AcmeConnectionResult.fail("RegionId 不能为空");
            }
            if (privateKeyPem == null || privateKeyPem.isEmpty()) {
                return AcmeConnectionResult.fail("AccessKeyId 不能为空");
            }
            if (eabKid == null || eabKid.isEmpty()) {
                return AcmeConnectionResult.fail("AccessKeySecret 不能为空");
            }
            Config config = new Config()
                    .setAccessKeyId(privateKeyPem)
                    .setAccessKeySecret(eabKid)
                    .setType("access_key")
                    .setRegionId(serverUrl)
                    .setEndpoint("cas." + serverUrl + ".aliyuncs.com");
            client = new Client(config);
            this.email = email;
            this.productCode = (eabHmacKey == null || eabHmacKey.isEmpty())
                    ? "digicert-free-1y" : eabHmacKey;
            String accountUrl = "cas." + serverUrl + ".aliyuncs.com#ak=" + privateKeyPem;
            log.info("阿里云 CAS 连接成功: region={}, productCode={}", serverUrl, this.productCode);
            return AcmeConnectionResult.success(accountUrl, null);
        } catch (Exception e) {
            log.error("阿里云 CAS 连接失败", e);
            return AcmeConnectionResult.fail(e.getMessage());
        }
    }

    /**
     * 获取域名验证信息：内部走"下单 → 拉验证记录"。
     *
     * <p>仅取 {@code domains[0]} 作为下单主域名，其它域名通过 CSR 的 SAN 字段（CAS 自动从 csr 解析）。
     * 如需 SAN 扩展，请在业务层构造带 SAN 的 CSR，并通过 {@code challengeType} 传入 CSR PEM。
     * （SPI 未透传 CSR，本实现按单域处理。）</p>
     *
     * @param domains 域名
     * @param challengeType HTTP-01 / DNS-01，可选
     * @return 验证信息列表
     */
    @Override
    public List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType) {
        if (client == null) {
            log.warn("未连接阿里云 CAS");
            return Collections.emptyList();
        }
        if (domains == null || domains.isEmpty()) {
            log.warn("域名列表为空");
            return Collections.emptyList();
        }
        String domain = domains.get(0);
        try {
            Long orderId = createOrderId(domain, null);
            DescribeCertificateStateRequest request = new DescribeCertificateStateRequest();
            request.setOrderId(orderId);
            DescribeCertificateStateResponse response = client.describeCertificateState(request);
            DescribeCertificateStateResponseBody body = response.getBody();
            if (body == null) {
                log.warn("DescribeCertificateState 返回空");
                return Collections.emptyList();
            }
            AcmeValidationInfo info = new AcmeValidationInfo();
            info.setDomain(domain);
            info.setChallengeType(toChallengeType(body.getValidateType(), challengeType));
            String recordType = body.getRecordType();
            String recordDomain = body.getRecordDomain();
            String recordValue = body.getRecordValue();
            String uri = body.getUri();
            String content = body.getContent();
            if (recordType != null && !recordType.isEmpty()) {
                info.setDnsName(recordDomain);
                info.setDnsValue(recordValue);
            }
            if (uri != null && !uri.isEmpty()) {
                info.setHttpPath(uri);
            }
            if (content != null && !content.isEmpty()) {
                info.setHttpContent(content);
            }
            if (body.getCertId() != null) {
                info.setToken(body.getCertId());
            }
            List<AcmeValidationInfo> result = new ArrayList<>();
            result.add(info);
            return result;
        } catch (Exception e) {
            log.error("获取验证信息失败: domain={}", domain, e);
            return Collections.emptyList();
        }
    }

    /**
     * 申请证书：触发签发并返回证书详情。
     *
     * <p>若 {@code challengeType} 为 PEM 格式的 CSR（含 "-----BEGIN CERTIFICATE REQUEST-----"），
     * 视为 CSR PEM 传入订单；否则视为 challengeType 字符串，下单时 CSR 字段为空。</p>
     *
     * @param domains 域名
     * @param challengeType CSR PEM 或 challengeType 字符串
     * @return 证书结果
     */
    @Override
    public AcmeCertificateResult requestCertificate(List<String> domains, String challengeType) {
        if (client == null) {
            return AcmeCertificateResult.fail("未连接阿里云 CAS");
        }
        if (domains == null || domains.isEmpty()) {
            return AcmeCertificateResult.fail("域名列表为空");
        }
        String domain = domains.get(0);
        String csr = isCsrPem(challengeType) ? challengeType : null;
        try {
            Long orderId = createOrderId(domain, csr);
            String instanceId = queryInstanceId(orderId);
            ApplyCertificateRequest applyRequest = new ApplyCertificateRequest();
            applyRequest.setInstanceId(instanceId);
            ApplyCertificateResponse applyResponse = client.applyCertificate(applyRequest);
            if (applyResponse == null || applyResponse.getBody() == null) {
                return AcmeCertificateResult.fail("ApplyCertificate 返回空");
            }
            log.info("ApplyCertificate 触发签发: orderId={}, instanceId={}, requestId={}",
                    orderId, instanceId, applyResponse.getBody().getRequestId());
            GetCertificateDetailResponse detailResponse = pollCertificateDetail(orderId);
            if (detailResponse == null || detailResponse.getBody() == null) {
                return AcmeCertificateResult.needValidation(
                        getValidationInfo(domains, challengeType));
            }
            GetCertificateDetailResponseBody body = detailResponse.getBody();
            String certPem = joinCertificateChain(body);
            String san = body.getSubjectAlternativeNames() == null
                    ? domain
                    : String.join(",", body.getSubjectAlternativeNames());
            LocalDateTime notBefore = body.getNotBefore() == null ? null
                    : LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(body.getNotBefore()),
                            ZoneId.systemDefault());
            LocalDateTime notAfter = body.getNotAfter() == null ? null
                    : LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(body.getNotAfter()),
                            ZoneId.systemDefault());
            return AcmeCertificateResult.success(
                    certPem,
                    "",
                    body.getCommonName() == null ? domain : body.getCommonName(),
                    san,
                    notBefore,
                    notAfter);
        } catch (Exception e) {
            log.error("证书申请失败: domain={}", domain, e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    /**
     * 续签证书：必须传入 CSR PEM 作为 {@code challengeType}。
     *
     * <p>{@code domains} 用于选择要续签的订单；同一 domain 会优先使用 {@link #orderIdCache}。</p>
     *
     * @param domains 域名
     * @param challengeType CSR PEM
     * @return 证书结果
     */
    @Override
    public AcmeCertificateResult renewCertificate(List<String> domains, String challengeType) {
        if (client == null) {
            return AcmeCertificateResult.fail("未连接阿里云 CAS");
        }
        if (domains == null || domains.isEmpty()) {
            return AcmeCertificateResult.fail("域名列表为空");
        }
        if (!isCsrPem(challengeType)) {
            return AcmeCertificateResult.fail("续签需要传入 CSR PEM 作为 challengeType");
        }
        String domain = domains.get(0);
        try {
            Long orderId = createOrderId(domain, challengeType);
            csrCache.put(orderId, challengeType);
            return requestCertificate(domains, challengeType);
        } catch (Exception e) {
            log.error("证书续签失败: domain={}", domain, e);
            return AcmeCertificateResult.fail(e.getMessage());
        }
    }

    /**
     * 吊销证书：参数为阿里云侧证书 ID（{@code certId}）而非 PEM 内容。
     *
     * <p>接口约定为 PEM，但 CAS 只能通过 certificateId+instanceId 吊销；调用方请在调用本方法前
     * 自行维护 certificateId/instanceId → 证书 PEM 的映射（或扩展 AcmeProvider 接口）。</p>
     *
     * @param certificatePem 证书 PEM（当前未使用，仅占位）
     * @return 是否成功
     */
    @Override
    public boolean revokeCertificate(String certificatePem) {
        if (client == null) {
            log.warn("未连接阿里云 CAS");
            return false;
        }
        log.warn("CAS 吊销需 certificateId + instanceId，请使用 revokeCertificateWithId 方法替代");
        return false;
    }

    /**
     * 通过 certificateId + instanceId 吊销。
     *
     * @param certificateId 阿里云证书 ID
     * @param instanceId 实例 ID
     * @return 是否成功
     */
    public boolean revokeCertificateWithId(Long certificateId, String instanceId) {
        if (client == null) {
            return false;
        }
        try {
            RevokeCertificateRequest request = new RevokeCertificateRequest();
            request.setCertificateId(certificateId);
            request.setInstanceId(instanceId);
            RevokeCertificateResponse response = client.revokeCertificate(request);
            log.info("证书吊销成功: certificateId={}, requestId={}",
                    certificateId,
                    response == null || response.getBody() == null
                            ? null : response.getBody().getRequestId());
            return true;
        } catch (Exception e) {
            log.error("证书吊销失败", e);
            return false;
        }
    }

    /**
     * 阿里云账号体系无 EAB 私钥。
     *
     * @return 固定返回 null
     */
    @Override
    public String getAccountPrivateKeyPem() {
        return null;
    }

    @Override
    public void close() {
        orderIdCache.clear();
        instanceIdCache.clear();
        csrCache.clear();
        client = null;
        email = null;
        productCode = null;
    }

    /** 创建订单并返回 orderId（缓存避免重复下单） */
    private Long createOrderId(String domain, String csr) throws Exception {
        return orderIdCache.computeIfAbsent(domain + "|" + (csr == null ? "" : csr), key -> {
            try {
                CreateCertificateForPackageRequestRequest req =
                        new CreateCertificateForPackageRequestRequest();
                req.setDomain(domain);
                req.setProductCode(productCode);
                req.setValidateType("DNS");
                req.setEmail(email);
                req.setUsername(email);
                if (csr != null) {
                    req.setCsr(csr);
                }
                CreateCertificateForPackageRequestResponse resp =
                        client.createCertificateForPackageRequest(req);
                if (resp == null || resp.getBody() == null || resp.getBody().getOrderId() == null) {
                    throw new IllegalStateException("CreateCertificateForPackageRequest 返回空 orderId");
                }
                Long orderId = resp.getBody().getOrderId();
                log.info("阿里云 CAS 下单成功: domain={}, orderId={}", domain, orderId);
                return orderId;
            } catch (Exception e) {
                throw new RuntimeException("下单失败: " + e.getMessage(), e);
            }
        });
    }

    /** 查询 instanceId（首次申请后立即可用） */
    private String queryInstanceId(Long orderId) throws Exception {
        if (orderId == null) {
            return null;
        }
        String cached = instanceIdCache.get(orderId);
        if (cached != null) {
            return cached;
        }
        DescribeCertificateStateRequest request = new DescribeCertificateStateRequest();
        request.setOrderId(orderId);
        DescribeCertificateStateResponse response = client.describeCertificateState(request);
        DescribeCertificateStateResponseBody body = response.getBody();
        if (body == null) {
            return null;
        }
        DescribeCertificateStateRequest again = new DescribeCertificateStateRequest();
        again.setOrderId(orderId);
        DescribeCertificateStateResponse retry = client.describeCertificateState(again);
        DescribeCertificateStateResponseBody retryBody = retry.getBody();
        String instanceId = retryBody == null ? null : retryBody.getCertId();
        if (instanceId != null) {
            instanceIdCache.put(orderId, instanceId);
        }
        return instanceId;
    }

    /** 轮询证书详情（最多 60 次，每次 5 秒） */
    private GetCertificateDetailResponse pollCertificateDetail(Long orderId) throws Exception {
        int maxAttempts = 60;
        while (maxAttempts-- > 0) {
            DescribeCertificateStateRequest req = new DescribeCertificateStateRequest();
            req.setOrderId(orderId);
            DescribeCertificateStateResponse resp = client.describeCertificateState(req);
            DescribeCertificateStateResponseBody body = resp.getBody();
            if (body == null) {
                Thread.sleep(5000);
                continue;
            }
            String certId = body.getCertId();
            String certificate = body.getCertificate();
            if (certId == null && certificate == null) {
                Thread.sleep(5000);
                continue;
            }
            if (certId == null) {
                Thread.sleep(5000);
                continue;
            }
            GetCertificateDetailRequest detailReq = new GetCertificateDetailRequest();
            detailReq.setCertificateId(Long.parseLong(certId));
            GetCertificateDetailResponse detailResp = client.getCertificateDetail(detailReq);
            if (detailResp != null && detailResp.getBody() != null) {
                return detailResp;
            }
            Thread.sleep(5000);
        }
        return null;
    }

    /** 拼接证书链为 PEM */
    private String joinCertificateChain(GetCertificateDetailResponseBody body) {
        StringBuilder sb = new StringBuilder();
        String leaf = body.getCertIdentifier();
        if (leaf != null && !leaf.isEmpty()) {
            sb.append(leaf);
            if (!leaf.endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (body.getCertificateChainList() != null) {
            for (GetCertificateDetailResponseBody.GetCertificateDetailResponseBodyCertificateChainList c
                    : body.getCertificateChainList()) {
                if (c == null || c.getSubject() == null) {
                    continue;
                }
                sb.append(c.getSubject());
                if (!c.getSubject().endsWith("\n")) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 把 CAS validateType 映射为 SPI 的 challengeType */
    private String toChallengeType(String validateType, String fallback) {
        if (validateType == null || validateType.isEmpty()) {
            return fallback == null ? "DNS-01" : fallback;
        }
        switch (validateType.toUpperCase()) {
            case "DNS":
                return "DNS-01";
            case "FILE":
                return "HTTP-01";
            default:
                return validateType;
        }
    }

    /** 判断 challengeType 是否为 CSR PEM */
    private boolean isCsrPem(String s) {
        return s != null && s.contains("-----BEGIN CERTIFICATE REQUEST-----");
    }
}