# utils-support-otp-starter

> OTP（一次性密码）消息推送模块：向用户发送一次性密码、获取最近记录、列表查询。本模块基于 common 层的 `MessagePush` SPI 以 `opt` 渠道提供 OTP 的生成、发送、获取与核对能力。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承 `utils-support-core-parent` 的 `maven-compiler-plugin` `release=25`）｜包名：`com.chua.otp.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 源码构成](#13-源码构成)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 接口明细](#22-接口明细)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-otp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 本质 | 基于 `MessagePush` SPI 的 OTP 渠道实现（`@Spi("opt")`），通过 `MessagePush.create("opt")` 装配 | 不是独立的短信/邮件/IM 消息网关 |
| 能力 | 验证码生成（DIGIT / LETTER / MIXED）、发送（call / send）、最近记录（get）、列表查询（list）、核对（verify） | 不实际调用任何第三方短信、邮件或推送通道，`call()` 只生成记录并返回发送结果（源码未提供外部通道调用） |
| 存储 | 进程内 `ConcurrentHashMap`（接收人标识 → 记录列表），每个用户最多保留 **20** 条 | 不持久化，进程重启即全部丢失；非分布式 |
| 模板 | 无模板：`listTemplates()` 返回空列表、`getTemplate(String)` 返回 空；下发内容走 `otp.content` 模板，占位符 `{code}` | 不提供模板管理与 `sendTemplate` 可用实现 |
| 依赖 | 仅 `utils-support-common-starter`（提供 `MessagePush`/`MessageEnvironment`/`MessageRequest`/`MessageResponse` 契约）+ `lombok`（`provided`） | 不依赖 Spring、不依赖任何短信/邮件 SDK |

**判断口径**：本模块只负责「一次性密码」的业务逻辑与内存记账，把「真正把验证码发出去（短信/邮件/IM）」的事留给外部渠道 starter 去做；`OptClient` 内部没有任何渠道发送逻辑。

### 1.3 源码构成

- Java 源文件 **2** 个（包 `com.chua.otp.support`）：
  - `OptClient.java` — OTP 推送实现（`@Spi("opt")`，实现 `com.chua.common.support.task.message.MessagePush`）
  - `OptMessage.java` — OTP 验证码记录（record，`java.io.Serializable`）
- SPI 扩展声明 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.task.message.MessagePush`，内容为 `opt=com.chua.otp.support.OptClient`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发送验证码（自动生成） | `client.call(String toUser)` | 自动生成验证码并保存记录，返回 `MessageResponse`（`success` / `messageId` / `errorMessage` / `durationMillis`） |
| 发送指定验证码 | `client.call(String toUser, String code)` | `code` 为空白时自动生成；`toUser` 为空白时返回失败（"接收人不能为空"） |
| 获取最近一条记录 | `client.get(String toUser)` | 优先返回未过期的最近一条；无记录返回 空 |
| 记录列表 | `client.list(String toUser)` | 该用户记录列表（按发送时间倒序），客户端不可变 |
| 全部记录 | `client.list()` | 所有接收人的全部记录 |
| 核对验证码 | `client.verify(String toUser, String code)` | 与最近一条有效验证码比对，通过返回 `true` |
| 经 SPI 统一入口获取 | `MessagePush.create("opt")` | 由 `ServiceProvider.of(MessagePush.class).getNewExtension("opt")` 每次新建实例 |
| 渠道标识 | `client.getProvider()` | 返回 `"opt"` |

### 2.2 接口明细

**`OptClient implements MessagePush`**（`com.chua.otp.support.OptClient`）

