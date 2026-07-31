# utils-support-acme-starter

ACME 协议支持模块，提供 SSL/TLS 证书自动化管理功能。
        主要功能：
        - SSL 证书申请：支持 Let's Encrypt 等 ACME 服务
        - 证书自动续期：自动检测和续期即将过期的证书
        - 证书管理：证书的创建、验证、吊销操作
        - 域名验证：支持 HTTP-01 和 DNS-01 验证方式
        - 证书存储：证书和私钥的安全存储管理
        基于 ACME4J 库实现，符合 RFC 8555 ACME 协议标准。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.shredzone.acme4j</groupId>
    <artifactId>utils-support-acme-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `Acme4jProvider` | 基于 ACME4J 的 ACME 提供者实现 @version 1.0.0 |
| `AcmeCertificateResult` | ACME 证书结果 @version 1.0.0 |
| `AcmeConnectionResult` | ACME 连接结果 @version 1.0.0 |
| `AcmeProvider` | ACME 证书服务提供者接口 @version 1.0.0 |
| `AcmeValidationInfo` | ACME 域名验证信息 @version 1.0.0 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-acme-starter
└── (无内部依赖)
```