# utils-support-dingding-starter

钉钉集成模块：钉钉 AI 对话、消息推送

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-dingding-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DingTalkBotClient` | 钉钉 Bot 客户端，实现 接口。 支持 Webhook 模式（接收消息通过回调）和发送消息（文本、图片等）。 |
| `DingTalkBotClientFactory` | 钉钉 Bot 客户端工厂（SPI 实现）。 通过 SPI 机制注册到 ， 平台名称为 。 |
| `DingdingChatClient` | 钉钉 AI 大模型对话客户端 基于钉钉 AI API 的 实现，通过钉钉开放平台的 AI 对话接口 调用钉钉 AI 助理能力。 |
| `DingdingPush` | 钉钉消息推送实现 基于钉钉机器人 Webhook 的消息发送实现，支持文本和 Markdown 格式。 (SPI: `dingding`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-dingding-starter
├── utils-support-common-starter
```