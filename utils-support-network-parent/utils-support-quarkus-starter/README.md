# utils-support-quarkus-starter

> utils 网络子体系的 **Quarkus 风格 HTTP 服务端实现**：基于 Vert.x（Quarkus 底层 HTTP 引擎）接入 `Server` SPI 统一抽象，与 `jdk` / `nio` / `aio` / `armeria-http` 等实现可互换；并提供 JAX-RS 注解路由解析与 CDI / MicroProfile 配置注解的桥接。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.quarkus.support.*`｜Vert.x：`5.2.0`（`${vertx.version}`）

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
    <artifactId>utils-support-quarkus-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.network.server.Server` SPI 的 **HTTP 协议实现**（`QuarkusHttpServer`，SPI 名 `quarkus-http` / `quarkus`），基于 Vert.x | 不是 Quarkus 框架本身：不含 Quarkus 运行时、打包（`quarkus:*` 插件）或部署能力 |
| 能力 | 同步阻塞 / 响应式两种执行模式、TLS（PEM 证书/私钥）、请求体上限、文件上传、SSE 推送、统一过滤器链 | 不是 HTTP 客户端（客户端见 `utils-support-common-starter`） |
| 对象体系 | 解析 JAX-RS `@Path` + `@GET/@POST/@PUT/@DELETE/@PATCH` 路由；桥接 `@Inject` / `@Named` / `@ConfigProperty` 到 common-starter 的对象容器 | 不是 Bean 的创建与生命周期管理器：Bean 实例由 CDI 容器管理，`BeanDefinitionRegister` 只读桥接 |
| 依赖 | 只依赖 `utils-support-common-starter` + `io.vertx:vertx-core` + `io.vertx:vertx-web` | 不依赖任何 `spring-*` 上层模块；无 Spring 自动装配 |

**判断口径**：需要 Quarkus 风格（Vert.x 引擎）HTTP 服务端、或已有 JAX-RS / CDI 注解的 Bean 想直接暴露为 HTTP 服务时选本模块；使用方式与其它 `Server` 实现一致，仅 `type("quarkus-http")`（或 `"quarkus"`）不同。

### 1.3 规模

- 6 个实现类，位于 3 个包：
  - `com.chua.quarkus.support.server`：`QuarkusHttpServer`（含内部类 `QuarkusServerRequest` / `QuarkusServerResponse`）；
  - `com.chua.quarkus.support.network`：`QuarkusServerHandlerAnnotationParser`；
  - `com.chua.quarkus.support.objects.*`：`QuarkusBeanConstructorResolver` / `QuarkusBeanDefinitionRegister` / `QuarkusBeanDefinitionMethodInjector` / `QuarkusBeanDefinitionConfigInjector`。
- 自 `4.0.0.42` 起提供（类注释 `@since 4.0.0.42`）。
- 内置 GraalVM `native-image` 元数据（`reflect-config.json` / `resource-config.json`）。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起 Quarkus HTTP 服务 | `ServerBuilder.create().type("quarkus-http")...build()` | `type` 命中本模块 SPI 名；`"quarkus"` 亦可 |
| 直接构造 | `new QuarkusHttpServer(ServerSetting)` | 构造参数为服务器配置对象 |
| 生命周期 | `server.start()` / `stop()` / `isRunning()` / `close()` | 继承自 `Server` / `AbstractServer` |
| 注册路由（函数式） | `ServerBuilder.mapping(path, method, handler)` | 由统一过滤器链中的 URL 映射过滤器完成路由 |
| 注册路由（JAX-RS 注解） | Bean 标注 `@Path`，方法标注 `@GET` / `@POST` / `@PUT` / `@DELETE` / `@PATCH` | 由 `QuarkusServerHandlerAnnotationParser` 解析生成 `ServerHandler` |
| 读取请求 | `QuarkusServerRequest`：`getBody()` / `getBodyString()` / `getParams()` / `getFormData()` / `getFiles()` 等 | 实现 `ServerRequest` 接口 |
| 写出响应 | `QuarkusServerResponse`：`setStatus()` / `setHeader()` / `setBody()` / `setResult()` 等 | 实现 `ServerResponse` 接口 |
| SSE 推送 | `resp.sse()` → `resp.sseEvent(event, data)` → `resp.sseClose()` | `text/event-stream`，关闭时补发 `[DONE]` |
| 依赖注入 / 配置注入 | 构造器与 setter 上的 `@Inject` + `@Named`；字段/参数上的 `@ConfigProperty` | 由 objects 包 4 个 SPI 实现桥接到 common-starter 容器 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-quarkus-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;

