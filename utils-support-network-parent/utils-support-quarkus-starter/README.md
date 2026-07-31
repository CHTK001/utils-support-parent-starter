# utils-support-quarkus-starter

Quarkus HTTP 服务器实现（基于 Vert.x）

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-quarkus-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `QuarkusHttpServer` | 基于 Vert.x 的 Quarkus 风格 HTTP 服务器实现，支持同步阻塞和响应式两种模式。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-quarkus-starter
├── utils-support-common-starter
```