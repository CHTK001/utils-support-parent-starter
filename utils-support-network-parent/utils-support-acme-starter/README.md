# utils-support-acme-starter

> utils 体系**网络协议集成**（`utils-support-network-parent`）下的 ACME 证书自动化模块：基于 [acme4j](https://github.com/shredzone/acme4j) 封装 RFC 8555 ACME 客户端，对接 Let's Encrypt、ZeroSSL 等兼容 CA，提供账户连接、域名验证信息、证书申请/续签/恢复/吊销能力，实现定义在 `utils-support-common-starter` 的 `AcmeProvider` SPI。
>
> 版本：`4.0.0.51`｜JDK：`25`（顶层 parent `maven.compiler.source/target=25`，含 `--enable-preview`）｜包名：`com.chua.acme.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 工作流程与验证方式](#4-工作流程与验证方式)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-acme-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | acme4j 3.5.1 的封装适配，实现 `com.chua.common.support.network.ssl.AcmeProvider` SPI | 不是证书库、密钥库，也不是 ACME 服务端 |
| 协议 | RFC 8555 ACME 客户端（Let's Encrypt、ZeroSSL 等兼容 CA） | 不实现 ACME 服务器 |
| 验证 | 产出 HTTP-01 / DNS-01 验证所需的信息（token、路径、DNS 记录名/值） | 不替调用方部署 HTTP 验证文件或写入 DNS TXT 记录 |
| 形态 | SPI 实现（`acme4j`）+ Spring `@Component` 双入口 | 无自动配置类、无 `spring.factories`、无配置属性键 |

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.acme.support.Acme4jProvider`（`@since 4.0.0.42`，类版本 `1.1.0`）。
- SPI 扩展声明 **1** 个：`META-INF/extensions/com.chua.common.support.network.ssl.AcmeProvider`。
- 内置 GraalVM `native-image` 配置三件套（`reflect-config.json` / `proxy-config.json` / `resource-config.json`）。

---

## 2. 核心 API 与速查表

本模块唯一实现类 `Acme4jProvider` 实现 `AcmeProvider` 接口。接口契约与三个结果模型（`AcmeConnectionResult`、`AcmeCertificateResult`、`AcmeValidationInfo`）都定义在 `utils-support-common-starter` 的 `com.chua.common.support.network.ssl` 包中。

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 获取实现 | `ServiceProvider.of(AcmeProvider.class).getExtension("acme4j")` | SPI 名称 `acme4j`；`getExtension` 返回 SPI 容器缓存的共享实例，需独立实例时用 `getNewExtension("acme4j")` 每次新建 |
| 连接 / 注册账户 | `connect(serverUrl, email, privateKeyPem, eabKid, eabHmacKey)` | 私钥为空则新生成（RSA 2048）并注册；返回账户 URL 与需持久化的账户私钥 PEM |
| 拿域名验证信息 | `getValidationInfo(domains, challengeType)` | 返回 `orderUrl`、`token`、HTTP 路径/内容或 DNS 记录名/值；失败返回空列表 |
| 申请证书 | `requestCertificate(domains, challengeType)` | 触发验证、提交 CSR、下载完整证书链（PEM）与私钥（PKCS#8 PEM） |
| 续签证书 | `renewCertificate(domains, challengeType)` | 基于同一账户重新走完整下单流程 |
| 恢复历史订单 | `resumeCertificate(orderUrl, domains, challengeType)` | 跨请求续办（如人工部署验证后重新验证），需先 `connect` |
| 吊销证书 | `revokeCertificate(certificatePem)` | 需先 `connect`；以 `UNSPECIFIED` 原因吊销，返回是否成功 |
| 释放会话 | `close()` | 清空 session / login / account / 订单 / 密钥等全部状态 |

### 2.2 方法签名（与源码一致）

```java
public interface AcmeProvider {
    // 3 参重载为 default，等价于 connect(serverUrl, email, privateKeyPem, null, null)
    AcmeConnectionResult connect(String serverUrl, String email, String privateKeyPem,
                                 String eabKid, String eabHmacKey);
    void setTimeout(java.time.Duration timeout);                       // 默认 no-op，acme4j 实现
    List<AcmeValidationInfo> getValidationInfo(List<String> domains, String challengeType);
    AcmeCertificateResult requestCertificate(List<String> domains, String challengeType);
    AcmeCertificateResult renewCertificate(List<String> domains, String challengeType);
    // 接口默认返回 fail("当前 ACME 提供者不支持恢复历史订单")；acme4j 实现已覆盖
    AcmeCertificateResult resumeCertificate(String orderUrl, List<String> domains, String challengeType);
    boolean revokeCertificate(String certificatePem);
    String getAccountPrivateKeyPem();
    void close();
}
```

### 2.3 结果模型字段

| 类型 | 字段 |
| --- | --- |
| `AcmeConnectionResult` | `success`、`accountUrl`、`privateKeyPem`（账户私钥 PEM，首次注册后必须持久化）、`error` |
| `AcmeCertificateResult` | `success`、`certificatePem`（含链）、`privateKeyPem`、`primaryDomain`、`san`（逗号分隔）、`notBefore`、`notAfter`（`LocalDateTime`，系统时区）、`error`、`needsValidation`、`validationInfos` |
| `AcmeValidationInfo`（record） | `orderUrl`、`domain`、`challengeType`、`token`、`httpPath`、`httpContent`、`dnsName`、`dnsValue` |

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-acme-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 完整申请流程（HTTP-01）

```java
import com.chua.common.support.network.ssl.*;
import com.chua.common.support.spi.ServiceProvider;

// 1. 通过 SPI 获取实现（getExtension 返回 SPI 容器缓存的共享实例；并发/隔离场景用 getNewExtension("acme4j") 每次新建）
AcmeProvider acme = ServiceProvider.of(AcmeProvider.class).getExtension("acme4j");

// 2. 连接 CA：已保存的账户私钥传入可复用账户；首次为 null 则注册新账户
//    ZeroSSL 等需要 EAB 时，第 4、5 参传入 eabKid / eabHmacKey
AcmeConnectionResult conn = acme.connect(
        "https://acme-staging-v02.api.letsencrypt.org/directory",  // 示例：Let's Encrypt 测试环境 directory
        "admin@example.com",
        savedAccountKeyPem,     // 首次传 null
        null, null);
if (!conn.isSuccess()) {
    throw new IllegalStateException(conn.getError());
}
// 首次注册后必须持久化账户私钥，下次连接传入即可复用同一账户
String accountKeyPem = conn.getPrivateKeyPem();   // 等价于 acme.getAccountPrivateKeyPem()

// 3. 获取 HTTP-01 验证信息
List<AcmeValidationInfo> infos = acme.getValidationInfo(List.of("example.com"), "HTTP-01");
for (AcmeValidationInfo info : infos) {
    // info.orderUrl()    订单地址：跨请求恢复时必须保存
    // info.httpPath()    /.well-known/acme-challenge/{token}
    // info.httpContent() 该路径需要返回的内容
    deployHttpChallenge(info.httpPath(), info.httpContent());   // 由调用方部署
}

// 4. 验证部署完成后申请证书：复用当前实例时内部沿用同一订单，不重复下单
AcmeCertificateResult cert = acme.requestCertificate(List.of("example.com"), "HTTP-01");
if (cert.isSuccess()) {
    String chainPem = cert.getCertificatePem();   // 叶子证书 + 中间证书的完整 PEM 链
    String keyPem   = cert.getPrivateKeyPem();    // 与 CSR 同一把私钥，PKCS#8 PEM
    LocalDateTime notAfter = cert.getNotAfter();
} else {
    // cert.getError() 携带失败原因（验证未通过 / 订单超时 / 不支持该验证方式等）
}

// 5. 释放会话状态
acme.close();
```

### 3.3 DNS-01 验证

```java
List<AcmeValidationInfo> infos = acme.getValidationInfo(List.of("example.com"), "DNS-01");
for (AcmeValidationInfo info : infos) {
    // info.dnsName()  形如 _acme-challenge.example.com 的 TXT 记录名
    // info.dnsValue() 需要写入的 TXT 记录值（digest）
    addTxtRecord(info.dnsName(), info.dnsValue());   // 由调用方写入 DNS
}
AcmeCertificateResult cert = acme.requestCertificate(List.of("example.com"), "DNS-01");
```

### 3.4 跨请求恢复订单（人工部署验证后重新验证）

```java
// 新请求中：先 connect，再凭首次保存的订单地址恢复并继续签发
AcmeCertificateResult cert = acme.resumeCertificate(savedOrderUrl, List.of("example.com"), "HTTP-01");
```

> 若不提前调用 `getValidationInfo`，直接调用 `requestCertificate` 时会内部完成下单，适用于授权仍有效或验证已由外部完成的场景。

---

## 4. 工作流程与验证方式

`Acme4jProvider` 的一次申请流程（与源码注释一致）：

```text
connect          建立账户会话（复用已保存账户私钥，支持 EAB 外部账户绑定）
   ↓
getValidationInfo 创建订单 + 生成域名密钥对，返回 HTTP-01/DNS-01 验证信息
   ↓
（调用方完成验证部署：HTTP 路径内容 或 DNS TXT 记录）
   ↓
requestCertificate 复用同一订单：触发挑战并等待 CA 验证（≤90s）→ 提交 CSR → 等待签发（≤90s）→ 下载完整证书链
```

| 行为 | 事实 |
| --- | --- |
| 挑战类型 | `"HTTP-01"` 与 `"DNS-01"`（大小写不敏感）；传入其它值一律按 HTTP-01 处理 |
| 授权复用 | 状态已 `VALID` 的授权（通常 30 天内重复申请）跳过验证 |
| 账户私钥 | 优先加载已保存 PEM；为空、历史占位值（不含 `-----BEGIN`）或解析失败时重新生成 RSA 2048 |
| EAB | `eabKid` 与 `eabHmacKey` **同时非空**才执行外部账户绑定 |
| 续签语义 | `renewCertificate` 即基于同一账户重新走完整下单流程；模块内**没有**到期检测或定时续期任务 |
| 实例语义 | 单次操作对象：订单状态仅在同一次申请流程内复用；跨请求必须用 `resumeCertificate(orderUrl, ...)` |
| 吊销前提 | 未 `connect` 时直接失败并返回 `false`；证书链 PEM 取首个 `CERTIFICATE` 块解析 |
| 网络超时 | acme4j 默认 10s，本实现放宽到 60s（公网 CA 慢握手容忍），可被 `setTimeout` 覆盖 |

---

## 5. 配置项

**无 Spring 配置属性键。** 依据：`src/main/resources` 下仅有 `META-INF/extensions/` 与 `META-INF/native-image/` 共 4 个文件，不存在 `application*.properties/yml`、`spring.factories` 或 `@ConfigurationProperties` 类；引入依赖即可使用。

运行期可调项（编程式）：

| 项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 请求超时 | `setTimeout(Duration)` | 60s | 网络请求超时；传 `null` / 零 / 负数时忽略，保持默认 |
| 验证/签发等待 | — | 90s | 等待 CA 域名验证与订单签发的超时，源码常量 `POLL_TIMEOUT_SECONDS`，不可配置 |
| 账户密钥长度 | — | 2048 | RSA，源码常量 `ACCOUNT_KEY_SIZE`，不可配置 |
| 挑战类型 | 方法参数 | HTTP-01 | `"HTTP-01"` / `"DNS-01"`，大小写不敏感 |

---

## 6. SPI 扩展点

- **契约在底层**：SPI 接口 `com.chua.common.support.network.ssl.AcmeProvider` 定义于 `utils-support-common-starter`；本模块只提供实现，符合「共享契约下沉到底层」的分层规则。
- **本模块注册的实现**：`META-INF/extensions/com.chua.common.support.network.ssl.AcmeProvider` 中声明 `acme4j=com.chua.acme.support.Acme4jProvider`。
- **获取方式**：`ServiceProvider.of(AcmeProvider.class).getExtension("acme4j")`，也可 `getNewExtension("acme4j")` 每次取新实例。`getExtension` 返回 SPI 容器缓存的共享实例（provider 按 SPI 类型缓存，实例首次获取时创建并复用）；`Acme4jProvider` 内部持有会话/订单/密钥等流程状态，并发执行多个申请流程时应使用 `getNewExtension` 获取独立实例。
- **Spring 入口**：`Acme4jProvider` 标注了 `@Component`；宿主若扫描 `com.chua.acme.support` 包，该类同时会成为 Spring Bean（本模块无自动配置导入文件，不会自动装配）。
- **扩展新 CA**：实现 `AcmeProvider` 接口，并在自己模块的 `src/main/resources/META-INF/extensions/com.chua.common.support.network.ssl.AcmeProvider` 中登记新名称即可被 `ServiceProvider` 发现；接口的 `connect(3参)`、`setTimeout`、`resumeCertificate` 均有 default 实现，可按需覆盖。

---

## 7. 构建与依赖

模块位于 `utils-support-parent-starter/utils-support-network-parent/utils-support-acme-starter`，父模块为 `utils-support-network-parent`（网络协议集成父模块）。

```text
utils-support-acme-starter
├── com.chua:utils-support-common-starter        # AcmeProvider SPI 契约、结果模型、Wraps/StringUtils 等工具
├── org.shredzone.acme4j:acme4j-client:3.5.1     # RFC 8555 ACME 客户端
├── org.springframework:spring-context           # @Component 注解支持
└── org.projectlombok:lombok (provided)          # @Slf4j
```

编译随顶层 parent 使用 **JDK 25** 并开启 `--enable-preview`。GraalVM native-image 配置：

| 文件 | 内容 |
| --- | --- |
| `reflect-config.json` | `com.chua.acme.support.Acme4jProvider` 的构造器/方法/字段全部开放反射 |
| `proxy-config.json` | 为 `com.chua.common.support.network.ssl.AcmeProvider` 接口生成动态代理 |
| `resource-config.json` | 包含 `META-INF/services/.*` 与 `META-INF/extensions/.*`，保证 SPI 声明进入镜像 |

---

## 8. 边界：这些能力不在本模块

- **SPI 契约与结果模型**：`AcmeProvider`、`AcmeConnectionResult`、`AcmeCertificateResult`、`AcmeValidationInfo` 定义在 `utils-support-common-starter`，本模块只是实现方。
- **验证部署**：HTTP-01 的路径内容发布、DNS-01 的 TXT 记录写入，均由调用方完成；本模块只产出验证信息并等待 CA 校验。
- **证书存储与定时续期**：证书/私钥的持久化、到期检测、自动续期调度不在本模块；`renewCertificate` 只是重新下单，调用方需自行编排续期时机。
- **JDK 自签名证书**：`AcmeProvider` 接口注释提及「真实 ACME 服务器和 JDK 自签名证书两种模式」，但本模块只实现 acme4j 一种，不包含 JDK 自签名实现。
- **ACME 服务端**：本模块是纯客户端，不提供 CA/服务端功能。
- **其它 TLS 能力**：TLS 密钥日志解析（`TlsKeyLogDecoder`）等位于 `utils-support-common-starter` 的 `network.ssl` 包，不属于本模块。
