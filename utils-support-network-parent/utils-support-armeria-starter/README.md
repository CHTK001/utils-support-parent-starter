# utils-support-armeria-starter

> utils 网络子体系的 **Armeria HTTP 服务端实现**：把 Armeria 的高性能异步 HTTP 服务器接入 `Server` SPI 统一抽象，与 `jdk` / `nio` / `aio` 等实现可互换，相比 JDK HttpServer 具备更高的并发能力。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.armeria.support.server`｜Armeria：`1.42.0`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 实现要点](#4-实现要点)
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
    <artifactId>utils-support-armeria-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.network.server.Server` SPI 的 **HTTP 协议实现**（`ArmeriaHttpServer`，SPI 名 `armeria-http`） | 不是 HTTP 客户端（客户端见 `utils-support-common-starter` 的 `HttpClientFactory`） |
| 能力 | Armeria 事件循环 + 每请求虚拟线程、TLS（PEM 证书/私钥）、请求体上限、统一过滤器链、Reactor 能力、表单/multipart 解析 | 不是 Spring Web 容器，无自动装配类（仓库内 `starter` 仅为命名约定） |
| 依赖 | 只依赖 `utils-support-common-starter` + `com.linecorp.armeria:armeria` + `io.netty:netty-all` | 不依赖任何 `spring-*` 上层模块 |

**判断口径**：需要「比 JDK HttpServer 更高并发能力」的 HTTP 服务端时选本模块；使用方式与其它 `Server` 实现完全一致，仅 `type("armeria-http")` 不同。

### 1.3 规模

- 单个实现类 `ArmeriaHttpServer`（含内部类 `ArmeriaServerRequest` / `ArmeriaServerResponse`），位于 `com.chua.armeria.support.server` 包。
- 自 `4.0.0.42` 起提供（类注释 `@since 4.0.0.42`）。
- 内置 GraalVM `native-image` 元数据（`reflect-config.json` / `proxy-config.json` / `resource-config.json`）。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起 Armeria HTTP 服务 | `ServerBuilder.create().type("armeria-http")...build()` | `type` 命中本模块的 SPI 名称 |
| 直接构造 | `new ArmeriaHttpServer(ServerSetting)` | 构造参数为服务器配置对象 |
| 生命周期 | `server.start()` / `stop()` / `isRunning()` / `close()` | 继承自 `Server` / `AbstractServer` |
| 注册路由 | `server.registerMapping(path, method, handler)` 或 `ServerBuilder.mapping(...)` | 由统一过滤器链中的 URL 映射过滤器完成路由 |
| 读取请求 | `ArmeriaServerRequest`：`getBody()` / `getParams()` / `getFormData()` / `getFiles()` 等 | 实现 `ServerRequest` 接口 |
| 写出响应 | `ArmeriaServerResponse`：`setStatus()` / `setHeader()` / `setBody()` / `setResult()` 等 | 实现 `ServerResponse` 接口 |
| 启用 HTTPS | `ServerSetting.SslConfig`（`enabled` + `certPath` + `keyPath`） | PEM 证书/私钥，映射到 Armeria 的 TLS 配置 |
| 运行指标 | `server.getMetrics()` | 返回 `ServerMetrics`：请求数、活跃数、错误数、连接数等 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-armeria-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;

public class ArmeriaDemo {
    public static void main(String[] args) {
        Server server = ServerBuilder.create()
                .type("armeria-http")                       // 使用本模块的 Armeria 实现
                .host("0.0.0.0")
                .port(8080)
                .mapping("/hello", HttpMethod.GET,
                        (req, resp) -> resp.setResult("Hello Armeria"))
                .build();

        server.start();
        System.out.println(server.getServerUrl());          // http://localhost:8080/

        // ... 业务运行期间 ...

        server.stop();
    }
}
```

说明：

- `ServerBuilder.type("armeria-http")` 内部通过 `ServiceProvider.of(Server.class).getNewExtension(type, setting)` 创建 `ArmeriaHttpServer`；
- 路由匹配由 common-starter 的统一过滤器链处理：未命中返回 `404`，命中后执行 handler；
- `resp.setResult(...)` 的结果会派生为响应体：`String` → UTF-8 字节、`byte[]` → 原样、`java.nio.file.Path` → 读取文件字节、其他 → `toString()` 的 UTF-8 字节；仅在未调用 `setBody(...)` 时生效，不会覆盖显式设置的响应体。

### 3.3 启用 HTTPS（PEM 证书）

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.ServerSetting.SslConfig;
import com.chua.armeria.support.server.ArmeriaHttpServer;

ServerSetting setting = ServerSetting.defaults();
setting.setPort(8443);
setting.setSsl(SslConfig.builder()
        .enabled(true)
        .certPath("cert.pem")     // PEM 证书
        .keyPath("key.pem")       // PEM 私钥
        .build());

Server server = new ArmeriaHttpServer(setting);
server.start();
```