public class QuarkusDemo {
    public static void main(String[] args) {
        Server server = ServerBuilder.create()
                .type("quarkus-http")                       // 使用本模块的 Vert.x(Quarkus 风格) 实现
                .host("0.0.0.0")
                .port(8080)
                .mapping("/hello", HttpMethod.GET,
                        (req, resp) -> resp.setResult("Hello Quarkus"))
                .build();

        server.start();
        System.out.println(server.getServerUrl());          // http://localhost:8080/

        // ... 业务运行期间 ...

        server.stop();
    }
}
```

说明：

- `ServerBuilder.type("quarkus-http")` 按 SPI 名称创建 `QuarkusHttpServer`；
- 路由匹配由 common-starter 的统一过滤器链处理：未命中返回 `404`，命中后执行 handler；
- `resp.setResult(...)` 的结果会派生为响应体：`String` → UTF-8 字节、`byte[]` → 原样、`java.nio.file.Path` → 读取文件字节、其他 → `toString()` 的 UTF-8 字节；仅在未调用 `setBody(...)` 时生效，不会覆盖显式设置的响应体。

### 3.3 JAX-RS 注解路由

```java
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api")
public class HelloResource {

    @GET
    @Path("/hello")
    public String hello() {
        return "Hello Quarkus";
    }
}
```

把标注了 `@Path` 的 Bean 注册进 common-starter 的 `ObjectContext` 后，`QuarkusServerHandlerAnnotationParser` 会扫描类级 + 方法级路径并生成路由；方法必须标注 `@GET` / `@POST` / `@PUT` / `@DELETE` / `@PATCH` 之一，否则跳过。

### 3.4 SSE 推送

```java
Server server = ServerBuilder.create()
        .type("quarkus-http")
        .port(8080)
        .mapping("/stream", HttpMethod.GET, (req, resp) -> {
            resp.sse();                          // text/event-stream; charset=utf-8
            resp.sseEvent("message", "hello");   // event: message\ndata: hello\n\n
            resp.sseClose();                     // 补发 data: [DONE] 并结束响应
        })
        .build();
server.start();
```

### 3.5 启用 HTTPS（PEM 证书）

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.ServerSetting.SslConfig;

ServerSetting setting = ServerSetting.defaults();
setting.setPort(8443);
setting.setSsl(SslConfig.builder()
        .enabled(true)
        .certPath("cert.pem")     // PEM 证书
        .keyPath("key.pem")       // PEM 私钥
        .build());

Server server = new QuarkusHttpServer(setting);
server.start();
```

---

## 4. 实现要点

以下要点均来自 `QuarkusHttpServer` 源码：

- **线程模型**：同步模式（默认，`setting.isReactor()=false`）下，过滤器链经 `executeBlocking` 在 Vert.x Worker 线程池中执行；响应式模式（`isReactor()=true`）下直接在 Vert.x EventLoop 线程上执行。`supportsReactor()` 返回 `true`。
- **Vert.x 线程池**：EventLoop 池大小 = `max(bossThreads, 2)`；Worker 池大小 = `max(workerThreads, CPU核数*4)`；`VertxOptions.setPreferNativeTransport(true)` 固定开启。
- **请求入口**：`Router` 接管全部路径 → `BodyHandler`（`bodyLimit = maxRequestSize`，`handleFileUploads = true`）→ 包装为 `QuarkusServerRequest` / `QuarkusServerResponse` → 走 `handleRequest(...)` 的统一过滤器链 → `endVertx()` 提交响应。过滤器链抛异常时回 `500 Internal Server Error`。
- **随机端口**：`port=0` 时由 Vert.x 自动分配端口，启动后回写 `setting.setPort(...)`，保证 `getPort()` 返回真实端口。
- **流加密开关**：`ServerSetting#encrypt=true` 时启动即被 `AbstractServer#rejectStreamEncryption` 拒绝——本实现不做流加密，错误信息提示改用 `ssl`（`certPath`/`keyPath`）启用 TLS。
- **TLS**：仅消费 `SslConfig` 的 `enabled` + `certPath` + `keyPath`（PEM 文件），映射到 Vert.x 的 `PemKeyCertOptions`。`keyStorePath`、`selfSigned*` 等其余 `SslConfig` 字段本实现未读取（源码未提供对应逻辑）。
- **SSE**：`sse()` 置 `Content-Type: text/event-stream; charset=utf-8`、`Cache-Control: no-cache`、`Connection: keep-alive` 并以 chunked 方式提交；`sseEvent(event, data)` 按 `event:` / `data:` 行写出；`sseClose()` 先发 `data: [DONE]` 再结束响应。
- **响应占位方法**：`getOutputStream()` 返回的 `ByteArrayOutputStream`、`flush()` 当前不会回写到响应体（源码现状）；`writeRaw(byte[])` 会直接写 Vert.x 响应。常规输出请使用 `setBody(...)` / `setResult(...)`。
- **文件上传**：multipart/form-data 由 Vert.x `BodyHandler` 处理（`handleFileUploads=true`），`getFiles()` 将 `ctx.fileUploads()` 转为 `FormFile` 列表，`getFormData()` 取自 `formAttributes()`。
- **请求体预缓存**：`QuarkusServerRequest` 构造时一次性读入请求体字节，`getBody()` / `getBodyString()`（UTF-8）/ `getInputStream()` 均基于该缓存。

