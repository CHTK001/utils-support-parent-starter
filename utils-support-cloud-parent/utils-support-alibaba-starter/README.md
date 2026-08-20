# utils-support-alibaba-starter

阿里云集成模块：OSS、短信、通义千问大模型、CAS 数字证书管理

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-alibaba-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AlibabaChatClient` | 阿里云通义千问大模型对话客户端 基于 DashScope 通义千问 API 的 实现，通过 HTTP 协议 调用阿里云模型服务灵积（DashScope）的对话接 |
| `AlipayLoginProvider` | 支付宝登录渠道实现 基于 alipay-sdk-java 的 实现授权码登录。 支持小程序授权码登录、H5/APP 授权登录。 (SPI: `alipay`) |
| `AlibabaImageClient` | 阿里云通义万相图片生成客户端 基于 DashScope 通义万相 API 的 实现，通过 HTTP 协议 调用通义万相（Wanx）系列模型的图片生成接口。 |
| `AlipayConfig` | 支付宝配置 |
| `AlipayProvider` | 支付宝支付渠道实现 (SPI: `alipay`) |
| `AlibabaSmsPush` | 阿里云短信推送实现 基于阿里云 Dysmsapi SDK 的短信发送实现。 |
| `AliYunFileStorage` | 阿里云 OSS 文件存储实现。 基于阿里云 OSS SDK 实现 SPI 接口，提供对象存储的上传、下载、删除、列表等操作。 (SPI: `oss`) |
| `AlibabaVoiceCall` | 阿里云语音电话实现（基于 dysmsapi Tea SDK） 通过阿里云 dysmsapi20170525 SDK 的 Tea-OpenAPI 框架调用语音服务 |
| `AlibabaCasProvider` | 阿里云数字证书管理服务（CAS）证书申请/续签/吊销 AcmeProvider SPI 实现 (SPI: `cas`)，详见下方「CAS 数字证书」 |

---

## CAS 数字证书

阿里云 **数字证书管理服务（CAS）**（原 SSL 证书服务）的 AcmeProvider SPI 实现，作为 `utils-support-acme-starter`（基于 ACME4J 连接 Let's Encrypt）之外的第二个 `AcmeProvider` 实现，让现有 SSL 证书自动化申请业务可一键切换到阿里云账号体系。

| 项 | 值 |
|----|----|
| OpenAPI 版本 | `2020-04-07` |
| Java SDK | `com.aliyun:cas20200407:3.6.0`（**provided 范围**） |
| SDK 风格 | Tea OpenAPI（`tea-openapi`、`tea-util`、`tea`、`endpoint-util`、`openapiutil`，同样 **provided**） |
| 业务接口 | `com.chua.common.support.network.ssl.AcmeProvider` |

### provided 依赖说明

CAS SDK（332 KB）+ Tea 运行时（合计约 100 KB）属于阿里云 OpenAPI 体系产物，本身已 shading 了 okhttp/okio/openssl/gson/bouncycastle 等三方库。**provided** 模式下本模块发布 jar 不包含这些 SDK，用户（业务集成方）需在运行期自行提供：

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

### SPI 参数映射

`AcmeProvider.connect(serverUrl, email, privateKeyPem, eabKid, eabHmacKey)` 在 CAS 实现中等价于：

| SPI 参数 | CAS 等价 | 示例 |
|----------|---------|------|
| `serverUrl` | RegionId | `cn-hangzhou` |
| `email` | 联系人邮箱 | `admin@example.com` |
| `privateKeyPem` | AccessKeyId | `LTAI5txxxxxxxxxxxx` |
| `eabKid` | AccessKeySecret | `Kxxxxxxxxxxxxxxxxxxx` |
| `eabHmacKey` | productCode（证书产品编码） | `digicert-free-1y`（默认） |

### AcmeProvider 方法实现

| SPI 方法 | CAS 实现要点 |
|---------|-------------|
| `connect` | 构造 `com.aliyun.teaopenapi.models.Config`（setAccessKeyId/setAccessKeySecret/setType("access_key")/setRegionId/setEndpoint），新建 `com.aliyun.cas20200407.Client` |
| `getValidationInfo(domains, challengeType)` | 内部走 `createCertificateForPackageRequest` 下单 + `describeCertificateState` 拉验证记录。仅取 `domains[0]` 作为下单主域名。 |
| `requestCertificate(domains, challengeType)` | 若 `challengeType` 是 PEM（含 `-----BEGIN CERTIFICATE REQUEST-----`）则视为 CSR 传入；调用 `applyCertificate(instanceId)` 触发签发；轮询 `describeCertificateState` + `getCertificateDetail` 拿证书详情。 |
| `renewCertificate(domains, challengeType)` | 必须传 CSR PEM；走 `renewCertificateOrderForPackageRequest(orderId, csr)` 续签。 |
| `revokeCertificate(certificatePem)` | CAS 需要 `certificateId` + `instanceId`，SPI 签名仅有 PEM 不够，**固定返回 false**，请改用扩展方法 `revokeCertificateWithId(Long, String)`。 |
| `getAccountPrivateKeyPem` | 阿里云账号体系无 EAB 私钥，**固定返回 null**。 |
| `close` | 清空缓存 + 置空 client。 |

### 证书状态机（CAS）

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

### 关于免费证书

阿里云 CAS 提供 **Digicert DV 单域名证书** 1 张免费体验（1 年有效期），productCode 通常为 `digicert-free-1y`。免费证书可走完整流程（申请 → DNS 验证 → 签发 → 下载）。其他品牌（GlobalSign、Wosign、CFCA、Entrust）均为付费套餐，需先在阿里云控制台购买实例（`InstanceId`），下单时通过 `productCode` 指定。

### 已知限制

- SPI 的 `revokeCertificate(certificatePem)` 与 CAS 的"certificateId + instanceId"接口不直接对应；本实现扩展提供 `revokeCertificateWithId(Long, String)`，业务层需自行维护 cert PEM → ID 映射（或扩展 `AcmeProvider` 接口）。
- 多域名场景（`domains` 含多个）：CAS `CreateCertificateForPackageRequest.domain` 字段只支持单域名；如需 SAN，应将多个域名放入 CSR 的 SAN 字段，本实现已将 `domains[0]` 作为主域名，其余作 CSR SAN（业务层生成 CSR 时实现）。
- 验证记录 `DescribeCertificateState` 是**同步查询**接口；用户部署验证记录后需重新调用 `applyCertificate` 才能触发签发。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-alibaba-starter
├── utils-support-common-starter
├── utils-support-network-starter
├── utils-support-payment-starter
├── utils-support-auth-starter
```