# utils-support-retrofit-starter

> utils 网络家族中的 **Retrofit2 声明式 HTTP 调用器**：把标注了注解的 Java 接口变成 HTTP 动态代理。它是 `com.chua.common.support.network.invoker.Invoker` SPI 的实现模块（pom 描述：「Retrofit2 HTTP 客户端调用器 SPI 实现」）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.retrofit.support.invoker`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 它是什么 / 不是什么](#2-它是什么--不是什么)
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
    <artifactId>utils-support-retrofit-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 规模与组成

- Java 源文件 **1** 个：`com.chua.retrofit.support.invoker.RetrofitHttpInvoker`（`@since 4.0.0.42`）。
- SPI 注册文件 **1** 个：`META-INF/services/com.chua.common.support.network.invoker.Invoker`。
- GraalVM native-image 元数据 **3** 个：`reflect-config.json` / `proxy-config.json` / `resource-config.json`（位于 `META-INF/native-image/com.chua/utils-support-retrofit-starter/`）。
- 无测试源码、无 Spring 自动装配类、无配置类。

---

## 2. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | `Invoker` SPI 的 Retrofit2 实现：SPI 名 `http`、`order=100`；类路径存在 `retrofit2.Retrofit` 时自动生效（`@ConditionalOnClass`） | 不是 HTTP 底层执行器，不参与 `HttpClientFactory` 的执行器选择 |
| 能力 | 接口 → Retrofit 动态代理；类级注解解析 baseUrl；Retrofit 原生注解（`@GET`、`@POST`、`@Path`、`@Query` 等）声明方法；Jackson 编解码 | 不做服务端、RPC、IPC；不管理连接池、拦截器链、重试限流 |
| 依赖 | 仅 `utils-support-common-starter` + `retrofit` + `converter-jackson` | 不强制依赖 Spring：`@RequestMapping` 通过反射识别，类路径无 Spring 时自动跳过 |

**判断口径**：需要在「接口上打注解就拿到 HTTP 客户端」且已引入 Retrofit2 时，用本模块；没有 Retrofit2 时，`@ConditionalOnClass` 使本实现不生效，`InvokerFactory` 取当前可用的最高优先级 `Invoker` 实现（默认 `HttpInvoker`）。

### 2.1 baseUrl 解析顺序（resolveBaseUrl）

按顺序尝试，取到非空值即用，并标准化为以 `/` 结尾（Retrofit 强制要求）：

| 顺序 | 来源 | 取值 |
| --- | --- | --- |
| 1 | Spring `@RequestMapping`（类级，反射加载，无 Spring 时跳过） | `value` 属性（`String` 或 `String[]` 取第一个） |
| 2 | `@RequestMethod`（`com.chua.common.support.network.annotations`） | `value()` |
| 3 | `@RemoteService`（`com.chua.common.support.network.invoker.annotations`） | `url()` |
| 4 | 均未声明 | 抛 `IllegalArgumentException`：`接口 X 缺少 baseUrl，请标注 @RequestMethod 或 @RequestMapping` |

---

## 3. 核心 API 与速查表

模块只暴露一个实现类 `RetrofitHttpInvoker`，实现 `Invoker` 的两个方法；日常通过 `InvokerFactory` 间接使用。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建代理（带缓存） | `InvokerFactory.create(UserApi.class)` | 底层 `create(Class)`：同一接口全局复用同一个代理（静态 `ConcurrentMap` 缓存） |
| 每次新建代理 | `invoker.createNew(UserApi.class)` | 不查缓存，每次重新构建 `Retrofit` 实例并生成代理 |
| 按 SPI 名取调用器 | `InvokerFactory.getInvoker("http")` | 命中本模块实现；未引入本模块时返回 `null` |
| 声明 baseUrl | 类级 `@RemoteService(url = "...")` | 也可用 `@RequestMethod("...")` 或 Spring `@RequestMapping`，优先级见 2.1 |
| 声明方法与路径 | Retrofit `@GET` / `@POST` / `@Path` / `@Query` 等 | 未注册额外 CallAdapter，方法返回值需为 `retrofit2.Call<T>` |
| JSON 编解码 | `JacksonConverterFactory`（构建代理时自动装配） | 无需配置 |
| 程序化注入 | `invoker.addInject(target, callback)` | 本实现未覆写，走接口默认空实现（返回 this，无注入效果） |

---

## 4. 快速开始

第 1 步：引入依赖（坐标见 1.1）。第 2 步：声明接口——类级注解提供 baseUrl，方法级使用 Retrofit 原生注解：

```java
import com.chua.common.support.network.invoker.annotations.RemoteService;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

