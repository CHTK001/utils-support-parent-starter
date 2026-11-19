# utils-support-httpclient-starter

> Apache HttpClient5 的 **HTTP 客户端执行器 SPI 实现**：把 `org.apache.httpcomponents.client5:httpclient5` 接入 utils 体系统一的 `HttpClientExecutor` SPI，使 `HttpClientFactory` 可以按名称 `httpclient5` 选用 Apache HttpClient5 作为 HTTP 底层。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.httpclient.support.executor`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 工作原理](#4-工作原理)
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
    <artifactId>utils-support-httpclient-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `HttpClientExecutor` SPI 的 **httpclient5 实现**（SPI 名称 `httpclient5`，优先级 `order=1`） | 不是 HTTP 门面本身；`HttpClient` / `HttpClientFactory` / `ClientRequest` / `ClientResponse` 与 SPI 接口都在 `utils-support-common-starter` |
| 实现 | 单一实现类 `HttpClient5Executor`：方法映射、请求头/请求体转换、超时与代理、响应转 `ClientResponse` | 不提供连接池、SSL、鉴权等 HttpClient5 细粒度定制（客户端由 `HttpClients.createDefault()` 创建） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `org.apache.httpcomponents.client5:httpclient5` | 不依赖 Spring，无自动装配，无配置文件 |
| 注册 | 通过 `META-INF/extensions/` 声明 + `@Spi("httpclient5")` + `@ConditionalOnClass` 按需生效 | 不通过 Spring Bean / spring.factories 注册 |

**判断口径**：要在统一 HTTP 门面下使用 Apache HttpClient5 → 引入本模块；要换底层 → 用 `HttpClientFactory.getClient("okhttp" / "jdk" / ...)`，机制见 `utils-support-common-starter/README.md` 第 7.1 节。

### 1.3 规模

- Java 源文件 **1** 个：`HttpClient5Executor.java`（类上 `@Spi("httpclient5")`、`@ConditionalOnClass("org.apache.hc.client5.http.classic.methods.HttpGet")`，`@since 4.0.0.42`）。
- SPI 扩展声明 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor`，内容为 `httpclient5=com.chua.httpclient.support.executor.HttpClient5Executor`。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`（注册 `HttpClient5Executor` 的全部构造器/方法/字段）与 `resource-config.json`。

---

## 2. 核心 API 与速查表

本模块对外只有一个类 `HttpClient5Executor`，实现 SPI 接口 `com.chua.common.support.network.client.spi.HttpClientExecutor`。日常使用入口是 common-starter 的 `HttpClientFactory`。

| # | 需求 | 入口 | 说明 |
| --- | --- | --- | --- |
| 1 | 强制用 HttpClient5 发请求 | `HttpClientFactory.getClient("httpclient5")` | 返回已套用拦截器链的 `HttpClient` |
| 2 | 自动选择底层 | `HttpClientFactory.getClient()` / `HttpClientFactory.of(url)` | 按 okhttp(0) → **httpclient5(1)** → httpclient → jdk(100) 取第一个 `isAvailable()==true` 的执行器 |
| 3 | 同步执行 | `HttpClient5Executor.execute(ClientRequest)` | SPI 契约方法，返回统一 `ClientResponse` |
| 4 | GET / POST 快捷链 | `HttpClientFactory.of(url).get()`、`HttpClientFactory.of(url).json().body(...).post()` | 链式构建，最终仍由本执行器执行 |
| 5 | 探测可用性 | `executor.isAvailable()` | 反射加载 `org.apache.hc.client5.http.classic.methods.HttpGet`，成功即可用 |
| 6 | 名称与优先级 | `getName()` → `"httpclient5"`、`getOrder()` → `1` | 值越小越优先，仅次于 okhttp |
| 7 | 异步执行 | `executeAsync(request)`（接口 default） | 本执行器**未覆写**，默认把同步 `execute` 包成 Reactor `Mono`（`boundedElastic`） |
| 8 | 释放资源 | `executor.close()` | 关闭内部 `CloseableHttpClient`；失败仅 debug 日志，不抛出 |

`HttpClient5Executor` 自身的方法全集（与源码一致）：

| 方法 | 签名要点 | 行为 |
| --- | --- | --- |
| `getName()` | 返回 `String` | 固定 `"httpclient5"` |
| `isAvailable()` | 返回 `boolean` | `ReflectUtils.forName("org.apache.hc.client5.http.classic.methods.HttpGet")` 成功 → `true`，任何异常 → `false` |
| `execute(ClientRequest)` | 声明 `throws Exception`，实际把异常包成 `RuntimeException("HttpClient5执行失败", e)` | 构造请求 → 执行 → 转 `ClientResponse` |
| `getOrder()` | 返回 `int` | 固定 `1` |
| `close()` | `void` | 关闭 `client`；异常时 `log.debug("[httpclient] 关闭 HTTP 客户端失败，已忽略: {}", ...)` |
| `executeAsync(ClientRequest)` | 继承接口 default | 未覆写：`Mono.fromFuture(CompletableFuture.supplyAsync(...))` 包装同步执行 |
| `supportedVersions()` | 继承接口 default | 未覆写：默认支持 `HTTP_1_1`、`HTTP_2` |

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-httpclient-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

引入后 SPI 自动发现本执行器；无需任何配置。

### 3.2 最短可运行示例（GET）

```java
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;

