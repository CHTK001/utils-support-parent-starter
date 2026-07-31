# utils-support-zai-starter

Z.AI 大模型集成模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zai-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ZaiImageClient` | ZAI 平台图片生成客户端 基于 ZAI 平台 OpenAI 兼容 API 的 实现， 通过 HTTP 协议调用 ZAI 的图片生成接口，兼容 OpenAI D |
| `ZaiChatClient` | Z.AI 大模型对话客户端 基于 Z.AI OpenAPI 的 实现，通过 HTTP 协议 调用 Z.AI 平台的对话接口，支持 Z.AI 系列模型。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-zai-starter
├── utils-support-common-starter
```