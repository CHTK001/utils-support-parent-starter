# utils-support-okhttp-starter

> `utils` 网络体系的 **OkHttp3 底层执行器模块**：把 `HttpClientExecutor`（普通 HTTP 请求）与 `SseClient`（SSE 事件流）两个 SPI 契约落到 OkHttp3 上。引入本模块后，`HttpClientFactory` 与 `SseClient.create()` 会优先选中 `okhttp` 实现。
>
> 版本：`4.0.0.51`（类级 `@since 4.0.0.42`）｜JDK：`25`（`maven.compiler.source/target=25`，全仓 `--enable-preview`）｜OkHttp：`4.12.0`（`${okhttp.version}`）｜包名：`com.chua.okhttp.support`（子包 `executor`、`sse`）

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

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `HttpClientExecutor` 与 `SseClient` 两个 SPI 的 **OkHttp3 实现**，SPI 名称均为 `okhttp` | 不是 HTTP 门面：统一入口 `HttpClientFactory` / `SseClient` 契约在 `utils-support-common-starter` |
| HTTP | `OkHttpExecutor`：同步执行 `ClientRequest`，返回 `ClientResponse`（状态码、字节数组响应体、响应头） | 不做异步 `Mono`、拦截器链、请求合并、TTL 缓存等上层能力（由 common 层 `DefaultHttpClient` 编排） |
| SSE | `OkHttpSseClient`：建立连接后在**虚拟线程**中逐行读取 `data:` 行，回调 `SseListener` | 不实现 SSE 自动重连（`SseRequest.reconnect` 字段在本实现源码中未被读取） |
| 激活条件 | 类上标注 `@ConditionalOnClass("okhttp3.OkHttpClient")`，classpath 无 OkHttp3 时自动跳过 | 不强制绑定：不引本模块时上层按 `okhttp → httpclient5 → httpclient → jdk` 顺序回退 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `com.squareup.okhttp3:okhttp` | 不依赖 Spring、不依赖任何业务 starter |

模块极小：`src/main/java` 仅 2 个类，`src/main/resources` 仅 2 个 SPI 登记文件与 2 个 GraalVM native-image 配置。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 强制用 OkHttp 发 HTTP 请求 | `HttpClientFactory.getClient("okhttp")` | 返回本模块 `OkHttpExecutor` 支撑的 `HttpClient` |
| 自动选型（含 OkHttp） | `HttpClientFactory.getClient()` / `HttpClientFactory.of(url)` | 按执行器顺序取第一个可用者，本模块在列 |
| 执行器 SPI 名称 | `"okhttp"`（`HttpClientExecutor`） | 登记于 `META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor` |
| SSE 客户端（指定 OkHttp） | `ServiceProvider.of(SseClient.class).getExtension("okhttp")` | 返回本模块 `OkHttpSseClient` |
| SSE 客户端（自动选型） | `SseClient.create()` | 优先级 OkHttp > HttpClient5 > JDK（见 `SseClient#create` 契约） |
| SSE 便捷连接 | `SseClient.get(url, listener)` / `SseClient.post(url, body, listener)` | 默认实现直接连，无需手动构建 `SseRequest` |
| HTTP 代理 | 链式 `.proxy(host, port)` → `ClientRequest.setProxy` | `OkHttpExecutor` 读取 `getProxyHost()/getProxyPort()` 创建 `Proxy.Type.HTTP` |
| 释放执行器资源 | `OkHttpExecutor#close()` | 关闭 OkHttp 调度线程池并 `evictAll()` 连接池 |

核心类型只有两个：

| 类 | 实现契约 | SPI 名 | 关键方法 |
| --- | --- | --- | --- |
| `com.chua.okhttp.support.executor.OkHttpExecutor` | `HttpClientExecutor` | `okhttp` | `getName()`、`isAvailable()`、`execute(ClientRequest)`、`getOrder()`（返回 0）、`close()` |
| `com.chua.okhttp.support.sse.OkHttpSseClient` | `SseClient` | `okhttp` | `connect(SseRequest, SseListener)`（另继承默认 `getConnection()`） |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-okhttp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 发一个 HTTP 请求（走 OkHttp）

```java
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.http.HttpMethod;

HttpClient okhttp = HttpClientFactory.getClient("okhttp");   // 强制选中本模块
ClientResponse resp = okhttp.request("https://api.example.com/users", HttpMethod.GET)
        .header("Authorization", "Bearer token")
        .execute();

if (resp.isSuccess()) {
    System.out.println(resp.getBodyString());
}
```

### 3.3 订阅 SSE 事件流（走 OkHttp）

```java
import com.chua.common.support.network.sse.SseClient;
import com.chua.common.support.network.sse.SseConnection;
import com.chua.common.support.network.sse.SseRequest;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.spi.ServiceProvider;

SseClient client = ServiceProvider.of(SseClient.class).getExtension("okhttp");

SseRequest request = SseRequest.builder()
        .url("https://api.example.com/stream")
        .method(HttpMethod.POST)
        .header("Authorization", "Bearer sk-xxx")
        .body("{\"prompt\":\"hello\"}")
        .build();

SseConnection conn = client.connect(request, new SseListener() {
    @Override
    public void onData(String data) {
        System.out.println("收到: " + data);
    }
    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});

conn.close();   // 主动断开；多次调用安全
```