| 方法 | 行为（与源码一致） |
| --- | --- |
| `String getProvider()` | 返回 `"opt"` |
| `MessageResponse send(MessageRequest request)` | 等价于 `call(request.getTo())` |
| `List<TemplateInfo> listTemplates()` | OTP 没有模板，返回空列表 |
| `TemplateInfo getTemplate(String templateId)` | OTP 没有模板，返回 空 |
| `MessageResponse call(String toUser)` | `call(toUser, null)`，验证码为空时自动生成 |
| `MessageResponse call(String toUser, String code)` | 生成/使用验证码并 `save`，返回 `MessageResponse.builder().success(...).messageId(...).errorMessage(...).durationMillis(...).build()` |
| `OptMessage get(String toUser)` | 先清理该用户过期记录（`expire()` 会移除已过期记录）；`findFirst` 取列表首条未过期记录（最新一条），无记录或全部过期时返回 空 |
| `List<OptMessage> list(String toUser)` | 先清理该用户过期记录；无记录或全部过期时返回空列表，否则返回不可变副本（`Collections.unmodifiableList`） |
| `List<OptMessage> list()` | 聚合 `store` 中所有用户的全部记录 |
| `boolean verify(String toUser, String code)` | `toUser` / `code` 任一空白返回 `false`；无最近记录返回 `false`；`StringUtils.equals(message.code(), code)` |

验证码生成规则（`generateCode()`）：按 `otp.length` 生成长度，随机源 `RandomUtils.randomInt(0, bound)`，bound 取对应类型码表大小：

| `otp.type` | 码表大小 | 生成字符 |
| --- | --- | --- |
| `DIGIT`（默认） | 10（0-9） | 数字 |
| `LETTER` | 26（a-z） | 小写字母 |
| `MIXED` | 62 | 数字 0-9，或 `'a' + (code - 10)` 计算出的字符（`code ∈ [10, 35]` 时为 a-z，`code ≥ 36` 时超出字母表；生成逻辑见源码 `generateCode`） |

存储行为（`save()` / `expire()`）：记录插入列表头部（最新在前）；单用户超过 `MAX_KEEP = 20` 时丢弃最旧记录；读取路径（get / list）先清理已过期记录。

**`OptMessage`**（`com.chua.otp.support.OptMessage`，record）

```java
public record OptMessage(String id, String toUser, String code, String content, String type,
                         boolean success, String errorMsg, long createdAt, long expiredAt)
        implements Serializable
```

- 常量：`TYPE_DIGIT = "DIGIT"`、`TYPE_LETTER = "LETTER"`、`TYPE_MIXED = "MIXED"`
- 工厂：`static OptMessage record(String toUser, String code, String content, String type, boolean success, String errorMsg, long duration)`（`id` 为 `UUID.randomUUID().toString()`，`expiredAt = now + duration`）；`static OptMessage failure(String toUser, String errorMsg)`
- `boolean isExpired()`：`System.currentTimeMillis() > expiredAt`

---

## 3. 快速开始

以下示例与 `OptClient` 源码 Javadoc 中的用法示例一致：

```java
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.otp.support.OptClient;
import com.chua.otp.support.OptMessage;

import java.util.List;

// 获取 OTP 渠道实例（每次新建）
OptClient client = (OptClient) MessagePush.create("opt");

// 发送验证码（自动生成）
MessageResponse response = client.call("user123");

// 获取最近一条验证码记录
OptMessage message = client.get("user123");

// 验证码记录列表
List<OptMessage> list = client.list("user123");

// 核对验证码
boolean ok = client.verify("user123", message.code());
```

指定验证码 / 自定义环境配置：

```java
import com.chua.common.support.task.message.MessageEnvironment;

// 指定验证码下发
client.call("user123", "888888");

// 自定义环境配置（长度 8、有效 5 分钟、混合类型、内容模板）
MessageEnvironment environment = new MessageEnvironment()
        .set("otp.length", "8")
        .set("otp.duration", "300000")
        .set("otp.type", "MIXED")
        .set("otp.content", "本次验证码为：{code}，5 分钟内有效。");
OptClient custom = new OptClient(environment);
custom.call("user456");
```

经 `MessagePush` 统一接口发送（等价于 `call(request.getTo())`）：

```java
MessagePush push = MessagePush.create("opt");
MessageResponse resp = push.send(MessageRequest.builder().to("user123").build());
```

---

## 4. 配置项

配置项在 `OptClient` 的 `@SpiDescribe#optional` 中声明，运行时经 `MessageEnvironment` 读取（`environment.get(prop)`）。

