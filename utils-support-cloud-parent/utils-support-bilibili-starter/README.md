# utils-support-bilibili-starter

> 哔哩哔哩 **Index-Translate** 翻译服务的 SPI 集成模块。B 站翻译服务（`index-translate.bilibili.com`）采用 OpenAI 兼容的 Chat Completions 协议，因此本模块只用一个子类把默认地址与默认模型切到 B 站，其余能力全部复用 `utils-support-openai-starter` 的 `OpenAiTranslateClient` 实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `java.version=25`）｜包名：`com.chua.bilibili.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
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
    <artifactId>utils-support-bilibili-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `TranslateClient` 的 B 站 SPI 实现（SPI 名称 `bilibili` / `index-translate`），对接 B 站 Index-Translate 翻译服务 | 不是 B 站站内业务 SDK（视频、弹幕、直播、登录等均不在此） |
| 实现 | `BilibiliTranslateClient extends OpenAiTranslateClient`，仅覆盖默认地址与默认模型，协议栈复用 openai-java SDK | 不是从零实现的 B 站私有协议客户端 |
| 配置 | 通过 `TranslateClientSetting`（Builder）在代码中配置 | 无 Spring Boot 自动装配、无 `application.yml` 配置键 |
| 依赖 | 只依赖 `utils-support-common-starter`（SPI 契约）、`utils-support-openai-starter`（OpenAI 兼容实现）、slf4j、lombok | 不依赖 Spring、不依赖其它业务 starter |

**关键事实**（均来自源码 `BilibiliTranslateClient`）：

| 项 | 值 |
| --- | --- |
| 类 | `com.chua.bilibili.support.BilibiliTranslateClient` |
| SPI 注解 | `@Spi({"bilibili", "index-translate"})` |
| 父类 | `com.chua.openai.support.OpenAiTranslateClient` |
| 默认地址 | `https://index-translate.bilibili.com/v1`（`defaultBaseUrl()`） |
| 默认模型 | `Index-Translate-35B-A3B`（`defaultModel()`） |
| appKey | 即 API Key |
| 引入版本 | `@since 4.0.0.42` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 最快拿到译文 | `TranslateClient.create("bilibili", "sk-xxx").targetLang("英语").translateSync("你好")` | 静态工厂 + 链式配置 + 同步翻译 |
| 换 SPI 名称 | `TranslateClient.create("index-translate", "sk-xxx")` | `bilibili` 的别名，二者等价 |
| 指定模型 | `.model("Index-Translate-35B-A3B")` | 不指定时用默认模型 |
| 自定义地址 | `TranslateClient.create("bilibili", "sk-xxx", "https://your-proxy/v1")` | 走代理网关时使用 |
| 流式翻译 | `.translate(text, consumer)` | `START / STREAMING / STOP / ERROR` 事件回调 |
| 异步 + 用量 | `.translateAsync(text)` | 返回 `CompletableFuture<TranslateSyncResponse>` |
| 代理 / 超时 / 自定义头 | `TranslateClientSetting.builder()...build()` | Builder 全量配置 |
| SPI 直取 | `ServiceProvider.of(TranslateClient.class).getExtension("bilibili")` | 绕过工厂，直接按名称取实现 |

### 2.2 方法清单

下列方法定义于 `com.chua.common.support.ai.translate.TranslateClient`；静态工厂在接口内实现，实例方法由父类 `OpenAiTranslateClient` 实现（或沿用接口默认实现），本模块全部可用：

