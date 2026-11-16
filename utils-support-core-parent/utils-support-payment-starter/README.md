# utils-support-payment-starter

> utils 体系的**支付 SPI 抽象层**：支付渠道契约、统一请求/响应模型、链式 Builder、渠道 SPI 工厂；具体支付渠道（如 `alipay`、`tenpay`）由实现方按渠道名称通过 SPI 注册接入。pom 描述原文：「支付SPI抽象模块：支付渠道、请求/响应、链式Builder」。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 pom `maven.compiler.source/target=25`）｜包名：`com.chua.payment.support`、`com.chua.payment.spi`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 代码地图](#2-代码地图)
- [3. 核心 API 与速查表](#3-核心-api-与速查表)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-payment-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 支付 SPI 抽象层：渠道契约、请求/响应模型、链式 Builder | 不绑定任何具体支付渠道（支付宝、微信等） |
| 契约 | `PaymentChannel` 定义 `pay` / `query` / `close` / `refund` 统一操作口径 | 不实现具体渠道的下单、签名、验签 |
| 扩展 | 渠道实现通过 SPI 按名称注册（接口注释示例：`"alipay"`、`"tenpay"`），调用方用工厂方法获取实例 | 不内置任何渠道实现类 |
| 依赖 | 仅依赖 `utils-support-common-starter` 与 lombok（provided） | 不依赖 Spring、不依赖任何支付 SDK |
| 配置 | 零配置，引入依赖即可使用 | 无 Spring Boot 自动配置、无配置文件 |

**判断口径**：本模块只定契约与数据模型（`pom.xml` 中 `<name>` 为 `Utils Support Payment Starter`）；所有渠道对接逻辑落在各渠道实现方，通过 SPI 注册进 `PaymentChannel`。

---

## 2. 代码地图

源码共 **5 个类型**，分两个包：

| 包 | 类型 | 职责 |
| --- | --- | --- |
| `com.chua.payment.support` | `PaymentRequest` | 支付请求，Builder 模式构建，支持链式调用（`@since 4.0.0.42`） |
| `com.chua.payment.support` | `PaymentResponse` | 支付响应（`@since 4.0.0.42`） |
| `com.chua.payment.support` | `Scene` | 支付场景枚举（`@since 4.0.0.42`） |
| `com.chua.payment.support` | `PayException` | 支付异常，运行时异常（`@since 4.0.0.42`） |
| `com.chua.payment.spi` | `PaymentChannel` | 支付渠道 SPI 接口（`@since 4.0.0.42`） |

模块同时内置 GraalVM native-image 反射配置：`src/main/resources/META-INF/native-image/com.chua/utils-support-payment-starter/reflect-config.json`。

---

## 3. 核心 API 与速查表

### 3.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发起支付 | `PaymentChannel.getChannel("alipay").pay(request)` | 按渠道名取实例并支付（接口 Javadoc 内链式示例） |
| 获取渠道实例 | `PaymentChannel.getChannel(String name)` | 静态工厂：`ServiceProvider.of(PaymentChannel.class).getExtension(name)`；未注册对应实现时返回 `null` |
| 构建支付请求 | `PaymentRequest.builder()...build()` | lombok `@Builder` + `@Data`，全字段链式 |
| 指定支付场景 | `PaymentRequest.scene(Scene.APP)` | 见 `Scene` 枚举：APP / MINI_APP / H5 / BAR_CODE / NATIVE / JSAPI |
| 读取支付结果 | `PaymentResponse` | `success`、`tradeNo`、`outTradeNo`、`codeUrl`、`appAuthUrl`、`rawResponse`、`extParams` |
| 查询/关闭/退款 | `PaymentChannel.query / close / refund` | 接口 default 方法，**默认抛 `UnsupportedOperationException`**，需实现类覆盖 |
| 抛出支付异常 | `new PayException(code, message)` | 运行时异常，`getCode()` 取业务编码 |
| 新增渠道 | 实现 `PaymentChannel` + SPI 注册 | 见「6. SPI 扩展点」 |

### 3.2 PaymentRequest 字段速查

lombok：`@Data` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor`。

| 字段 | 类型 | 说明（源码注释原文） |
| --- | --- | --- |
| `outTradeNo` | `String` | 商户订单号 |
| `amount` | `BigDecimal` | 订单总金额（单位：元） |
| `subject` | `String` | 订单标题 |
| `body` | `String` | 订单描述 |
| `scene` | `Scene` | 支付场景 |
| `timeoutExpress` | `String` | 交易超时时间（如 `30m`、`1h`、`1d`） |
| `notifyUrl` | `String` | 异步通知地址 |
| `returnUrl` | `String` | 同步跳转地址（H5/网页支付） |
| `extParams` | `Map<String, String>` | 扩展参数 |

### 3.3 PaymentResponse 字段速查

lombok：`@Data` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor`。

| 字段 | 类型 | 说明（源码注释原文） |
| --- | --- | --- |
| `success` | `boolean` | 是否成功 |
| `tradeNo` | `String` | 支付渠道交易号 |
| `outTradeNo` | `String` | 商户订单号 |
| `codeUrl` | `String` | 支付链接（Native/H5 场景） |
| `appAuthUrl` | `String` | app_认证_url（APP 支付签约跳转） |
| `rawResponse` | `String` | 原始响应体 |
| `extParams` | `Map<String, String>` | 扩展参数 |

### 3.4 Scene 支付场景枚举

| 枚举值 | 说明（源码注释原文） |
| --- | --- |
| `APP` | APP 支付 |
| `MINI_APP` | 小程序支付 |
| `H5` | H5 支付 |
| `BAR_CODE` | 扫码支付（条码/扫码枪） |
| `NATIVE` | Native 支付（扫码下单） |
| `JSAPI` | JSAPI 支付（公众号/服务号） |

### 3.5 PaymentChannel 接口方法

| 方法 | 形态 | 说明 |
| --- | --- | --- |
| `pay(PaymentRequest request)` | 抽象方法 | 支付，返回 `PaymentResponse` |
| `query(PaymentRequest request)` | default | 查询订单，默认 `throw new UnsupportedOperationException()` |
| `close(PaymentRequest request)` | default | 关闭订单，默认 `throw new UnsupportedOperationException()` |
| `refund(PaymentRequest request)` | default | 退款，默认 `throw new UnsupportedOperationException()` |
| `getChannel(String name)` | static | 通过 SPI 获取支付渠道实例；`name` 为渠道名称，如 `"alipay"`、`"tenpay"` |

### 3.6 PayException

`PayException extends RuntimeException`，构造方式：

```java
throw new PayException("CODE_1001", "余额不足");   // code + message
throw new PayException("余额不足");                 // 仅 message，code 为 null
```

`getCode()` 返回业务编码。

---

## 4. 快速开始

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-payment-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 4.2 发起一笔支付

来自 `PaymentChannel` 接口 Javadoc 的链式示例（需 classpath 上存在名为 `alipay` 的渠道 SPI 实现；未注册对应实现时 `getChannel` 返回 `null`）：

```java
import com.chua.payment.spi.PaymentChannel;
import com.chua.payment.support.PaymentRequest;
import com.chua.payment.support.PaymentResponse;
import com.chua.payment.support.Scene;

import java.math.BigDecimal;

public class PaymentDemo {

    public static void main(String[] args) {
        PaymentResponse response = PaymentChannel.getChannel("alipay")
                .pay(PaymentRequest.builder()
                        .amount(BigDecimal.valueOf(0.01))
                        .subject("订单标题")
                        .outTradeNo("ORD20260101001")
                        .scene(Scene.APP)
                        .build());

        if (response.isSuccess()) {
            System.out.println(response.getTradeNo());
            System.out.println(response.getCodeUrl());
        }
    }
}
```

### 4.3 一次用满全部字段的请求

```java
PaymentRequest request = PaymentRequest.builder()
        .outTradeNo("ORD20260101002")
        .amount(new BigDecimal("9.90"))
        .subject("示例商品")
        .body("示例商品描述")
        .scene(Scene.NATIVE)
        .timeoutExpress("30m")
        .notifyUrl("https://merchant.example.com/notify")
        .returnUrl("https://merchant.example.com/return")
        .extParams(Map.of("channelTag", "activity"))
        .build();
```

---

## 5. 配置项

**无。**

依据：模块源码中不存在任何配置类（无 `@ConfigurationProperties`、无 `@Value` 读取类），`src/main/resources` 下无 `application.properties` / `application.yml` / 自定义配置文件，`pom.xml` 也未引入配置解析相关依赖；唯一资源文件是 GraalVM 的 `reflect-config.json`。模块为纯契约模块，引入依赖即可使用。

---

## 6. SPI 扩展点

唯一的 SPI 接口是 `com.chua.payment.spi.PaymentChannel`，接口标注 `@Spi`：

- **获取实例**：`PaymentChannel.getChannel(name)` 内部等价于 `ServiceProvider.of(PaymentChannel.class).getExtension(name)`。
- **渠道名称**：接口注释示例为 `"alipay"`、`"tenpay"`，实现类按渠道名称注册，调用方通过工厂方法获取实例。
- **注册方式**：按 `utils-support-common-starter` 的 SPI 机制注册（`@Spi` / `@Extension` 注解或 `META-INF/extensions/` 声明文件），详见同仓 `utils-support-common-starter/README.md` 的「SPI 服务发现」章节。
- **最小实现面**：只需实现 `pay(PaymentRequest)`；`query` / `close` / `refund` 是 default 方法，不覆盖时调用会抛 `UnsupportedOperationException`。

---

## 7. 构建与依赖

### 7.1 依赖关系

```text
utils-support-payment-starter
└── utils-support-common-starter（${project.version}）
    └── lombok（scope=provided，编译期）
```

### 7.2 依赖清单（来自 pom.xml）

| GroupId | ArtifactId | 版本 | Scope |
| --- | --- | --- | --- |
| `com.chua` | `utils-support-common-starter` | `${project.version}`（4.0.0.51） | compile |
| `org.projectlombok` | `lombok` | 继承父 pom 管理 | provided |

### 7.3 构建

模块位于 `utils-support-core-parent` 之下，parent 为 `utils-support-core-parent`（version `4.0.0.51`）。构建方式（与同仓其它模块一致）：

```bash
mvn clean install -pl utils-support-core-parent/utils-support-payment-starter
```

或先安装父模块 `utils-support-core-parent` 后再整体构建。

---

## 8. 边界：这些能力不在本模块

以下能力**源码未提供**，请不要在本模块内寻找：

- **具体支付渠道实现**（支付宝、微信/tenpay 等）：`alipay`、`tenpay` 只是接口注释中的渠道名称示例，模块内没有任何渠道实现类。
- **支付网关对接**：HTTP 客户端封装、下单报文构造、异步回调解析与验签 —— 源码未提供。
- **真实业务语义的查询/关闭/退款**：`query` / `close` / `refund` 是 default 桩方法，默认抛 `UnsupportedOperationException`。
- **聚合支付、分账、对账、账单下载** —— 源码未提供。
- **Spring 自动装配**：无任何 `@Configuration` / `@EnableAutoConfiguration` / starter 自动配置类，模块不依赖 Spring。
- **支付回调处理框架**（通知重试、幂等、结果落库）—— 源码未提供。