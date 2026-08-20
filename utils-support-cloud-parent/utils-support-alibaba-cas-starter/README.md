# Utils Support Alibaba CAS Starter

阿里云 **数字证书管理服务（CAS）**（原 SSL 证书服务）的 AcmeProvider SPI 实现，作为 `utils-support-acme-starter`（基于 ACME4J 连接 Let's Encrypt）之外的第二个 `AcmeProvider` 实现，让现有 SSL 证书自动化申请业务可一键切换到阿里云账号体系。

## 模块说明

| 项 | 值 |
|----|----|
| 父模块 | `utils-support-cloud-parent` |
| 阿里云产品 | [数字证书管理服务（原 SSL 证书）](https://help.aliyun.com/zh/ssl-certificate) |
| OpenAPI 版本 | `2020-04-07` |
| Java SDK | `com.aliyun:cas20200407:3.6.0`（**provided 范围**） |
| SDK 风格 | Tea OpenAPI（基于 `com.aliyun:tea-openapi`、`tea-util`、`tea`、`endpoint-util`、`openapiutil`，同样 **provided**） |
| 业务接口 | `com.chua.common.support.network.ssl.AcmeProvider` |

## 为什么用 provided 范围

CAS SDK（332 KB）+ Tea 运行时（合计约 100 KB）属于阿里云 OpenAPI 体系产物，本身已 shading 了 okhttp/okio/openssl/gson/bouncycastle 等三方库。**provided** 模式：

- 本模块可在 `mvn -o` 离线模式下编译通过；
- 发布 jar 仅 10.5 KB，**不包含** CAS SDK 与 Tea 运行时；
- 用户（业务集成方）需自行在运行期提供：

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>cas20200407</artifactId>
    <version>3.6.0</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>tea-openapi</artifactId>
    <version>0.3.12</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>tea-util</artifactId>
    <version>0.2.26</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>tea</artifactId>
    <version>1.4.1</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>endpoint-util</artifactId>
    <version>0.0.8</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>openapiutil</artifactId>
    <version>0.2.2</version>
</dependency>
```

## 业务流程（CAS vs ACME）

| 步骤 | ACME（Let's Encrypt） | 阿里云 CAS（本实现） |
|------|----------------------|---------------------|
| 1. 账号 | `new Session + AccountBuilder`（EAB） | `new Client(Config)`（AccessKeyId/Secret） |
| 2. 下单 | `account.newOrder().domains(...)` | `createCertificateForPackageRequest(productCode, domain, validateType, csr, ...)` |
| 3. 验证记录 | `order.getAuthorizations()` 遍历 challenge | `describeCertificateState(orderId)` 直接返回 `recordDomain/recordValue/recordType/uri/content` |
| 4. 触发签发 | `order.execute(csr)` | `applyCertificate(instanceId)` |
| 5. 拿证书 | `order.getCertificate()` | 轮询 `describeCertificateState` → `getCertificateDetail(certificateId)` |
| 6. 续签 | 新一轮 `newOrder` | `renewCertificateOrderForPackageRequest(orderId, csr)` |
| 7. 吊销 | `certificate.revoke()` | `revokeCertificate(certificateId, instanceId)` |

## SPI 参数映射

`AcmeProvider.connect(serverUrl, email, privateKeyPem, eabKid, eabHmacKey)` 在 CAS 实现中等价于：

| SPI 参数 | CAS 等价 | 示例 |
|----------|---------|------|
| `serverUrl` | RegionId | `cn-hangzhou` |
| `email` | 联系人邮箱 | `admin@example.com` |
| `privateKeyPem` | AccessKeyId | `LTAI5txxxxxxxxxxxx` |
| `eabKid` | AccessKeySecret | `Kxxxxxxxxxxxxxxxxxxx` |
| `eabHmacKey` | productCode（证书产品编码） | `digicert-free-1y`（默认） |

## AcmeProvider 接口实现说明

| SPI 方法 | CAS 实现要点 |
|---------|-------------|
| `connect` | 构造 `com.aliyun.teaopenapi.models.Config`（setAccessKeyId/setAccessKeySecret/setType("access_key")/setRegionId/setEndpoint），新建 `com.aliyun.cas20200407.Client` |
| `getValidationInfo(domains, challengeType)` | 内部走 `createCertificateForPackageRequest` 下单 + `describeCertificateState` 拉验证记录。仅取 `domains[0]` 作为下单主域名。 |
| `requestCertificate(domains, challengeType)` | 若 `challengeType` 是 PEM（含 `-----BEGIN CERTIFICATE REQUEST-----`）则视为 CSR 传入；调用 `applyCertificate(instanceId)` 触发签发；轮询 `describeCertificateState` + `getCertificateDetail` 拿证书详情。 |
| `renewCertificate(domains, challengeType)` | 必须传 CSR PEM；走 `renewCertificateOrderForPackageRequest(orderId, csr)` 续签。 |
| `revokeCertificate(certificatePem)` | CAS 需要 `certificateId` + `instanceId`，SPI 签名仅有 PEM 不够，**固定返回 false**，请改用扩展方法 `revokeCertificateWithId(Long, String)`。 |
| `getAccountPrivateKeyPem` | 阿里云账号体系无 EAB 私钥，**固定返回 null**。 |
| `close` | 清空缓存 + 置空 client。 |

## 证书状态机（CAS）

```
CreateCertificateForPackageRequest → orderId
        │
        ▼
DescribeCertificateState(orderId)
        │  返回 recordDomain/recordType/recordValue（DNS）或 uri/content（HTTP）
        ▼
  用户部署验证记录
        │
        ▼
ApplyCertificate(instanceId)        ← 触发签发
        │
        ▼
DescribeCertificateState(orderId)  ← 轮询等 certId
        │
        ▼
GetCertificateDetail(certificateId) ← 拿证书链 + certIdentifier(PEM) + notBefore/notAfter
```

## 关于免费证书

阿里云 CAS 提供 **Digicert DV 单域名证书**1 张免费体验（1 年有效期），productCode 通常为 `digicert-free-1y`。免费证书可走完整流程（申请 → DNS 验证 → 签发 → 下载）。其他品牌（GlobalSign、Wosign、CFCA、Entrust）均为付费套餐，需先在阿里云控制台购买实例（`InstanceId`），下单时通过 `productCode` 指定。

## ACME 链式调用说明

ACME 协议本身不限定 CA 是否收费，`AcmeProvider` 是 SPI：

- 现有 `Acme4jProvider` 仅连 Let's Encrypt（免费）
- `AlibabaCasProvider`（本模块）走阿里云账号，可申请免费/付费证书
- 业务层通过 `@Autowired List<AcmeProvider> providers` 注入所有实现，按 `provider.getClass().getSimpleName()` 或配置键选择

## 依赖引入

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-alibaba-cas-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

## 版本兼容性

| 依赖 | 版本 | 来源 |
|------|------|------|
| `com.aliyun:cas20200407` | 3.6.0 | Maven Central（CAS 2020-04-07 最新稳定版） |
| `com.aliyun:tea-openapi` | 0.3.12 | 同 SDK 依赖 |
| `com.aliyun:tea-util` | 0.2.26 | 同 SDK 依赖 |
| `com.aliyun:tea` | 1.4.1 | 同 SDK 依赖 |
| `com.aliyun:endpoint-util` | 0.0.8 | 同 SDK 依赖 |
| `com.aliyun:openapiutil` | 0.2.2 | 同 SDK 依赖 |

## 已知限制

- SPI 的 `revokeCertificate(certificatePem)` 与 CAS 的"certificateId + instanceId"接口不直接对应；本模块扩展提供 `revokeCertificateWithId(Long, String)`，业务层需自行维护 cert PEM → ID 映射（或扩展 `AcmeProvider` 接口）。
- 多域名场景（`domains` 含多个）：CAS `CreateCertificateForPackageRequest.domain` 字段只支持单域名；如需 SAN，应将多个域名放入 CSR 的 SAN 字段，本模块已将 `domains[0]` 作为主域名，其余作 CSR SAN（业务层生成 CSR 时实现）。
- 验证记录 `DescribeCertificateState` 是**同步查询**接口；用户部署验证记录后需重新调用 `applyCertificate` 才能触发签发。

@author CH
@since 4.0.0.42
@version 1.0.0