// 1. 强制使用本模块的 httpclient5 执行器
HttpClient hc5 = HttpClientFactory.getClient("httpclient5");

// 2. 链式构建并同步执行
ClientResponse resp = hc5.request("https://api.example.com/users", HttpMethod.GET)
        .header("Authorization", "Bearer token")
        .query("page", "1")
        .execute();

// 3. 读取统一响应
System.out.println(resp.isSuccess());      // 是否 2xx
System.out.println(resp.getStatusCode()); // 状态码
System.out.println(resp.getBodyString());  // 响应体文本
```

### 3.3 POST JSON

```java
ClientResponse created = HttpClientFactory.of("https://api.example.com/users")
        .json()
        .body("{\"name\":\"张三\"}")
        .post();
```

> `HttpClientFactory.of(...)` 走全局自动选择的执行器：classpath 上有本模块且未引入 okhttp 时，选中的就是 `httpclient5`。

---

## 4. 工作原理

`execute(ClientRequest)` 内部两步转换（均为源码行为）：

**① `ClientRequest` → HttpClient5 请求（`toRequest`）**

| ClientRequest 字段 | 映射 |
| --- | --- |
| `method` | `POST/PUT/DELETE/HEAD/OPTIONS/PATCH` 分别映射 `HttpPost/HttpPut/HttpDelete/HttpHead/HttpOptions/HttpPatch`，**其余一律按 GET**（`HttpGet`） |
| `url` | 直接作为请求 URI |
| `headers` | `headers.toMap()` 逐条 `setHeader` |
| `body` | `byte[]` → `ByteArrayEntity(ContentType.DEFAULT_BINARY)`；其它非 null 对象 → `StringEntity(body.toString(), ContentType.DEFAULT_TEXT)`；null → 无请求体 |
| `connectTimeout` / `readTimeout` | `RequestConfig.setConnectTimeout` / `setResponseTimeout`（毫秒） |
| `followRedirects` | `RequestConfig.setRedirectsEnabled` |
| `keepAliveTimeout` | `> 0` 时 `setDefaultKeepAlive(keepAliveTimeout, TimeUnit.MILLISECONDS)` |
| `proxyHost` / `proxyPort` | `proxyHost` 非空时 `RequestConfig.setProxy(new HttpHost(proxyHost, proxyPort))` |

**② HttpClient5 响应 → `ClientResponse`（`toClientResponse`）**

- `statusCode = resp.getCode()`；
- 响应体：`entity.getContent().readAllBytes()`（entity 为 null 时无 body）；
- 响应头：逐条 `cr.getHeaders().add(name, value)`。

客户端实例：构造时 `HttpClients.createDefault()`；`execute` 中若 `client == null` 会重新创建（惰性兜底）。

---

## 5. 配置项

**无。**

依据：本模块仅一个源文件 `HttpClient5Executor`，源码中没有读取任何配置属性（无 `@ConfigurationProperties`、无 `Environment`/`PropertySource`、无 `application.yml` 键），pom 中也没有配置类或自动装配；模块本身不是 Spring starter，一切行为参数都通过每次请求的 `ClientRequest` 传入。

可直接通过 `ClientRequest`（链式 API）控制的参数及默认值（默认值定义于 common-starter 的 `ClientRequest`）：

| 参数 | 默认值 | 本执行器是否消费 |
| --- | --- | --- |
| `connectTimeout` | `30000` ms | 是 |
| `readTimeout` | `30000` ms | 是（映射为 responseTimeout） |
| `keepAliveTimeout` | `60000` ms | 是（`>0` 时生效） |
| `followRedirects` | `true` | 是 |
| `proxyHost` / `proxyPort` | 无代理 | 是 |
| `method` / `url` / `headers` / `body` | GET / 必填 / 空 / null | 是 |
| `writeTimeout` / `version` / `cacheTtl` / `maxRetries` / `params` / `redirectHandler` / `interceptor` | — | **否**（`HttpClient5Executor` 源码未读取这些字段） |

---

## 6. SPI 扩展点

本模块**是** SPI 扩展的实现方，不是扩展点的定义方：

- 接口：`com.chua.common.support.network.client.spi.HttpClientExecutor`（定义于 `utils-support-common-starter`）；
- 本模块注册名：`httpclient5`（注册文件 `META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor`）；
- 同一接口的其它实现：`jdk`（common-starter 内置 `JdkHttpClientExecutor`）、`okhttp`（`utils-support-okhttp-starter`）；SPI 名 `httpclient`（HttpClient4）不由本模块提供，源码未提供实现。

自定义执行器（替换或新增底层）的标准写法：

```java
@Spi("my-http")
public class MyExecutor implements HttpClientExecutor {
    @Override public String getName() { return "my-http"; }
    @Override public boolean isAvailable() { return true; }
    @Override public int getOrder() { return 50; }   // 越小越优先
    @Override public ClientResponse execute(ClientRequest request) throws Exception {
        // 用任意 HTTP 栈发请求，转成统一 ClientResponse
    }
}
```

并在自己的 `src/main/resources/META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor` 中登记：`my-http=com.example.MyExecutor`。

---

## 7. 构建与依赖

```text
utils-support-parent-starter (com.chua:4.0.0.51, JDK 25)
└── utils-support-network-parent
    └── utils-support-httpclient-starter
        ├── com.chua:utils-support-common-starter  (${project.version}，SPI 接口与门面)
        └── org.apache.httpcomponents.client5:httpclient5  (${httpclient5.version} = 5.6.4，由根 pom dependencyManagement 统一托管)
