# utils-support-tencentcloud-starter

腾讯云基础能力集成模块：当前提供**腾讯云短信（SMS）推送**这一个 `MessagePush` SPI 实现——基于腾讯云官方 SDK 发送短信验证码与通知，并可拉取云端已审核模板列表。

- **当前版本**：`4.0.0.51`（随父模块 `utils-support-cloud-parent`；实现类自 `4.0.0.42` 起提供）
- **JDK**：25（根 pom 统一 `maven.compiler.release=25`）
- **包名**：`com.chua.tencentcloud.support.sms`
- **模块定位（pom 描述原文）**：腾讯云基础能力集成模块（短信等；不含混元大模型与微信支付）

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界：这些能力不在本模块](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

**是：**

| # | 能力 | 说明 |
|---|------|------|
| 1 | 腾讯云短信发送 | 实现 `com.chua.common.support.task.message.MessagePush`，调用腾讯云 SMS `SendSms`（SDK `v20190711`）发送验证码/通知 |
| 2 | 云端模板列表查询 | 调用 `DescribeSmsTemplateList`（SDK `v20210111`）分页拉取**审核通过且已生效**的短信模板 |
| 3 | 内存模板登记 | `registerTemplate` / `getTemplate` 提供进程内模板兜底登记与查询 |
| 4 | SPI 注册 | 以 SPI 名 `tencent-sms` 注册到 `MessagePush` 扩展点 |

**不是：**

| # | 边界 | 依据 |
|---|------|------|
| 1 | 不是 Spring Boot starter | pom 无任何 Spring 依赖；无自动装配、无 `@ConfigurationProperties` 配置类 |
| 2 | 不含混元大模型 | pom 描述明确"不含混元大模型" |
| 3 | 不含微信支付 | pom 描述明确"不含微信支付" |
| 4 | 不含腾讯云其它产品（COS、CVM、OCR 等） | 源码仅含 `com.chua.tencentcloud.support.sms` 一个包、一个实现类 |
| 5 | 不做模板占位符本地替换 | `sendTemplate(templateId, to, params)` 覆写后直接把 `templateId` 与参数传给 `send()`，由腾讯云按模板渲染 |

---

## 核心 API 与速查表

### 速查表（最常查的入口）

| 你想做什么 | 怎么调 |
|-----------|--------|
| 创建实例（推荐，可注入配置） | `new TencentSmsMessagePush(environment)` |
| 通过 SPI 按渠道名创建 | `MessagePush.create("tencent-sms")`（得到的是**空配置**实例，见下文注意） |
| 发送短信（模板 + 参数） | `push.sendTemplate(templateId, to, params)` |
| 发送短信（完整请求对象） | `push.send(MessageRequest.builder().to(...).templateId(...).templateParams(...).build())` |
| 拉取云端已审核模板 | `push.listTemplates()` |
| 取已登记模板 | `push.getTemplate(templateId)` |
| 手工登记模板（兜底） | `push.registerTemplate(templateInfo)` |
| 读渠道标识 | `push.getProvider()` → `"tencent-sms"` |

### 方法明细（`TencentSmsMessagePush` 全部公开方法）

| 方法 | 说明 |
|------|------|
| `TencentSmsMessagePush()` | 无参构造，内部使用空的 `MessageEnvironment` |
| `TencentSmsMessagePush(MessageEnvironment environment)` | 指定消息环境；`environment` 为 `final` 字段，无 setter |
| `String getProvider()` | 返回 `"tencent-sms"` |
| `MessageResponse send(MessageRequest request) throws Exception` | 发送短信。取 `request.getTo()` 作为手机号、`request.getTemplateId()` 作为模板 ID、`request.getTemplateParams().values()` 按迭代顺序作为模板参数数组；成功返回 `success=true`、`messageId=腾讯云 SerialNo`，失败返回 `success=false`、`errorMessage="code: message"` |
| `List<TemplateInfo> listTemplates()` | 分页拉取国内（`international=0`）模板，每页 50 条，仅保留 `statusCode==0`（审核通过且已生效）的模板；`TemplateInfo.id` 为模板 ID 字符串，`type` 为 `"sms"`，`paramDefs` 为 `null` |
| `TemplateInfo getTemplate(String templateId)` | 只查**内存中已登记**的模板，不查云端；查不到返回 `null` |
| `void registerTemplate(TemplateInfo template)` | 把模板放入进程内 `ConcurrentHashMap`（以 `template.id()` 为键） |
| `MessageResponse sendTemplate(String templateId, String to, Map<String, String> params) throws Exception` | 用 `to`/`templateId`/`params` 构建 `MessageRequest` 后直接 `send()` |

### 发送行为要点（源码事实）

- 接入点固定：endpoint `sms.tencentcloudapi.com`，地域固定 `ap-guangzhou`。
- 成功判定：腾讯云返回的 `SendStatus.code` 等于 `"Ok"`。
- 腾讯云未返回状态集时：`success=false`，`errorMessage="腾讯云 SMS 未返回发送状态"`。
- 短信发送本身抛出的异常（网络/SDK）按 `throws Exception` 向上传播；**业务失败**（如签名/模板错误）不抛异常，而是返回 `success=false` 的 `MessageResponse`。
- `listTemplates()` 查询失败时：`RuntimeException` 原样抛出，其它异常包装为 `RuntimeException("查询短信模板列表失败: " + message)`；缺 `secretId`/`secretKey` 时抛 `IllegalArgumentException`。
- `listTemplates()` 的结果**不会**写入 `getTemplate()` 使用的内存缓存；`getTemplate()` 只能看到 `registerTemplate()` 登记过的模板。
- `MessageResponse` 可读字段：`isSuccess()`、`getMessageId()`、`getErrorMessage()`、`getDurationMillis()`、`getData()`。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tencentcloud-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最短可运行示例

```java
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.tencentcloud.support.sms.TencentSmsMessagePush;

import java.util.LinkedHashMap;
import java.util.Map;

public class TencentSmsDemo {

    public static void main(String[] args) throws Exception {
        // 1. 注入 4 个必填配置
        MessageEnvironment env = new MessageEnvironment()
                .set("sms.secretId", "你的SecretId")
                .set("sms.secretKey", "你的SecretKey")
                .set("sms.appId", "1400000000")
                .set("sms.signName", "你的短信签名");

        // 2. 创建推送实现
        TencentSmsMessagePush push = new TencentSmsMessagePush(env);

        // 3. 按模板发送（参数值顺序 = 腾讯云模板占位符顺序，建议用有序 Map）
        Map<String, String> params = new LinkedHashMap<>();
        params.put("1", "123456");

        MessageResponse resp = push.sendTemplate("2000001", "13800138000", params);

        // 4. 判断结果
        if (resp.isSuccess()) {
            System.out.println("发送成功，SerialNo=" + resp.getMessageId());
        } else {
            System.out.println("发送失败：" + resp.getErrorMessage());
        }
    }
}
```

> 也可用 `MessagePush.create("tencent-sms")` 获取实例，但该路径走无参构造，实例内的 `MessageEnvironment` 为空且无法事后注入，发送时会因缺少 `sms.secretId` 等配置抛 `IllegalArgumentException`。**需要配置时请直接 `new TencentSmsMessagePush(env)`。**

---

## 配置项

配置通过 `MessageEnvironment.set(key, value)` 注入，经构造器传入实现类；本模块**不读取** Spring 配置文件，也没有 Spring 配置属性类。

| 键 | 必填 | 说明 |
|----|------|------|
| `sms.secretId` | 是 | 腾讯云 SecretId。为空时回退读取 `sms.accessKey`（账号模块统一按 `sms.accessKey` 注入 AccessKeyId；腾讯云叫 SecretId） |
| `sms.secretKey` | 是 | 腾讯云 SecretKey |
| `sms.appId` | 是 | 短信应用 ID（作为 `SmsSdkAppid` 传给腾讯云） |
| `sms.signName` | 是 | 短信签名（作为 `Sign` 传给腾讯云） |
| `sms.accessKey` | 否 | `sms.secretId` 的回退键，仅当 `sms.secretId` 为空时生效 |

补充：

- `send()` 要求上表 4 项全部非空，任一缺失抛 `IllegalArgumentException("sms.xxx 配置项必填")`。
- `listTemplates()` 只要求 `sms.secretId`（或回退 `sms.accessKey`）与 `sms.secretKey`。
- 除上述键外，本模块不读取其它配置；无默认值可覆盖项（地域、endpoint 在代码中固定）。

---

## SPI 扩展点

| 项 | 值 |
|----|----|
| 扩展点接口 | `com.chua.common.support.task.message.MessagePush` |
| SPI 名 | `tencent-sms`（类上 `@Spi("tencent-sms")`，与 `getProvider()` 一致） |
| 注册文件 | `META-INF/extensions/com.chua.common.support.task.message.MessagePush` |
| 注册内容 | `tencent-sms=com.chua.tencentcloud.support.sms.TencentSmsMessagePush` |
| 描述元数据 | `@SpiDescribe(value="腾讯云短信", type="SMS", desc="基于腾讯云 SMS SDK 发送短信验证码与通知")`，可选参数声明为上表 4 个 `sms.*` 键 |

获取实例的两种方式：

1. **直接构造（可配置）**：`new TencentSmsMessagePush(env)` / `new TencentSmsMessagePush()`。
2. **SPI 工厂**：`MessagePush.create("tencent-sms")`（内部经 common-starter 的 `ServiceProvider` 按名加载）。注意该路径只能得到空配置实例。

本模块未提供新的 SPI 接口；它只是 `MessagePush` 扩展点的一个实现方。业务侧若要接入自己的短信渠道，实现 `MessagePush` 并在 `META-INF/extensions/com.chua.common.support.task.message.MessagePush` 中登记即可，与本模块并列，无需依赖它。

---

## 构建与依赖

Maven 坐标：

```text
com.chua:utils-support-tencentcloud-starter:4.0.0.51
```

模块链：`utils-support-parent-starter`（4.0.0.51）→ `utils-support-cloud-parent` → 本模块。

| 依赖 | 版本 | 说明 |
|------|------|------|
| `com.chua:utils-support-common-starter` | `${project.version}`（当前 4.0.0.51） | 提供 `MessagePush` 契约、`MessageEnvironment`、SPI 注解与加载器 |
| `com.tencentcloudapi:tencentcloud-sdk-java-sms` | `3.1.1281` | 腾讯云短信 SDK，版本由根 pom `dependencyManagement` 统一管理；含 `v20190711` 发送与 `v20210111` 模板查询；其公共包 `tencentcloud-sdk-java-common`（`3.2.32`，同样由根 pom 管理）经传递依赖提供 |
| `org.slf4j:slf4j-api` | 由父 pom 管理 | 日志门面 |
| `org.projectlombok:lombok` | `provided` | 仅编译期 |

编译（在 `utils-support-parent-starter` 根目录执行）：

```bash
mvn compile -DskipTests
```

---

## 边界：这些能力不在本模块

| 能力 | 去哪找 |
|------|--------|
| 腾讯云混元大模型 | 不在本模块（pom 描述明确排除）；仓库同目录另有 `utils-support-hunyuan-starter`（模块清单见 `utils-support-cloud-parent/pom.xml`） |
| 微信支付 | 不在本模块（pom 描述明确排除）；相关能力见 `utils-support-wechat-starter` / `utils-support-payment-starter` 等兄弟模块 |
| 腾讯云其它产品（对象存储、CVM、OCR 等） | 本模块源码未提供；当前仅实现了短信推送 |
| 邮件、飞书、钉钉、桌面通知等其它消息渠道 | 属于 `MessagePush` 的其它实现，不在本模块 |
| Spring Boot 自动装配 / 配置属性绑定 | 本模块无 Spring 依赖，配置一律经 `MessageEnvironment` 构造注入 |