---

## 5. 配置项

本模块**没有独立的配置文件或配置键**（源码与 pom 中无 `application*.yml/properties`、无 `@ConfigurationProperties`）；所有配置通过 common-starter 的 `ServerSetting` 承载，经 `ServerBuilder` 或构造函数传入。本实现实际读取的字段：

| `ServerSetting` 字段 | 说明 | 默认行为 |
| --- | --- | --- |
| `port` | 监听端口；`0` = 随机端口（启动后回写实际端口） | 以 `ServerSetting` 源码为准 |
| `host` | 监听地址 | 以 `ServerSetting` 源码为准 |
| `reactor` | `true` = 响应式（过滤器链在 EventLoop 线程执行）；`false` = 同步（Worker 线程池执行） | 同步模式 |
| `bossThreads` | Vert.x EventLoop 池大小下限（实际取 `max(bossThreads, 2)`） | 以 `ServerSetting` 源码为准 |
| `workerThreads` | Vert.x Worker 池大小下限（实际取 `max(workerThreads, CPU*4)`） | 以 `ServerSetting` 源码为准 |
| `backlog` | TCP accept backlog（实际取 `max(backlog, 2048)`） | 以 `ServerSetting` 源码为准 |
| `maxRequestSize` | 请求体上限：映射到 `maxHeaderSize` 与 `BodyHandler` 的 `bodyLimit` | 以 `ServerSetting` 源码为准 |
| `tcpNoDelay` / `soReuseAddr` | 映射到 Vert.x `setTcpNoDelay` / `setReusePort` | 以 `ServerSetting` 源码为准 |
| `ssl.enabled` / `ssl.certPath` / `ssl.keyPath` | 启用 TLS 的 PEM 证书与私钥（三者需同时就绪） | `enabled=false` |
| `encrypt` | 流加密开关；本实现**不支持**，设为 `true` 启动即失败 | `false` |

固定行为（源码硬编码，**无配置项**）：`tcpFastOpen=true`、`preferNativeTransport=true`、`logActivity=false`。

---

## 6. SPI 扩展点

### 6.1 本模块提供的实现

| SPI 接口 | 名称 | 实现 | 注册方式 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `quarkus-http` / `quarkus` | `com.chua.quarkus.support.server.QuarkusHttpServer` | 类上 `@Spi({"quarkus-http", "quarkus"})` |
| `com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser` | `quarkus` | `com.chua.quarkus.support.network.QuarkusServerHandlerAnnotationParser` | `@Spi("quarkus")` + `@ConditionalOnClass("jakarta.ws.rs.Path")` + `META-INF/extensions/...ServerHandlerAnnotationParser`（`quarkus=...`） |
| `com.chua.common.support.objects.resolver.BeanConstructorResolver` | `quarkus` | `com.chua.quarkus.support.objects.resolver.impl.QuarkusBeanConstructorResolver` | `@Spi("quarkus")` + `META-INF/extensions/...BeanConstructorResolver` |
| `com.chua.common.support.objects.register.BeanDefinitionRegister` | `quarkus` | `com.chua.quarkus.support.objects.register.impl.QuarkusBeanDefinitionRegister` | `@Spi("quarkus")` + `@SpiDescribe` + `META-INF/extensions/...BeanDefinitionRegister` |
| `com.chua.common.support.objects.inject.BeanDefinitionMethodInjector` | `quarkus` | `com.chua.quarkus.support.objects.inject.impl.QuarkusBeanDefinitionMethodInjector` | `@Spi("quarkus")` + `META-INF/extensions/...BeanDefinitionMethodInjector` |
| `com.chua.common.support.objects.inject.BeanDefinitionConfigInjector` | `quarkus` | `com.chua.quarkus.support.objects.inject.impl.QuarkusBeanDefinitionConfigInjector` | `@Spi("quarkus")` + `META-INF/extensions/...BeanDefinitionConfigInjector` |

objects 包 4 个实现的职责：

