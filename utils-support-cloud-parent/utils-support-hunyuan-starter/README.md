# utils-support-hunyuan-starter

腾讯混元大模型集成模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hunyuan-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `HunyuanImageClient` | 腾讯混元生图图片生成客户端 基于腾讯混元大模型生图 API 的 实现，通过 HTTP 协议 调用混元生图接口。 |
| `FeishuPush` | 飞书消息推送实现 基于飞书机器人 Webhook 的消息发送实现，支持文本和富文本格式。 (SPI: `feishu`) |
| `TencentSmsPush` | 腾讯云短信推送实现 基于腾讯云 SMS SDK（com.tencentcloudapi.sms.v20190711）的短信发送实现。 |
| `TencentCosFileStorage` | 腾讯云 COS（Cloud Object Storage）文件存储实现。 |
| `TencentHunyuanChatClient` | 腾讯混元大模型对话客户端 基于腾讯混元（Hunyuan）大模型 API 的 实现，通过 HTTP 协议 调用腾讯混元的对话接口，支持混元 Pro、Standar |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-hunyuan-starter
├── utils-support-common-starter
```