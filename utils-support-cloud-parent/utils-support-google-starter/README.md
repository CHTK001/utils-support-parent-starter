# utils-support-google-starter

谷歌云集成模块：Cloud Storage、Cloud Vision、Gemini 大模型

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-google-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `GoogleChatClient` | Google Gemini 大模型对话客户端 基于 Google Gemini API 的 实现，通过 HTTP 协议 调用 Gemini 系列模型的对话接口， |
| `GoogleImageClient` | Google Imagen 图片生成客户端 基于 Google Vertex AI Imagen 或 Gemini API 的 实现， 通过 HTTP 协议调用 |
| `GoogleCloudFileStorage` | Google Cloud Storage 文件存储实现。 基于 Google Cloud Storage SDK 实现 SPI 接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-google-starter
├── utils-support-common-starter
```