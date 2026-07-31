# utils-support-payment-starter

支付SPI抽象模块：支付渠道、请求/响应、链式Builder

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-payment-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `PaymentChannel` | 支付渠道 SPI 接口 定义统一的支付渠道契约，支持支付、查询、关闭、退款等核心操作。 |
| `PayException` | 支付异常 |
| `PaymentRequest` | 支付请求 通过 Builder 模式构建，支持链式调用。 |
| `PaymentResponse` | 支付响应 |
| `Scene` | 支付场景 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-payment-starter
├── utils-support-common-starter
```