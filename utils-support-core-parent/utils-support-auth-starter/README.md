# utils-support-auth-starter

登录SPI抽象模块：登录渠道、请求/响应、链式Builder

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-auth-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `LoginChannel` | 登录渠道 SPI 接口 定义统一的登录渠道契约，支持授权码登录、扫码登录、账号密码登录等。 |
| `LoginException` | 登录异常 |
| `LoginRequest` | 登录请求 通过 Builder 模式构建，支持链式调用。 |
| `LoginResponse` | 登录响应 |
| `LoginScene` | 登录场景 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-auth-starter
├── utils-support-common-starter
```