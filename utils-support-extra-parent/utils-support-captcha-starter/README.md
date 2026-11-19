# utils-support-captcha-starter

> 验证码解析服务客户端模块：通过统一接口 `CaptchaParser` 对接第三方打码平台（内置 `yescaptcha` 与 `captcha-run` 两个实现），支持提交验证码任务、查询/轮询结果、任务缓存，覆盖 reCAPTCHA、hCaptcha、FunCaptcha、Cloudflare Turnstile、极验、MtCaptcha 与图文验证码。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.captcha.support`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-captcha-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 第三方验证码解析服务（打码平台）的统一客户端 | 不是验证码图片生成器，也不是服务端校验框架 |
| 抽象 | `CaptchaParser` 统一「提交任务 → 查询结果 → 轮询等待 → 一次式 solve」 | 不绑定单一服务商 |
| 实现 | 内置 `YesCaptchaClient`（SPI 名 `yescaptcha`）与 `CaptchaRunClient`（SPI 名 `captcha-run`） | 源码未提供其他服务商实现 |
| 依赖 | 仅依赖 `utils-support-common-starter` | 不依赖 Spring、无数据库表、无 Web Controller |
| 运行形态 | 纯 Java 库，按 SPI 或编程式装配 | 源码未提供 Spring Boot 自动装配 |

**判断口径**：需要「把验证码交给第三方平台解出 token」时用本模块；需要「自己出题、自己校验」的验证码能力不在本模块。

---

## 2. 核心 API 与速查表

### 2.1 常用入口速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 一次式解析（提交 + 轮询到终态） | `CaptchaParser.solve(CaptchaRequest, byte[] imageData)` | 提交后未拿到任务标识时返回错误码 `SUBMIT_FAILED` |
| 分步：提交任务 | `CaptchaParser.submitCaptcha(byte[] imageData, Map<String,String> options)` | 返回任务标识 `taskId` |
| 分步：查询结果 | `CaptchaParser.queryResult(String taskId)` | 单次查询，不轮询 |
| 轮询等待终态 | `CaptchaParser.awaitResult(taskId, timeoutMillis, pollIntervalMillis)` | 超时/中断返回错误码 `TIMEOUT`，并回填 `executionTime` |
| 按 SPI 名取实现 | `ServiceProvider.of(CaptchaParser.class).getExtension("yescaptcha")` | 另有 `"captcha-run"`；无参构造时配置取自 JVM 参数/环境变量 |
| 指定服务商直连 | `new YesCaptchaClient(setting)` / `new CaptchaRunClient(setting)` | 配置用 `CaptchaSetting.builder().apiToken(...)` |
| 账户信息 | `YesCaptchaClient.getBalance()`；`CaptchaRunClient.getBalance()` / `getUserInfo()` | 余额查询失败返回 `0.0` |
| 任务缓存 | `TaskPersistence.file(path)` + `client.withPersistence(...)` | 命中缓存时不再请求服务商 |
| 验证码类型 | `CaptchaType` 枚举、`CaptchaType.parse(type)` | `parse` 未知返回 `null`；`fromType` 未知回落 `ReCaptchaV2` |

### 2.2 类型总览

| 类型 | 说明 |
| --- | --- |
| `CaptchaParser` | 验证码解析器接口，本模块的核心抽象；`awaitResult`/`solve` 为 default 方法 |
| `CaptchaRequest` | 解析请求参数（类型、URL、siteKey、action、代理、超时、轮询间隔） |
| `CaptchaResponse` | 解析结果（`success`、`token`、`taskId`、`message`、`errorCode`、`executionTime`） |
| `CaptchaSetting` | 服务配置（`apiToken`、`apiUrl`、`connectTimeout`、`readTimeout`） |
| `CaptchaType` | 验证码类型枚举（10 种） |
| `YesCaptchaClient` | yescaptcha（2Captcha 兼容）客户端，SPI 名 `yescaptcha` |
| `CaptchaRunClient` | captcha-run 客户端，SPI 名 `captcha-run` |
| `TaskPersistence` | 任务持久化接口（`save`/`query`/`delete`） |
| `FileTaskPersistence` | 基于文件存储的持久化实现 |

### 2.3 CaptchaType 支持的验证码类型

| 枚举 | type 标识 |
| --- | --- |
| `RECAPTCHA_V2` | `ReCaptchaV2` |
| `RECAPTCHA_V3` | `ReCaptchaV3` |
| `RECAPTCHA_V2_ENTERPRISE` | `ReCaptchaV2Enterprise` |
| `RECAPTCHA_V3_ENTERPRISE` | `ReCaptchaV3Enterprise` |
| `HCAPTCHA` | `HCaptcha` |
| `FUNCAPTCHA` | `FunCaptcha` |
| `TURNSTILE` | `Turnstile` |
| `GEETEST` | `Geetest` |
| `MT_CAPTCHA` | `MtCaptcha` |
| `TEXT_CAPTCHA` | `TextCaptcha`（图文验证码，必须传图片） |

### 2.4 submitCaptcha 的 options 键

`options` 的键按实现各有侧重（键名区分大小写）：

| 键 | captcha-run | yescaptcha | 说明 |
| --- | --- | --- | --- |
| `captchaType` | ✓ | ✓ | 类型标识，缺省 `ReCaptchaV2`；未知值抛 `IllegalArgumentException` |
| `siteKey` | ✓ | ✓ | 目标网站 site 键（yescaptcha 侧映射为 `websiteKey`） |
| `siteReferer` | ✓ | — | 目标页面地址 |
| `url` | — | ✓ | 目标页面地址（yescaptcha 侧映射为 `websiteURL`） |
| `siteAction` / `action` | ✓ / — | — / ✓ | reCAPTCHA v3 动作参数 |
| `proxy` | — | ✓ | 代理地址 |
| `body` | ✓ | ✓ | 图文验证码的图片内容（裸 Base64）；显式给出时优先于 `imageData` |
| `host` / `port` / `login` / `password` / `domain` / `anchor` / `reload` | ✓ | — | captcha-run 附加参数 |
| `useCache` / `isInvisible` | ✓ | — | 布尔参数，传字符串 `true`/`false` |

`solve(CaptchaRequest, byte[])` 会把 `request.url` 同时写成 `url` 与 `siteReferer`、`request.action` 同时写成 `action` 与 `siteAction`，以命中两家服务商各自的键。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-captcha-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 编程式：图文验证码一次式解析

```java
import com.chua.captcha.support.CaptchaParser;
import com.chua.captcha.support.CaptchaRequest;
import com.chua.captcha.support.CaptchaResponse;
import com.chua.captcha.support.CaptchaSetting;
import com.chua.captcha.support.CaptchaType;
import com.chua.captcha.support.YesCaptchaClient;