```

在模块目录内编译：

```bash
mvn -o -q compile -DskipTests
```

GraalVM 原生镜像：`src/main/resources/META-INF/native-image/com.chua/utils-support-httpclient-starter/` 下的 `reflect-config.json` 已注册 `HttpClient5Executor`（全部构造器、方法、字段），`resource-config.json` 保留 SPI 声明文件资源。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 / 说明 |
| --- | --- |
| HTTP 门面、链式 API、cURL 互转、请求合并、TTL 缓存 | `utils-support-common-starter`（`HttpClient` / `HttpClientFactory` / `HttpClientBuilder` / `CollapseHttpClient` / `TtlResponseCache` / `HttpApiFactory`） |
| `ClientRequest` / `ClientResponse` / `HttpClientExecutor` 契约 | `utils-support-common-starter` |
| 其它底层实现 | `okhttp` → `utils-support-okhttp-starter`；`jdk` → common-starter 内置；`httpclient`（HttpClient4）→ 源码未提供实现 |
| 连接池大小、SSL/TLS、鉴权、域名解析等 HttpClient5 细粒度配置 | 本模块未暴露：客户端固定 `HttpClients.createDefault()` |
| multipart / 文件上传编码 | 本执行器请求体只区分 `byte[]`（二进制）与 `toString()`（文本）两类；`MultipartBody` 不会被编码为 multipart，而是走 `toString()` |
| 真 NIO 异步 | 本执行器未覆写 `executeAsync`，异步是同步执行的 `boundedElastic` 包装 |
| 查询参数拼接（`params`）、重试（`maxRetries`）、缓存（`cacheTtl`）、HTTP 版本协商（`version`） | `HttpClient5Executor` 源码未读取这些字段，行为以 common-starter 的 `DefaultHttpClient` 链路为准 |
| Spring 自动装配 / 配置项 | 无：本模块是纯 SPI 模块，引入即注册，零配置 |