### 3.4 行为要点（与源码一致）

- **HTTP 默认值**：连接超时 30s、读取超时 30s、跟随重定向；每次 `execute` 按请求覆盖 `connectTimeout`/`readTimeout`（毫秒）、`isFollowRedirects()`、`getKeepAliveTimeout()`（连接池空闲存活时间，池大小 5），并在设置 `proxyHost` 时挂 HTTP 代理。
- **请求体**：`byte[]` 按 `application/octet-stream` 发送；其余对象 `toString()` 按 `text/plain; charset=utf-8`；POST/PUT 无体时补空 `text/plain` 体。
- **失败语义**：`IOException` 包装为 `RuntimeException("OkHttp执行失败", e)` 抛出。
- **SSE 默认值**：连接超时 30s、读取超时 0（长连接不超时），按请求的 `connectTimeout`/`readTimeout`（毫秒）覆盖。
- **SSE 解析**：仅处理 `data: ` 前缀行，跳过空数据与 `[DONE]`；读取线程名为 `sse-reader-okhttp` 的虚拟线程；流正常结束且连接未关闭时回调 `onComplete()`，异常时回调 `onError()`，`finally` 中关闭响应。
- **SSE 失败语义**：非 2xx 或空响应体不抛异常，回调 `onError(...)` 并返回 `isConnected()==false` 的关闭连接。

---

## 4. 配置项

**无。**

依据：本模块 `src/main/resources` 下只有 2 个 SPI 登记文件与 2 个 native-image 配置，没有任何 `application*.properties`/`*.yml`，源码中也没有 `@ConfigurationProperties` 之类的配置绑定。全部行为参数（超时、重定向、代理、请求头、请求体等）都**按请求**通过 `ClientRequest` / `SseRequest` 传入，模块本身不持有全局配置。

---

## 5. SPI 扩展点

本模块**提供 SPI 实现**，不定义新的扩展点。两个实现均以 `@Spi("okhttp")` + `@ConditionalOnClass("okhttp3.OkHttpClient")` 声明，并在 `META-INF/extensions/` 登记：

| 契约接口（定义于 common-starter） | 登记文件 | 登记 key | 实现类 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.client.spi.HttpClientExecutor` | `META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor` | `okhttp` | `com.chua.okhttp.support.executor.OkHttpExecutor` |
| `com.chua.common.support.network.sse.SseClient` | `META-INF/extensions/com.chua.common.support.network.sse.SseClient` | `okhttp` | `com.chua.okhttp.support.sse.OkHttpSseClient` |

想换成别的底层（例如 Netty）时，不要改本模块：按 common-starter 的契约自行实现 `HttpClientExecutor` 或 `SseClient`，在自己的 `META-INF/extensions/<接口全限定名>` 里登记即可，`HttpClientFactory` / `SseClient.create()` 会按可用性与优先级装配。

GraalVM native-image：`META-INF/native-image/com.chua/utils-support-okhttp-starter/` 下的 `reflect-config.json` 注册了 `OkHttpExecutor` 的全部构造器/方法/字段反射，`resource-config.json` 放行 `META-INF/extensions/.*` 资源。

---

## 6. 构建与依赖

父模块：`com.chua:utils-support-network-parent:4.0.0.51`（聚合于 `utils-support-parent-starter`）。

```text
utils-support-okhttp-starter
├── com.chua:utils-support-common-starter        （SPI 契约与门面，${project.version}）
└── com.squareup.okhttp3:okhttp:4.12.0           （${okhttp.version}）
```

- 编译：`mvn -o -q compile -DskipTests`（JDK 25，`--enable-preview`）。
- 运行前置条件：classpath 上存在 `okhttp3.OkHttpClient`；否则两个实现因 `@ConditionalOnClass` 均不生效，上层自动回退到其它执行器。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `HttpClientFactory`、`HttpClient`、`ClientRequest` / `ClientResponse` 契约 | `utils-support-common-starter` |
| `SseClient` / `SseRequest` / `SseListener` / `SseConnection` 契约与 JDK 实现 | `utils-support-common-starter` |
| HttpClient5 执行器（SPI `httpclient5`） | `utils-support-httpclient-starter` |
| 拦截器链、请求合并（`CollapseHttpClient`）、TTL 缓存、cURL 互转等门面能力 | common 层 `DefaultHttpClient` 一侧，本模块只被其委托 |
| SSE 自动重连 | 未实现：`SseRequest.reconnect` 字段在本模块源码中未被读取，断开后不会自动重连 |
| 鉴权、重试、限流等业务策略 | 上层调用方自行编排，本模块只做协议执行 |
