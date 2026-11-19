# utils-support-webhook-starter

> utils 体系的**通用 Webhook 推送模块**：把文本 / Markdown / 原始 JSON 以 `POST application/json` 的方式发往任意 Webhook 地址，兼容钉钉、企业微信群机器人及自建网关；作为 `MessagePush` SPI 的 `webhook` 提供者，接入 utils 统一消息推送体系。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 POM `maven.compiler.release=25`）｜包名：`com.chua.webhook.support.message`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API](#2-核心-api)
- [3. 常用能力速查表](#3-常用能力速查表)
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
    <artifactId>utils-support-webhook-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `MessagePush` SPI 的 `webhook` 实现：向任意 Webhook 地址 POST JSON 消息 | 不是某个平台的专用 SDK |
| 协议 | 钉钉/企业微信群机器人通用的 `text` / `markdown` 报文，以及原始 JSON 透传 | 不做平台加签、@人、互动卡片等专属协议 |
| 形态 | 单实现类 `WebhookMessagePush` + 一行 SPI 登记，无 Spring 依赖 | 不是 Spring Boot starter，无自动配置、无 yml 属性绑定 |
| 依赖 | 只依赖 `utils-support-common-starter`、`slf4j-api`、`lombok` | 不依赖数据库、不依赖任何云厂商 SDK |

**判断口径**：需要一个「发到任意 Webhook 地址」的统一消息出口时用本模块；需要钉钉/飞书/企业微信等平台专属能力时，用对应平台 starter（见第 8 节边界）。

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.webhook.support.message.WebhookMessagePush`。
- SPI 登记文件 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.task.message.MessagePush`。

---

## 2. 核心 API

### 2.1 WebhookMessagePush

| 成员 | 说明 |
| --- | --- |
| `WebhookMessagePush()` | 默认构造，使用空的 `MessageEnvironment` |
| `WebhookMessagePush(MessageEnvironment environment)` | 指定环境配置，`webhook.url` 从这里读取 |
| `String getProvider()` | 返回 `"webhook"` |
| `MessageResponse send(MessageRequest request)` | 发送消息；内部捕获所有异常，失败以 `MessageResponse` 返回，不向调用方抛出 |
| `List<TemplateInfo> listTemplates()` | 已注册模板列表（进程内 Map，默认为空） |
| `TemplateInfo getTemplate(String templateId)` | 按标识取模板，不存在返回 `null` |
| `void registerTemplate(TemplateInfo template)` | 注册模板，按 `template.id()` 存入内存 |

继承自 `MessagePush` 接口的 `default` 方法：`sendTemplate(templateId, params, request)` 与 `sendTemplate(templateId, to, params)`，按 `{{key}}` 占位符渲染模板内容后发送。

### 2.2 消息格式（按 `contentType` 路由）

`send` 读取 `request.getContentType()`（忽略大小写、按 `contains` 匹配）决定请求体：

| contentType | 请求体 | 适用场景 |
| --- | --- | --- |
| `text`（默认，含未设置） | `{"msgtype":"text","text":{"content":"..."}}` | 钉钉 / 企业微信群机器人、通用文本 |
| 含 `markdown` | `{"msgtype":"markdown","markdown":{"content":"..."}}` | 钉钉 / 企业微信 Markdown 消息 |
| 含 `raw` 或 `json` | 消息内容本身作为 JSON 请求体透传（解析失败回退为 `text` 格式） | 自建网关、自定义报文 |

请求固定为 `HttpMethod.POST`，请求头 `Content-Type: application/json`，底层通过 `HttpClientFactory.getClient()` 发出。

### 2.3 发送结果 MessageResponse

| 场景 | 结果 |
| --- | --- |
| HTTP 2xx，且响应 JSON 的 `errcode` 缺失或为 `0` | `isSuccess()=true`；`messageId` 为本地生成的随机 UUID |
| HTTP 2xx，但 `errcode != 0` | `isSuccess()=false`；`errorMessage` 为 `"Webhook 返回错误: " + 响应体` |
| HTTP 非 2xx | `isSuccess()=false`；`errorMessage` 为 `"Webhook HTTP <状态码>: " + 响应体` |
| 网络/构造异常 | `isSuccess()=false`；`errorMessage` 为异常消息，方法不抛出 |

`errcode` 的提取面向钉钉/企业微信的返回结构；响应体不是 JSON 或无该字段时不影响成功判定。`getDurationMillis()` 为 `send` 内部计时的耗时（毫秒）。

---

## 3. 常用能力速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发文本消息 | `push.send(MessageRequest.builder()...build())` | `contentType` 缺省即 `text` |
| 发 Markdown | `MessageRequest.builder().contentType("markdown")` | 钉钉/企业微信 Markdown |
| 透传原始 JSON | `contentType("raw")` 或 `contentType("json")` | 内容须为合法 JSON，否则回退 `text` |
| 配置 Webhook 地址 | `new MessageEnvironment().set("webhook.url", url)` | 唯一必填配置 |
| SPI 方式获取实现 | `ServiceProvider.of(MessagePush.class).getNewExtension("webhook", environment)` | 构造参数透传环境 |
| 注册模板 | `push.registerTemplate(templateInfo)` | 内存态，按 `id()` 索引 |
| 模板发送 | `push.sendTemplate(templateId, params, request)` | `{{key}}` 占位符替换 |
| 判定结果 | `resp.isSuccess()` / `resp.getErrorMessage()` / `resp.getDurationMillis()` | 失败原因与耗时 |

---

## 4. 快速开始

```java
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.webhook.support.message.WebhookMessagePush;

public class WebhookDemo {

    public static void main(String[] args) throws Exception {
        // 1. 唯一必填配置：Webhook 地址
        MessageEnvironment environment = new MessageEnvironment()
                .set("webhook.url", "https://oapi.dingtalk.com/robot/send?access_token=***");

        // 2. 创建推送实例
        MessagePush push = new WebhookMessagePush(environment);

        // 3. 发送文本消息（contentType 缺省即 text）
        MessageResponse resp = push.send(MessageRequest.builder()
                .to("ops")                               // webhook 不使用该字段，但 MessageRequest 构建器要求非空
                .contentType("text")                     // text / markdown / raw、json
                .content("部署完成：order-service 已发布 v1.2.0")
                .build());

        System.out.println(resp.isSuccess() + " / " + resp.getDurationMillis() + "ms");
    }
}
```

说明：

- `MessageRequest.builder().build()` 要求 `to` 非空，否则抛 `IllegalArgumentException`；webhook 发送本身不使用 `to`，填任意标识即可。
- 也可用 SPI 获取实例：`ServiceProvider.of(MessagePush.class).getNewExtension("webhook", environment)`；而 `MessagePush.create("webhook")` 走无参构造、环境为空，且环境字段没有 setter，创建后无法再补 `webhook.url`，发送会直接返回「webhook.url 配置项必填」的失败结果。

---

## 5. 配置项

| 项 | 传入方式 | 必填 | 说明 |
| --- | --- | --- | --- |
| `webhook.url` | `MessageEnvironment.set("webhook.url", url)` | 是 | Webhook 地址；缺失时 `send` 返回失败 `webhook.url 配置项必填` |

除 `webhook.url` 外无其它配置项。依据：

- 源码中只有 `@SpiParam(value = "webhook.url", desc = "Webhook 地址", type = "String")` 一个参数声明，`send` 也只读取 `environment.get("webhook.url")`。
- 本模块 pom 无任何 Spring 依赖，源码未提供 `application.yml` / `@ConfigurationProperties` 之类的属性绑定；配置只能通过 `MessageEnvironment` 在构造时传入。

---

## 6. SPI 扩展点

本模块**不定义新的 SPI 接口**，它是 `utils-support-common-starter` 中 `MessagePush` SPI 的一个实现方：

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.common.support.task.message.MessagePush` |
| 提供者名 | `webhook`（`@Spi("webhook")`，与 `getProvider()` 一致） |
| 实现 | `com.chua.webhook.support.message.WebhookMessagePush` |
| 登记文件 | `src/main/resources/META-INF/extensions/com.chua.common.support.task.message.MessagePush`，内容一行：`webhook=com.chua.webhook.support.message.WebhookMessagePush` |
| 元数据 | `@SpiDescribe(value = "Webhook 推送", type = "WEBHOOK", desc = "发送文本/Markdown 到任意 Webhook 地址（钉钉/企业微信/自建网关）", optional = @SpiParam("webhook.url"))` |

获取方式：

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.message.MessagePush;
import com.chua.webhook.support.message.WebhookMessagePush;

// 方式一：直接构造（推荐，可携带环境配置）
MessagePush push = new WebhookMessagePush(environment);

// 方式二：SPI 构造参数透传
MessagePush push2 = ServiceProvider.of(MessagePush.class).getNewExtension("webhook", environment);
```

模板能力说明：模板存放在进程内 `ConcurrentHashMap`，仅 `registerTemplate` 写入，无持久化与自动加载；未注册模板时调用 `sendTemplate` 会抛 `IllegalArgumentException("模板不存在: ...")`。

---

## 7. 构建与依赖

```bash
mvn -pl utils-support-cloud-parent/utils-support-webhook-starter -am clean package
```

- JDK 25（父 POM `maven.compiler.release=25`）；源码 UTF-8、无 BOM。
- 模块归属：`utils-support-cloud-parent`（云服务集成父模块）。

| 依赖 | 用途 | 范围 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `MessagePush` 契约、`MessageEnvironment` / `MessageRequest` / `MessageResponse` / `TemplateInfo`、HTTP 客户端、JSON、SPI 与集合工具 | compile |
| `org.slf4j:slf4j-api` | 日志 | compile |
| `org.projectlombok:lombok` | 编译期注解处理（`@Slf4j`） | provided |

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 钉钉 / 飞书 / 企业微信平台专属能力（加签、机器人管理、卡片、审批等） | `utils-support-dingding-starter`、`utils-support-feishu-starter`、`utils-support-wechat-starter` |
| 邮件 / 短信 / 桌面通知 | `utils-support-email-starter`、`utils-support-alibaba-starter`、`utils-support-tencentcloud-starter`、`utils-support-desktop-starter` |
| `MessagePush` 接口与 SPI 装配本身 | `utils-support-common-starter` |
| HTTP 底层实现选择（okhttp / httpclient5 / jdk） | `utils-support-common-starter` 的 `HttpClientExecutor` SPI |
| Spring Boot 自动配置、yml 属性绑定 | 源码未提供；本模块不是 Spring starter |
| 模板持久化、发送重试、限流、异步批量 | 源码未提供 |