---

## 4. 实现要点

以下要点均来自 `ArmeriaHttpServer` 源码：

- **线程模型**：Armeria 事件循环只负责收发；请求处理统一投递到「每任务一个虚拟线程」的执行器（`Executors.newVirtualThreadPerTaskExecutor()`），避免阻塞事件循环。
- **请求入口**：`ServerBuilder.serviceUnder("/", ...)` 接管全部路径 → 聚合请求体（`AggregatedHttpRequest`）→ 包装为 `ArmeriaServerRequest` / `ArmeriaServerResponse` → 走 `handleRequestWithStage(...)` 的统一过滤器链 → 等待链路完成后构建 `AggregatedHttpResponse` 返回。请求聚合失败时回 `500`。
- **随机端口**：`port=0` 时由 Armeria 自动分配端口，启动后回写 `setting.setPort(...)`，保证 `getPort()` 返回真实端口。
- **Reactor 能力**：`supportsReactor()` 返回 `true`；所有请求均经由响应式过滤器链（`handleRequestWithStage`）处理。`ServerSetting#reactor` 字段本实现未直接读取。
- **流加密开关**：`ServerSetting#encrypt=true` 时启动即抛 `IllegalStateException`——本实现不做流加密，错误信息提示改用 `ssl`（`certPath`/`keyPath`）启用 TLS。
- **TLS**：仅消费 `SslConfig` 的 `enabled` + `certPath` + `keyPath`（PEM 文件），映射到 Armeria 的 `ServerBuilder.tls(File, File)`；配置失败抛 `RuntimeException("SSL 配置失败")`。`keyStorePath`、`selfSigned*` 等其余 `SslConfig` 字段本实现未读取（源码未提供对应逻辑）。
- **表单/文件解析**：`application/x-www-form-urlencoded` 由本模块直接解析（UTF-8 URL 解码）；`multipart/form-data` 委托给 SPI `MultipartParser`（名称 `fileupload`）。
- **响应占位方法**：`getOutputStream()` 返回的流、`writeRaw(...)`、`flush()` 当前不会回写到响应体（源码现状）；请使用 `setBody(...)` / `setResult(...)`。

---

## 5. 配置项

本模块**没有独立的配置文件或配置键**（源码中无 `application*.yml/properties`、无 `@ConfigurationProperties`）；所有配置通过 common-starter 的 `ServerSetting` 承载，经 `ServerBuilder` 或构造函数传入。本实现实际读取的字段：

| `ServerSetting` 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `port` | 监听端口；`0` = 随机端口（启动后回写实际端口） | `8080` |
| `ssl.enabled` / `ssl.certPath` / `ssl.keyPath` | 启用 TLS 的 PEM 证书与私钥（三者需同时就绪） | `enabled=false` |
| `maxRequestSize` | 最大请求体，映射到 Armeria 的 `maxRequestLength` | `10 * 1024 * 1024`（10MB） |
| `encrypt` | 流加密开关；本实现**不支持**，设为 `true` 启动即失败 | `false` |
| `host` | 监听地址；本实现仅按端口监听（`sb.http(port)`），**未消费**该字段 | `0.0.0.0` |

其他与 `Server` 体系相关的字段：`maxConcurrency` 由基类 `AbstractServer` 的并发闸门消费（超过上限的请求直接回 `503`，并非 Armeria 专属配置）；`reactor`、`maxConnections` 本实现未消费。各字段语义以 `utils-support-common-starter` 的 `ServerSetting` 源码为准。