CaptchaParser parser = new YesCaptchaClient(
        CaptchaSetting.builder()
                .apiToken("your-token")     // 必填，缺失时调用点抛 IllegalStateException
                .build());

CaptchaResponse result = parser.solve(
        CaptchaRequest.builder()
                .type(CaptchaType.TEXT_CAPTCHA)   // 图文验证码：imageData 必传
                .timeout(60_000)                  // 轮询超时（毫秒）
                .pollInterval(2_000)              // 轮询间隔（毫秒）
                .build(),
        imageBytes);                              // 验证码图片 byte[]

if (result.isSuccess()) {
    System.out.println("解析结果: " + result.getToken());
} else {
    System.out.println("失败: " + result.getErrorCode() + " - " + result.getMessage());
}
```

### 3.3 编程式：reCAPTCHA V2

```java
CaptchaResponse result = parser.solve(
        CaptchaRequest.builder()
                .type(CaptchaType.RECAPTCHA_V2)
                .url("https://example.com/login")   // solve 会同时映射 url 与 siteReferer
                .siteKey("6LeXXXXXXXXXXXXXXXX")
                .build(),
        null);                                      // 非图文验证码不需要图片
```

### 3.4 SPI 方式获取实现

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.captcha.support.CaptchaParser;

// 令牌来自 JVM 参数 -Dcaptcha.api-token=xxx 或环境变量 CAPTCHA_API_TOKEN
CaptchaParser parser = ServiceProvider.of(CaptchaParser.class).getExtension("captcha-run");
```

### 3.5 分步调用（提交 → 轮询）

```java
String taskId = parser.submitCaptcha(null, java.util.Map.of(
        "captchaType", CaptchaType.RECAPTCHA_V2.getType(),
        "siteKey", "6LeXXXXXXXXXXXXXXXX",
        "siteReferer", "https://example.com/login"));   // captcha-run 的页面地址键

CaptchaResponse resp = parser.awaitResult(taskId, 180_000, 3_000);
```

> 注意：yescaptcha 侧的页面地址键是 `url`、动作键是 `action`；直接调 `submitCaptcha` 时请按目标实现给键，或直接用 `solve()` 自动双写。

### 3.6 任务缓存（避免重复请求）

```java
import com.chua.captcha.support.TaskPersistence;

CaptchaParser parser = new YesCaptchaClient(setting)
        .withPersistence(TaskPersistence.file("./captcha-tasks.txt"));
```

`FileTaskPersistence` 每行一条记录，格式为 `任务id|成功|令牌|消息|错误编码|执行耗时`；`%`、`|`、换行符按 `%XX` 转义，远端返回内容里出现分隔符也不会撑破记录。写入时先落同目录临时文件再原子替换，内存中维护缓存以减少文件 IO。

---

## 4. 配置项

本模块**不是零配置**：调用解析服务必须提供 API 令牌。

### 4.1 CaptchaSetting

| 配置项 | 说明 | 缺省 |
| --- | --- | --- |
| `apiToken` | 服务商 API 令牌；缺失时 `requireApiToken()` 抛 `IllegalStateException` | 无（必须提供） |
| `apiUrl` | 服务地址；留空时由各解析器取自己的缺省端点，避免跨服务商打到错误主机 | `yescaptcha` → `https://api.yescaptcha.com`；`captcha-run` → `https://api.captcha-run.com` |
| `connectTimeout` | 连接超时（毫秒） | `30000` |
| `readTimeout` | 读取超时（毫秒） | `30000` |

