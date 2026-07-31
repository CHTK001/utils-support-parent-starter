# utils-support-remote-rustdesk-agent-starter

RustDesk Agent 独立模块 — 通过进程包裹方式管理 RustDesk 的完整生命周期

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-remote-rustdesk-agent-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `RustDeskAgentContext` | RustDesk Agent 与宿主环境之间的最小接口。 |
| `RustDeskAgentService` | RustDesk Agent 服务 — 通过进程包裹方式管理 RustDesk 的完整生命周期。 |
| `RustDeskProperties` | RustDesk 集成配置属性。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-remote-rustdesk-agent-starter
├── utils-support-common-starter
```