| 键 | 默认值 | 类型 | 说明 |
| --- | --- | --- | --- |
| `otp.length` | `6` | int | 验证码长度 |
| `otp.duration` | `60000` | long | 验证码有效时长，单位毫秒 |
| `otp.content` | `您的验证码为：{code}，请于 5 分钟内使用。` | String | 下发内容模板，占位符 `{code}` |
| `otp.type` | `DIGIT` | String | 验证码类型：`DIGIT` / `LETTER` / `MIXED` |

注入方式（源码未提供 Spring/配置文件绑定逻辑）：

- 默认构造 `new OptClient()` 使用空 `MessageEnvironment`，所有键走默认值；
- 显式构造 `new OptClient(MessageEnvironment)` 注入，通过 `MessageEnvironment.set(key, value)` 设置；`MessageEnvironment` 提供 `get(String)` / `get(String, String)` / `getAll()`；
- 数值解析失败（非数字）时回退默认值（源码 `parseInt` / `parseLong` 捕获异常返回默认值，`parseString` 空白时返回默认值）；
- 本模块没有自动配置类、没有配置文件加载逻辑（`pom.xml` 无 Spring 依赖，源码未提供）。

---

## 5. SPI 扩展点

本模块不是接口定义方，而是 `MessagePush` 的扩展实现方：

```text
META-INF/extensions/com.chua.common.support.task.message.MessagePush
opt=com.chua.otp.support.OptClient
```

- 声明：`@Spi("opt")` + `@SpiDescribe(value = "OTP推送", type = "OTP")`，通过 SPI 注册表按名字装配；
- 获取：`MessagePush.create("opt")`（common 层静态方法，`ServiceProvider.of(MessagePush.class).getNewExtension("opt")`，每次新建实例）；
- 扩展契约（定义于 `utils-support-common-starter`）：`getProvider()` / `send(MessageRequest)` / `listTemplates()` / `getTemplate(String)`，以及默认的 `sendTemplate` 两个重载；
- 本模块**没有**自定义新的 SPI 接口或注解（全模块仅 `OptClient`、`OptMessage` 两个类）；如需要新渠道，请在其它 starter 中实现 `MessagePush` 并按同目录规则登记（源码未提供相关实现）。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（继承 `utils-support-core-parent` 的 `maven-compiler-plugin` `release=25`，与同仓 `utils-support-common-starter` 一致）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译（`annotationProcessorPaths`）。

```bash
mvn -pl utils-support-core-parent/utils-support-otp-starter -am clean package
```

### 6.2 依赖（与 `pom.xml` 逐字一致）

| 依赖 | 坐标 | 作用域 | 用途 |
| --- | --- | --- | --- |
| `utils-support-common-starter` | `com.chua:utils-support-common-starter:${project.version}` | compile | `MessagePush` / `MessageEnvironment` / `MessageRequest` / `MessageResponse` / `TemplateInfo` 契约与 SPI、`StringUtils`、`RandomUtils`、`@Spi` 注解体系 |
| `lombok` | `org.projectlombok:lombok` | provided | 编译期（`@Slf4j`） |

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 真实短信 / 邮件 / IM 推送 | 不在本模块：`call()` 只生成验证码记录并返回 `MessageResponse`，源码中没有任何第三方通道调用（通道实现源码未提供） |
| 验证码持久化 | 不在本模块：记录仅存进程内存 `ConcurrentHashMap`，重启丢失；无数据库、无文件落盘 |
| 模板管理与 `sendTemplate` | 不在本模块：OTP 没有模板，`listTemplates()` 返回空列表、`getTemplate(String)` 返回 空；内容固定走 `otp.content` 模板替换 `{code}` |
| Spring / Spring Boot 自动配置 | 不在本模块：`pom.xml` 无 Spring 依赖，无自动配置类、无 `application.yml` 属性绑定（相关集成源码未提供） |
| 发送频控 / 防暴力破解 / 图形验证码 | 不在本模块：`verify` 仅比对最近一条有效验证码，无次数限制、无冷却、无图形验证码 |
| 分布式 / 集群共享验证码 | 不在本模块：存储为单机内存 Map，多实例间不共享 |
| 短信、邮件等渠道的 starter 实现 | 不在本模块：渠道类 `MessagePush` 实现在其它 starter（源码未提供），本模块只提供 `opt` 这一个登记项 |