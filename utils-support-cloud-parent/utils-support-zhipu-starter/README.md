# utils-support-zhipu-starter

智谱 GLM 大模型集成模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zhipu-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ZhipuImageClient` | 智谱 CogView 图片生成客户端 基于智谱 CogView API 的 实现，通过 HTTP 协议 调用 CogView 系列模型的图片生成接口，兼容 Op |
| `ZhipuVideoClient` | ZhipuVideoClient |
| `ZhipuChatClient` | 智谱 GLM 大模型对话客户端 基于智谱 AI 开放平台 GLM API 的 实现，通过 HTTP 协议 调用智谱 GLM-4 系列模型的对话接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-zhipu-starter
├── utils-support-common-starter
```