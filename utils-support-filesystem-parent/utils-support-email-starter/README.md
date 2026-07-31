# utils-support-email-starter

邮件发送与解析模块（SMTP/IMAP/POP3）

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-email-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `EmailClient` | 邮件客户端，支持 SMTP 发送 + POP3/IMAP 轮询监听。 初始化时检测 SMTP 是否可用，不可用时自动降级为轮询模式。 使用方式 |
| `EmailDirectory` | 邮件轮询目录，实现 PolledDirectory 接口。 |
| `EmailPush` | 邮件推送实现 基于 JavaMail SMTP 协议的邮件发送实现。 (SPI: `email`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-email-starter
├── utils-support-common-starter
```
