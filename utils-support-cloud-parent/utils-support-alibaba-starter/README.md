# utils-support-alibaba-starter

阿里云集成模块：OSS、短信、通义千问大模型

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