固定行为（源码硬编码，**无配置项**）：请求超时为 60 秒（`requestTimeout(Duration.ofSeconds(60))`）。

---

## 6. SPI 扩展点

### 6.1 本模块提供的实现

| SPI 接口 | 名称 | 实现 | 注册方式 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `armeria-http` | `com.chua.armeria.support.server.ArmeriaHttpServer` | 类上 `@Spi({"armeria-http"})` + 注册文件 `META-INF/services/com.chua.common.support.network.server.Server` |

### 6.2 本模块消费的 SPI

| SPI 接口 | 名称 | 用途 | 缺失时行为 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.server.request.MultipartParser` | `fileupload` | 解析 `multipart/form-data` 的表单字段（`parseFormFields`）与文件（`parse`，元素为 `FormFile`） | `getFormData()` / `getFiles()` 对 multipart 请求返回空 `Map` / 空 `List` |

> multipart 解析器实现不在本模块；使用文件上传时需引入提供 `MultipartParser("fileupload")` 的实现模块。

### 6.3 扩展建议

本模块定位是单一协议实现，**无对外扩展点**。要新增协议（如 WebSocket、gRPC 服务端）应另建 `*-starter` 实现 `Server` / `AbstractServer` 并用 `@Spi` 注册，而不是修改本模块。

---

## 7. 构建与依赖

### 7.1 依赖关系

```text
utils-support-armeria-starter (com.chua:4.0.0.51)
├── com.chua:utils-support-common-starter        # Server SPI、ServerSetting、统一过滤器链
├── com.linecorp.armeria:armeria                 # 1.42.0（根 pom dependencyManagement 统一管理）
└── io.netty:netty-all                           # 版本由 ${netty.all.version} 统一管理
```

- 父模块：`utils-support-network-parent`（聚合于 `utils-support-parent-starter`）。
- JDK：`25`（`maven.compiler.source` / `maven.compiler.target` = 25）。

### 7.2 构建命令

```bash
mvn -o -q compile -DskipTests        # 仓库标准编译闸门
```

测试：本模块当前无测试源码；pom 中「单元测试」相关内容仅为注释占位，未声明测试依赖。

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-armeria-starter/` 内置三份元数据：

| 文件 | 内容 |
| --- | --- |
| `reflect-config.json` | 注册 `ArmeriaHttpServer` 的全部构造器/方法/字段 |
| `proxy-config.json` | 注册代理接口 `com.chua.common.support.network.server.Server` |
| `resource-config.json` | 包含 `META-INF/services/.*`，保证 ServiceLoader 注册文件进入原生镜像 |

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| HTTP 客户端（同步/异步、cURL 互转） | `utils-support-common-starter` 的 `HttpClientFactory`；底层实现见 `utils-support-okhttp-starter` / `utils-support-httpclient-starter` |
| 其它 HTTP 服务端实现（`jdk` / `nio` / `aio`） | `utils-support-common-starter` |
| 路由匹配、注解处理器、参数解析、内置过滤器（gzip / cors / 安全头等） | `utils-support-common-starter` 的统一过滤器链 |
| `multipart/form-data` 解析器（`fileupload`） | 其它提供 `MultipartParser` 的模块；本模块只调用 SPI |
| WebSocket / SSE / gRPC 等协议服务端 | 源码未提供；本模块仅按 `ProtocolType.HTTP` 提供通用 HTTP 服务 |
| 流式响应写出 | 源码未提供有效实现：`getOutputStream()` / `writeRaw()` / `flush()` 为占位，请用 `setBody(...)` / `setResult(...)` |
| `ServerSetting#encrypt` 流加密 | 不支持，启动即失败；传输加密请走 `ssl.certPath` / `ssl.keyPath` |
| 请求超时调整 | 固定 60 秒，源码硬编码，未暴露配置项 |
| KeyStore / 自签名证书等 SSL 配置 | `SslConfig` 的 `keyStorePath` / `selfSigned*` 字段本实现未消费（源码未提供） |