| 实现 | 职责 |
| --- | --- |
| `QuarkusBeanConstructorResolver` | 解析构造器参数上的 `@Inject` / `@Named`：优先按 `@Named` 值查容器，其次按参数类型，再按参数名；均失败抛 `BeanDefinitionException("@Inject 参数无法解析: ...")` |
| `QuarkusBeanDefinitionRegister` | 只读 Bean 定义注册器（priority 100）：委托 `jakarta.enterprise.inject.spi.CDI` 查询/获取 Bean；`isWritable()=false`，`register` / `unregister` 抛 `UnsupportedOperationException` |
| `QuarkusBeanDefinitionMethodInjector` | 处理 `@Inject` 标注的 setter 方法，按 `@Named` 值 → 参数类型 → 方法名顺序解析入参后反射调用 |
| `QuarkusBeanDefinitionConfigInjector` | 反射识别 `@ConfigProperty`（`org.eclipse.microprofile.config.inject.ConfigProperty`）注解的字段/方法参数，从 `Environment` 取值；`defaultValue` 生效但排除 `UNCONFIGURED_VALUE`；不直接依赖 microprofile-config-api 编译 API |

### 6.2 本模块消费的 SPI

| SPI 接口 | 用途 |
| --- | --- |
| common-starter 的统一过滤器链（`handleRequest(...)`） | 路由匹配、内置过滤器；本模块只把请求/响应包装后交给链路 |

### 6.3 扩展建议

本模块定位是单一协议实现，**无对外扩展点**。要新增协议服务端应另建 `*-starter` 实现 `Server` / `AbstractServer` 并用 `@Spi` 注册，而不是修改本模块。

---

## 7. 构建与依赖

### 7.1 依赖关系

```text
utils-support-quarkus-starter (com.chua:4.0.0.51)
├── com.chua:utils-support-common-starter          # Server SPI、ServerSetting、统一过滤器链、对象容器
├── io.vertx:vertx-core                            # 5.2.0（${vertx.version}，根 pom dependencyManagement 统一管理）
├── io.vertx:vertx-web                             # 5.2.0（${vertx.version}）
└── provided：jakarta.inject-api / jakarta.enterprise.cdi-api / jakarta.ws.rs-api
```

- 父模块：`utils-support-network-parent`（聚合于 `utils-support-parent-starter`）。
- JDK：`25`（根 pom `maven.compiler.source` / `maven.compiler.target` / `java.version` = 25）。

### 7.2 构建命令

```bash
mvn -o -q compile -DskipTests        # 仓库标准编译闸门
```

测试：本模块当前无测试源码与测试依赖；pom 中「单元测试：Quarkus(Vert.x) 服务端启动期拒绝 encrypt」仅为注释占位。

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-quarkus-starter/` 内置两份元数据：

| 文件 | 内容 |
| --- | --- |
| `reflect-config.json` | 注册 objects/network 包 5 个 SPI 实现类的全部构造器/方法/字段（`QuarkusServerHandlerAnnotationParser`、`QuarkusBeanDefinitionConfigInjector`、`QuarkusBeanDefinitionMethodInjector`、`QuarkusBeanDefinitionRegister`、`QuarkusBeanConstructorResolver`） |
| `resource-config.json` | 包含 `META-INF/extensions/.*`，保证 SPI 注册文件进入原生镜像 |

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| HTTP 客户端（同步/异步、cURL 互转） | `utils-support-common-starter`；底层实现见 `utils-support-okhttp-starter` / `utils-support-httpclient-starter` |
| 其它 HTTP 服务端实现（`jdk` / `nio` / `aio` / `armeria-http`） | `utils-support-common-starter` / `utils-support-armeria-starter` |
| 路由匹配、内置过滤器（gzip / cors / 安全头等） | `utils-support-common-starter` 的统一过滤器链 |
| Quarkus 框架能力（依赖注入容器、打包、Dev Mode、配置中心等） | 本模块不是 Quarkus：仅借用 Vert.x 引擎与 JAX-RS / CDI / MicroProfile 注解语义 |
| Bean 的创建、注册与生命周期管理 | 由 CDI 容器管理；本模块 `QuarkusBeanDefinitionRegister` 只读桥接，不支持手动注册/注销 |
| WebSocket / gRPC 等协议服务端 | 源码未提供；本模块仅按 `ProtocolType.HTTP` 提供通用 HTTP 服务 |
| 流式响应写出 | 源码现状：`getOutputStream()` 返回的流与 `flush()` 不回写响应（`writeRaw()` 可直接写 Vert.x 响应）；常规输出请用 `setBody(...)` / `setResult(...)` |
| `ServerSetting#encrypt` 流加密 | 不支持，启动即失败；传输加密请走 `ssl.certPath` / `ssl.keyPath` |
| KeyStore / 自签名证书等 SSL 配置 | `SslConfig` 的 `keyStorePath` / `selfSigned*` 字段本实现未消费（源码未提供） |
