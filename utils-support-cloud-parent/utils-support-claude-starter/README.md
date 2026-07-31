# utils-support-claude-starter

Claude AI 集成模块：Anthropic Claude 大模型对话

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-claude-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ClaudeChatClient` | Claude 大模型对话客户端 基于 Anthropic Claude Messages API 的 实现，通过 HTTP 协议 调用 Claude 模型的对话 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-claude-starter
├── utils-support-common-starter
```