| 方法 | 说明 |
| --- | --- |
| `create(TranslateClientSetting)` | 静态工厂：按 `setting.getProvider()` 经 SPI **每次新建**实例 |
| `create(String provider, String appKey)` | 静态工厂：服务商名 + API Key |
| `create(String provider, String appKey, String baseUrl)` | 静态工厂：再加自定义地址 |
| `model(String)` | 覆盖模型名 |
| `sourceLang(String)` | 源语言，为空时由模型自动检测 |
| `targetLang(String)` | 目标语言，如 `"英语"`、`"日语"` |
| `prompt(String)` | 覆盖翻译指令模板，支持 `{text}` 占位符 |
| `temperature(double)` | 温度，翻译建议低值（默认 0.3） |
| `maxTokens(int)` | 最大输出 Token |
| `topP(double)` | Top-P 采样 |
| `stream(boolean)` | 流式开关；**同步方法内部强制非流式**，不受此开关影响 |
| `translateSync(String)` | 同步翻译，返回完整译文 |
| `translateSync(String, long timeoutMillis)` | 同步翻译带超时参数（接口默认实现忽略超时，直接走 `translateSync(String)`） |
| `translateSyncWithResponse(String)` | 同步翻译，返回含 `AiUsage` 用量（输入/输出/总 Token、缓存命中、耗时、首字延迟）的 `TranslateSyncResponse` |
| `translateAsync(String)` | 异步翻译，返回 `CompletableFuture<TranslateSyncResponse>` |
| `translate(String, Consumer<TranslateResponse>)` | 流式翻译，回调 `TranslateResponse`（`State.START / STREAMING / STOP / ERROR`） |
| `translate(String, Consumer, Runnable onComplete, Consumer<Throwable> onError)` | 流式翻译 + 完成/错误回调 |
| `close()` | 释放底层资源；`TranslateClient extends AutoCloseable`，支持 try-with-resources |
| `getSetting()` / `getModel()` / `getApiKey()` / `getBaseUrl()` | 读取配置与当前模型 |

### 2.3 默认值

| 项 | 默认值 | 来源 |
| --- | --- | --- |
| API 地址 | `https://index-translate.bilibili.com/v1` | 本模块 `defaultBaseUrl()` |
| 模型 | `Index-Translate-35B-A3B` | 本模块 `defaultModel()` |
| 目标语言（未调用 `targetLang` 时） | `English` | 父类常量 |
| 温度 | `0.3` | 父类常量 |
| maxTokens | `2048` | 父类常量 |
| 请求超时 | `90` 秒（`setting.timeoutMillis &gt; 0` 时覆盖） | 父类常量 |
| 翻译指令 | 自定义 `prompt` &gt; 源+目标语言 &gt; 仅目标语言 &gt; 默认英语；指令以英文下发，只输出译文 | 父类 `buildPrompt` |
| 认证头 | 默认 `Authorization: Bearer &lt;appKey&gt;`；可用 `authScheme`（`api_key`/`bearer`/`oauth`/`none`）、`authHeader`、`authPrefix` 覆盖 | `resolveAuthHeader()` |

---

## 3. 快速开始

### 3.1 最短可运行示例

```java
import com.chua.common.support.ai.translate.TranslateClient;

String english = TranslateClient.create("bilibili", "sk-xxx")
        .targetLang("英语")
        .translateSync("你好，世界。");
```

### 3.2 完整配置 + try-with-resources

```java
import com.chua.common.support.ai.translate.TranslateClient;
import com.chua.common.support.ai.translate.TranslateClientSetting;

TranslateClientSetting setting = TranslateClientSetting.builder()
        .provider("bilibili")              // 或 "index-translate"
        .appKey("sk-xxx")
        .targetLanguage("英语")
        .timeoutMillis(30_000L)
        .build();

try (TranslateClient client = TranslateClient.create(setting)) {
    String text = client
            .model("Index-Translate-35B-A3B")   // 不填则用默认模型
            .temperature(0.3)
            .translateSync("哔哩哔哩 Index-Translate 翻译服务。");
}
```

### 3.3 流式翻译

```java
TranslateClient.create("bilibili", "sk-xxx")
        .targetLang("日语")
        .translate("你好，世界。", response -> {
            switch (response.getState()) {
                case START     -> System.out.println("开始");
                case STREAMING -> System.out.print(response.getContent());
                case STOP      -> System.out.println("\n完成");
                case ERROR     -> System.err.println(response.getErrorMessage());
            }
        });
```

---

## 4. 配置项

**本模块没有任何 Spring 配置文件键（`application.yml` / `application.properties`）**。依据：本模块不含 Spring 依赖、无自动装配类，配置全部通过 `TranslateClientSetting` 的 Builder 在代码中传入。

