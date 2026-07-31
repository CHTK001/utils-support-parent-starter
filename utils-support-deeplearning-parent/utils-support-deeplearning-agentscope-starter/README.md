# utils-support-deeplearning-agentscope-starter

基于 AgentScope 多智能体编排风格的 Agent 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>utils-support-deeplearning-agentscope-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AgentHookAdapter` | 将项目 / 桥接为 AgentScope 。 |
| `AgentScopeAgent` | AgentScope 实现：将项目通用 桥接到 AgentScope Harness， 支持多 Agent 编排、思考上限配置与链式调用。 |
| `ChatClientModelAdapter` | 将项目通用 适配为 AgentScope 的 接口。 |
| `CompressionAwareModel` | Compression-aware Model decorator. 统一走 （与轻量 ChatClient 同一入口/算法）。 |
| `CompressionChatClientModelAdapter` | 将压缩专用 适配为 AgentScope 的 接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-agentscope-starter
├── utils-support-common-starter
```