// 本实现会读取 @RemoteService.url() 作为 baseUrl
@RemoteService(url = "https://api.example.com")
public interface UserApi {

    @GET("api/users/{id}")
    Call<User> getUser(@Path("id") Long id);   // User 为任意 POJO
}
```

第 3 步：创建代理并调用。类路径存在本模块时，`InvokerFactory` 按优先级（order=100）自动选中 `RetrofitHttpInvoker`：

```java
import com.chua.common.support.network.invoker.InvokerFactory;

UserApi api = InvokerFactory.create(UserApi.class);
User user = api.getUser(1L).execute().body();
```

也可以显式按 SPI 名获取本实现：

```java
import com.chua.common.support.network.invoker.Invoker;

Invoker invoker = InvokerFactory.getInvoker("http");   // 即 RetrofitHttpInvoker
UserApi fresh = invoker.createNew(UserApi.class);      // 绕过缓存的新代理
```

注意：接口缺 baseUrl 注解、或传入非接口类型，都会抛 `IllegalArgumentException`（后者消息为 `只支持接口类型: 类名`）。

---

## 5. 配置项

**无。** 依据：本模块源码只有一个 `RetrofitHttpInvoker` 类，未读取任何配置键、系统属性或环境变量；`pom.xml` 亦未定义配置属性。行为全部由注解与代码内固定逻辑决定：

| 行为 | 决定方 | 可配置性 |
| --- | --- | --- |
| baseUrl | 接口类级注解（`@RemoteService` / `@RequestMethod` / Spring `@RequestMapping`） | 否，只能改注解 |
| JSON 转换器 | 代码固定 `JacksonConverterFactory.create()` | 否 |
| 代理缓存 | 类内静态 `ConcurrentMap` | 否（`createNew` 可绕过缓存） |
| 超时、HTTP 客户端定制 | 未定制，走 Retrofit 默认 | 否（源码未提供定制入口） |

---

## 6. SPI 扩展点

本模块**不定义新的扩展点**；它本身是 common-starter 中 `Invoker` SPI 的一个实现：

- 注册文件：`src/main/resources/META-INF/services/com.chua.common.support.network.invoker.Invoker`，内容为 `com.chua.retrofit.support.invoker.RetrofitHttpInvoker`。
- 类注解：`@Spi(value = "http", order = 100)` + `@ConditionalOnClass("retrofit2.Retrofit")`。
- 装配顺序（`InvokerFactory` 按 order 取最高优先级实现）：`RetrofitHttpInvoker(100)` → `RpcInvoker(50)` → `IpcInvoker(40)` → `HttpInvoker(0)`。

要新增协议调用器（例如 gRPC），请在**自己的模块**里实现 `Invoker` 并按同样方式登记，不要修改本模块。

---

## 7. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` = 4.0.0.51 | 提供 `Invoker` / `InvokerFactory`、注解与 SPI 机制 |
| `com.squareup.retrofit2:retrofit` | 3.0.0（根 pom dependencyManagement 统一托管） | Retrofit2 核心 |
| `com.squareup.retrofit2:converter-jackson` | 3.0.0（同上） | Jackson JSON 转换器 |

父模块链：`utils-support-parent-starter`（4.0.0.51，JDK 25）→ `utils-support-network-parent`（网络协议集成父模块）→ 本模块。

GraalVM：`META-INF/native-image/com.chua/utils-support-retrofit-starter/` 已注册 `RetrofitHttpInvoker` 的全量反射元数据（reflect / proxy / resource 三个 config.json）。

---

## 8. 边界：这些能力不在本模块

| 能力 | 应去的位置 |
| --- | --- |
| HTTP 底层执行器（`okhttp` / `httpclient5` / `jdk`） | `utils-support-okhttp-starter` / `utils-support-httpclient-starter`；`jdk` 内置于 common-starter |
| RPC / IPC 调用 | common-starter 的 `RpcInvoker` / `IpcInvoker` |
| 请求头/属性注入（`@RemoteInject` 及 `addInject` 的实际处理） | common-starter 契约与其它 `Invoker` 实现；本实现 `addInject` 为空实现 |
| HTTP/TCP/UDP 服务端 | common-starter `network.server`（`ServerBuilder` 等） |
| 重试、限流、熔断 | common-starter `concurrent` |
| 负载均衡 | common-starter `lang.balance.LoadBalance` SPI（`@RemoteService` 的 `balance` 属性指向它，本模块源码未读取该属性） |
| 下载、SSE、隧道、代理服务器等网络能力 | common-starter `network.*` 对应子包 |