### 4.2 CaptchaRequest

| 配置项 | 说明 | 缺省 |
| --- | --- | --- |
| `type` | 验证码类型（`CaptchaType`） | 未设置时 `solve` 不写 `captchaType` 键，由实现按缺省 `ReCaptchaV2` 处理 |
| `url` | 目标页面 URL | 无 |
| `siteKey` | 目标网站 site 键 | 无 |
| `action` | reCAPTCHA v3 动作参数 | 无 |
| `proxy` | 代理地址 | 无 |
| `timeout` | 任务轮询超时（毫秒） | `180000` |
| `pollInterval` | 轮询间隔（毫秒） | `3000` |

### 4.3 JVM 参数与环境变量（SPI 无参实例化时）

通过 `CaptchaSetting.fromEnvironment(...)` 读取：先取 JVM 系统属性，缺失时回落同名大写环境变量（`.` 替换为 `_`）。

| JVM 参数 | 环境变量 | 说明 |
| --- | --- | --- |
| `captcha.api-token` | `CAPTCHA_API_TOKEN` | API 令牌 |
| `captcha.api-url` | `CAPTCHA_API_URL` | 服务地址；未配置时用解析器缺省端点 |
| `captcha.connect-timeout` | `CAPTCHA_CONNECT_TIMEOUT` | 连接超时（毫秒），非法值按 `30000` 处理 |
| `captcha.read-timeout` | `CAPTCHA_READ_TIMEOUT` | 读取超时（毫秒），非法值按 `30000` 处理 |

### 4.4 错误码

| 错误码 | 来源 | 含义 |
| --- | --- | --- |
| `TIMEOUT` | `CaptchaConstant.ERROR_TIMEOUT` | 轮询等待超时，或等待被中断 |
| `SUBMIT_FAILED` | `CaptchaConstant.ERROR_SUBMIT_FAILED` | 提交任务未获得任务标识 |
| `FAIL` | `CaptchaRunClient` | captcha-run 返回 `Fail` 状态 |
| `EXCEPTION` | 两个客户端 | 调用过程发生异常 |
| 服务商错误码 | `YesCaptchaClient` | `errorId != 0` 时透传服务商返回的 `errorCode` |

---

## 5. SPI 扩展点

### 5.1 CaptchaParser（SPI）

SPI 登记文件：`src/main/resources/META-INF/extensions/com.chua.captcha.support.CaptchaParser`

```text
yescaptcha=com.chua.captcha.support.YesCaptchaClient
captcha-run=com.chua.captcha.support.CaptchaRunClient
```

接入新的服务商：实现 `CaptchaParser` 并标注 `@Spi("名称")`，在同路径登记 `名称=全限定类名` 即可；`awaitResult` 与 `solve` 是接口 default 方法，无需重复实现。

### 5.2 TaskPersistence（编程式扩展点）

`TaskPersistence` 是任务缓存接口（`save` / `query` / `delete`），内置实现 `FileTaskPersistence`，通过静态工厂 `TaskPersistence.file(path)` 创建，再用 `withPersistence(...)` 注入客户端。源码中未提供该接口的 SPI 登记文件，替换实现走编程式注入。

### 5.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-captcha-starter/` 内置 `reflect-config.json`（登记本模块全部 9 个类型）与 `resource-config.json`（包含 `META-INF/services/.*`）。

---

## 6. 构建与依赖

```text
utils-support-captcha-starter (com.chua:utils-support-captcha-starter:4.0.0.51)
└── utils-support-common-starter   （SPI、JSON、集合、StringUtils 等基础能力）
```

- 父模块：`com.chua:utils-support-extra-parent:4.0.0.51`。
- 除 `utils-support-common-starter` 外无其他直接依赖；HTTP 使用 JDK `java.net.http.HttpClient`，JSON 序列化使用 common 层的 `com.chua.common.support.lang.json.Json`。
- 编译：在 `utils-support-parent-starter` 仓库根执行 `mvn -o -q compile -DskipTests`，`EXIT=0` 即通过。

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| 验证码生成 / 服务端校验 | 本模块只对接第三方解析服务；源码未提供图形验证码生成、短信验证码、行为验证码校验等能力 |
| Spring Boot 自动装配 | pom 无 Spring 依赖；源码未提供 `AutoConfiguration` / `spring.factories`，按 SPI 或编程式使用 |
| 数据库与 Web | 无数据库表、无 Mapper、无 Controller（源码未提供） |
| 其他服务商 | 内置实现仅 `yescaptcha` 与 `captcha-run`；2Captcha、Anti-Captcha 等源码未提供，可按第 5 节自行扩展 |
| 代理池 / IP 轮换 | `CaptchaRequest.proxy` 只透传单个代理地址；代理池管理源码未提供 |
| 结果字段语义 | `token` 取自服务商返回字段（`gRecaptchaResponse`，图文类回落 `code`）；不同类型返回内容的差异由服务商决定，本模块原样透传 |
