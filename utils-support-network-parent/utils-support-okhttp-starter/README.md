# utils-support-okhttp-starter

OkHttp3 HTTP 客户端执行器 SPI 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-okhttp-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `OkHttpExecutor` | 基于 OkHttp3 的 HTTP 客户端执行器 (SPI: `okhttp`) |
| `OkHttpSseClient` | 基于 OkHttp3 的 SSE 客户端实现 使用 OkHttp3 建立 HTTP 连接，通过响应体的 逐行读取 SSE 事件流， 解析 前缀并回调 。 (SPI: `okhttp`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-okhttp-starter
├── utils-support-common-starter
```