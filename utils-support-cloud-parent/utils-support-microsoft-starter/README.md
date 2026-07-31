# utils-support-microsoft-starter

微软云集成模块：Microsoft Graph、Azure OpenAI 大模型

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-microsoft-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MicrosoftImageClient` | Azure OpenAI DALL-E 图片生成客户端 基于 Azure OpenAI Service Images generation API 的 实现，  |
| `MicrosoftChatClient` | Azure OpenAI 大模型对话客户端 基于 Azure OpenAI Service API 的 实现，通过 HTTP 协议 调用 Azure OpenA |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-microsoft-starter
├── utils-support-common-starter
```