`TranslateClientSetting`（定义于 `utils-support-common-starter`，非本模块）的可配置字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `provider` | `String` | SPI 名称，本模块用 `bilibili` 或 `index-translate` |
| `appKey` | `String` | API Key |
| `baseUrl` | `String` | 自定义地址；为空时用本模块默认地址 |
| `appSecret` | `String` | 备用密钥（B 站翻译场景用不到，置空即可） |
| `model` | `String` | 默认模型；也可用链式 `model(...)` 覆盖 |
| `sourceLanguage` | `String` | 默认源语言，空 = 自动检测 |
| `targetLanguage` | `String` | 默认目标语言 |
| `prompt` | `String` | 翻译指令模板，支持 `{text}` 占位符 |
| `temperature` | `Double` | 温度 [0.0, 2.0] |
| `maxTokens` | `Integer` | 最大输出 Token |
| `topP` | `Double` | Top-P |
| `proxy` | `String` | HTTP 代理（`http://host:port`）或 SOCKS5（`socks5://host:port`） |
| `timeoutMillis` | `Long` | 请求超时（毫秒），`null` 或 `&lt;=0` 用默认 90 秒 |
| `extraHeaders` | `Map&lt;String, String&gt;` | 额外请求头 |
| `authScheme` | `String` | `api_key` / `bearer` / `oauth` / `none`，空按 `api_key`（即 Bearer） |
| `authHeader` | `String` | 认证头名覆盖，空 = `Authorization` |
| `authPrefix` | `String` | 认证值前缀覆盖，空 = `Bearer ` |

---

## 5. SPI 扩展点

本模块实现的是 `com.chua.common.support.ai.translate.TranslateClient` 这个 SPI 接口。注册文件位于：

```text
src/main/resources/META-INF/extensions/com.chua.common.support.ai.translate.TranslateClient
```

内容（逐字）：

```properties
bilibili=com.chua.bilibili.support.BilibiliTranslateClient
index-translate=com.chua.bilibili.support.BilibiliTranslateClient
```

查找链路：`TranslateClient.create(...)` → `ServiceProvider.of(TranslateClient.class).getNewExtension(provider, setting)` → 按名称反射新建实例。**每次 `create` 都是新实例**，需要复用请自行持有引用。

如需接入其它 OpenAI 兼容服务商，照抄本模块的模式即可：

1. 新建子类继承 `OpenAiTranslateClient`，只覆盖 `defaultBaseUrl()` 与 `defaultModel()`；
2. 类上加 `@Spi({"你的名称"})`；
3. 在自己模块的 `META-INF/extensions/com.chua.common.support.ai.translate.TranslateClient` 中登记 `名称=全限定类名`。

---

## 6. 构建与依赖

| 依赖 | 作用 |
| --- | --- |
| `com.chua:utils-support-common-starter` | `TranslateClient` 接口、`TranslateClientSetting`、SPI 框架 |
| `com.chua:utils-support-openai-starter` | `OpenAiTranslateClient` 父类及 openai-java SDK（协议栈） |
| `org.slf4j:slf4j-api` | 日志 |
| `org.projectlombok:lombok`（provided） | 编译期生成 |

编译验证：

```bash
mvn -o -q compile -DskipTests
```

在 `utils-support-parent-starter` 仓库根目录或本模块目录下执行，`EXIT=0` 即通过。

---

## 7. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| B 站站内业务（视频、弹幕、直播、登录、用户等 API） | 本模块不含；本模块只对接 `index-translate.bilibili.com` 的翻译服务 |
| AI 对话、Embedding、RAG、图像/音频生成等其它 AI 能力 | `utils-support-common-starter`（契约）与各 AI starter |
| OpenAI / 硅基流动等其它 OpenAI 兼容服务商的翻译 | `utils-support-openai-starter`（`@Spi({"openai", "siliconflow", ...})`） |
| Spring Boot 自动装配、配置键、Controller/Endpoint | 本模块没有；它是纯 SPI 工具模块 |
| HTTP 连接管理 | 由 openai-java SDK（OkHttp）承担，不经由本仓 `HttpClientFactory` |
| API Key 的申请、托管与续期 | 不在本模块职责内 |
