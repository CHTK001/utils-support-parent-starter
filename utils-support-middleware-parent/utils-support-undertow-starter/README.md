# utils-support-undertow-starter

> utils 体系**嵌入式 Web 容器**的 Undertow 实现：把 Red Hat Undertow 高性能服务器适配成统一的 `WebContainer` SPI 生命周期接口，用于在纯 Java 进程中部署 WAR / JAR / 静态资源。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.undertow.support.container`

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
    <artifactId>utils-support-undertow-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Undertow 的 `WebContainer` SPI 适配器（`UndertowWebContainer`），提供嵌入式容器的初始化 / 部署 / 启动 / 停止 / 卸载生命周期 | 不是 Spring Boot starter：源码未提供自动配置类与 `application.properties` 配置绑定 |
| 能力 | HTTP 监听、WAR / FAT JAR 部署、静态资源、HTTPS（KeyStore）、启动时批量部署、远程 URL 下载部署（继承自基类） | 不是 `WebContainer` 契约的拥有者（契约与公共配置模型在 `utils-support-common-starter`） |
| 依赖 | 只依赖 `utils-support-common-starter` + `undertow-core` / `undertow-servlet` | 不依赖 Spring，不依赖任何业务 starter |

**判断口径**：要在 Java 进程里用 Undertow 跑一个 WAR / 可执行 JAR → 用本模块；要换 Tomcat 或改契约 → 见第 7 节边界。

### 1.3 规模

- Java 源文件 **1** 个：`UndertowWebContainer`（`extends AbstractWebContainer`，类注释 `@since 4.0.0.42`）。
- SPI 注册文件 **1** 个：`META-INF/services/com.chua.common.support.network.container.WebContainer`。
- GraalVM `native-image` 配置 **3** 个：`reflect-config.json` / `proxy-config.json` / `resource-config.json`（位于 `META-INF/native-image/com.chua/utils-support-undertow-starter/`）。
- 源码未提供测试目录与配置文件（如 `application.yml`）。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿 Undertow 容器 | `ServiceProvider.of(WebContainer.class).getExtension("undertow")` | SPI 名：`undertow`、`jboss-undertow` |
| 部署 WAR | `container.deploy(path, contextPath, DeployUnitType.WAR)` | 启动前调用；支持目录与 `classpath:` 前缀 |
| 部署可执行 JAR / Spring Boot JAR | `container.deploy(path, ctx, DeployUnitType.JAR)` | `FAT_JAR` / `SPRING_BOOT` 由基类归一化为 `JAR` |
| 远程下载部署 | `container.deployRemote(url)` | 支持 `http://`、`https://`、`ftp://`，自动下载并缓存 |
| 启动 / 停止 / 重启 | `start()` / `stop()` / `restart()` | 生命周期见 2.3 |
| 查询状态 | `getStatus()` / `isRunning()` / `getName()` | `getName()` 返回 `"undertow"` |
| 卸载应用 | `container.undeploy(contextPath)` | 仅运行中可卸载 |
| 开启 HTTPS | `setting.setSsl(SslConfig.builder()...build())` | HTTPS 监听端口固定 `8443` |
| 查看已部署单元 | `((AbstractWebContainer) container).getDeployedUnits()` | 返回 `DeployUnitInfo` 列表 |

---

## 2. 核心 API 与速查表

### 2.1 本模块类清单

| 类 | 说明 |
| --- | --- |
| `com.chua.undertow.support.container.UndertowWebContainer` | 唯一实现类。类上标注 `@Spi({"undertow", "jboss-undertow"})`；类注释声明 Undertow 特性：纯异步非阻塞 I/O（基于 XNIO）、支持 Servlet 3.1+ 规范、支持 WebSocket 和 HTTP/2、嵌入式部署零外部依赖 |

本模块没有其它公共类；其余 API 全部来自契约层（`WebContainer` / `AbstractWebContainer` / `WebContainerSetting` / `DeployUnitType`，见第 7 节）。

### 2.2 WebContainer 契约方法（本实现行为）

| 方法 | 本实现行为 |
| --- | --- |
| `initialize(WebContainerSetting)` | 配置判空；状态 `NEW → INITIALIZED`，重复初始化抛 `ContainerException` |
| `deploy(String archivePath)` | 按文件名推断类型（`DeployUnitType.fromFileName`）与上下文路径后部署 |
| `deploy(String, String, DeployUnitType)` | 指定类型与上下文部署；容器运行中调用会抛 `ContainerException("容器已启动，不支持运行时部署")` |
| `deployRemote(...)` | 接口 default 方法，等价 `deploy(url)`；远程 URL 由基类下载到缓存目录 |
| `deployAll()` | 遍历 `setting.getDeployUnits()` 中 `autoDeploy=true` 的单元逐一部署 |
| `start()` | 状态 `INITIALIZED → STARTING → RUNNING`；创建 `ServletContainer`、装配 `PathHandler`、构建并启动 `Undertow` |
| `stop()` | 状态 `RUNNING → STOPPING → STOPPED`；先停止并卸载全部部署，再 `undertow.stop()` |
| `restart()` | 运行中先 `stop()`，再 `start()` |
| `undeploy(String contextPath)` | 仅运行中可调用；`DeploymentManager.stop()+undeploy()` 并移除 `PathHandler` 前缀路径 |
| `getStatus()` / `isRunning()` | 读取基类原子状态机 |
| `getName()` | 返回 `"undertow"` |
| `getPort()` | 接口 default 返回 `0`；本实现未覆写 |

### 2.3 容器状态机

`WebContainer.ContainerStatus`：`NEW → INITIALIZED → STARTING → RUNNING → STOPPING → STOPPED`；启动/停止失败置为 `FAILED`。

生命周期约束（源码行为）：**部署必须发生在 `start()` 之前**（运行中 `deploy` 被基类拒绝）；**卸载必须在运行中调用**（未启动时 `undeploy` 抛 `ContainerException`）。

### 2.4 部署类型行为（`doDeploy` 分发表）

| `DeployUnitType` | 本实现行为 |
| --- | --- |
| `WAR` | `deployWar`：构建 `DeploymentInfo`（类加载器 + 上下文路径 + 部署名），目录 → `FileResourceManager(目录)`，WAR 文件 → `FileResourceManager(父目录)`；注册 `ServletContextListener`；`manager.deploy()+start()` 后挂到 `PathHandler` 前缀路径 |
| `JAR` | `deployFatJar`：日志「FAT JAR 模式部署」，资源根为 JAR 父目录，其余同 WAR |
| `FAT_JAR` / `SPRING_BOOT` | 基类归一化为 `JAR` 后走 `deployFatJar` |
| `EAR` | 不支持原生部署，降级为静态资源：`deployResource` → `ResourceHandler` + `FileResourceManager` |
| `MAIN` | 由基类 `doDeployMain` 反射调用 `main` 方法（非空上下文时附带 `--server.servlet.context-path=...` 参数） |
| 其它 | 抛 `ContainerException("不支持的部署类型: " + type)` |

> WAR 部署通过 `DeploymentInfo` 注册资源管理器与 `ServletContextListener`，源码未注册 WAR 内的 `ServletInfo`、也未解析 `web.xml`；需要 Servlet 级能力时应按 Undertow 原生 API 自行扩展。

### 2.5 运行时参数（`doStart` 固定行为）

- IO 线程数 = `Runtime.getRuntime().availableProcessors()`；Worker 线程数 = `setting.getMaxThreads()`（默认 200）。
- 缓冲区 16384 字节，启用直接内存（`setDirectBuffers(true)`）。
- HTTP 监听 `host:port`；启用 SSL 时额外监听 `host:8443`（端口硬编码，源码未提供配置项）。
- `resolveFile` 支持 `classpath:` 前缀（经类加载器解析，找不到抛 `ContainerException`），其余按文件系统路径处理。

---

## 3. 快速开始

最小可运行示例（依赖坐标见 1.1）：

```java
import com.chua.common.support.network.container.DeployUnitType;
import com.chua.common.support.network.container.WebContainer;
import com.chua.common.support.network.container.WebContainerSetting;
import com.chua.common.support.spi.ServiceProvider;

public class UndertowDemo {
    public static void main(String[] args) {
        // 1. 通过 SPI 获取 Undertow 容器（META-INF/services 注册，SPI 名 undertow / jboss-undertow）
        WebContainer container = ServiceProvider.of(WebContainer.class).getExtension("undertow");

        // 2. 配置容器与部署单元
        WebContainerSetting setting = WebContainerSetting.defaults();
        setting.setHost("0.0.0.0");
        setting.setPort(8080);
        setting.addDeployUnit(WebContainerSetting.DeployUnit.builder()
                .type(DeployUnitType.WAR)
                .path("/opt/apps/demo.war")   // 也支持 classpath: 前缀与 http(s)/ftp 远程 URL
                .contextPath("/demo")
                .autoDeploy(true)
                .build());

        // 3. 初始化 → 启动（autoDeploy 的部署单元在启动时自动部署）
        container.initialize(setting);
        container.start();

        // 4. 运行状态
        System.out.println(container.getName() + " → " + container.getStatus());   // undertow → RUNNING

        // 5. 停止
        container.stop();
    }
}
```

也可以不走 `deployUnits`，在 `initialize()` 之后、`start()` 之前逐个调用 `container.deploy(path, contextPath, type)`。启用 HTTPS 的最小改法：

```java
setting.setSsl(WebContainerSetting.SslConfig.builder()
        .enabled(true)
        .keyStorePath("/opt/keystore.p12")
        .keyStorePassword("changeit")
        .build());   // HTTPS 监听 8443 端口
```

---

## 4. 配置项

本模块没有自己的配置文件或配置键；配置载体是契约层的 `com.chua.common.support.network.container.WebContainerSetting`（代码式配置，POJO setter / Builder）。

### 4.1 WebContainerSetting 字段

| 字段 | 类型 | 默认值 | 本模块是否消费 |
| --- | --- | --- | --- |
| `host` | String | `0.0.0.0` | 是：HTTP/HTTPS 监听地址 |
| `port` | int | `8080` | 是：HTTP 监听端口 |
| `maxThreads` | int | `200` | 是：Undertow Worker 线程数 |
| `deployUnits` | List&lt;DeployUnit&gt; | 空列表 | 是：启动时自动部署 |
| `ssl` | SslConfig | `enabled=false` | 是：HTTPS 监听 |
| `downloadDir` | String | `null`（系统临时目录 `webcontainer/undertow`） | 是：远程下载缓存目录（基类消费） |
| `contextPath` | String | `/` | 否：源码未读取（部署单元各自携带 contextPath） |
| `unpackWar` | boolean | `true` | 否：源码未读取 |
| `minSpareThreads` | int | `10` | 否：源码未读取 |
| `connectionTimeout` | int | `60000` | 否：源码未读取 |
| `charset` | String | `UTF-8` | 否：源码未读取 |
| `maxRequestBodySize` | long | `10MB` | 否：源码未读取 |
| `accessLogEnabled` | boolean | `false` | 否：源码未读取 |
| `accessLogDirectory` | String | `logs` | 否：源码未读取 |
| `gracefulShutdownTimeout` | int | `30` | 否：源码未读取 |

> 「否」的字段属于契约层统一模型，供其它容器实现或后续版本消费；本模块源码未读取，请以源码为准。

### 4.2 DeployUnit（部署单元）

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `type` | DeployUnitType | `WAR` | 部署单元类型 |
| `path` | String | — | 归档文件绝对路径、目录、`classpath:` 路径或 `http(s)/ftp` URL |
| `contextPath` | String | `null` | 为空时按文件名推导为 `/<文件名>` |
| `autoDeploy` | boolean | `true` | 是否在启动时部署 |

### 4.3 SslConfig

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | 是否启用 HTTPS |
| `keyStorePath` | String | `null` | KeyStore 文件路径；为空时使用 `SSLContext.getDefault()` |
| `keyStorePassword` | String | `null` | KeyStore 密码 |
| `keyStoreType` | String | `PKCS12` | KeyStore 类型 |
| `certPath` / `keyPath` / `keyPassword` | String | `null` | PEM 证书/私钥（契约层字段，本实现源码未读取） |
| `sslProtocol` | String | `TLS` | SSL 协议 |

行为：`enabled=true` 且 `keyStorePath` 非空 → 加载 KeyStore 构建 `SSLContext` 并监听 `8443`；SSL 配置失败仅记录 warn 日志，**不会阻断容器启动**。

---

## 5. SPI 扩展点

- **接口**：`com.chua.common.support.network.container.WebContainer`（契约层）；**抽象基类**：`AbstractWebContainer`（模板方法：子类实现 `doStart` / `doStop` / `doDeploy`，可选覆写 `doUndeploy` / `doDeployMain`）。
- **注册方式**：实现类标注 `@Spi("名称")`，并在 `META-INF/services/com.chua.common.support.network.container.WebContainer` 中登记实现类全限定名（JDK ServiceLoader 风格，由 common-starter 的 `ServiceLoaderServiceResolver` 解析，也可用 `META-INF/extensions` 属性风格）。
- **获取方式**：`ServiceProvider.of(WebContainer.class).getExtension("undertow")`；本模块注册了 `undertow` 与 `jboss-undertow` 两个名称。
- **新增容器实现**（例如接入 Jetty）：新建 `extends AbstractWebContainer` 的类并实现三个 `do*` 方法，用 `@Spi` 命名并登记注册文件——应放在自己的 starter 模块，不要改本模块。
- **GraalVM native-image**：`reflect-config.json` 为 `UndertowWebContainer` 注册全量反射；`resource-config.json` 保留 `META-INF/services/.*`，保证 SPI 文件在原生镜像中可见。

---

## 6. 构建与依赖

- **模块层级**：`utils-support-parent-starter` → `utils-support-middleware-parent` → `utils-support-undertow-starter`（版本 `4.0.0.51`）。
- **编译**：JDK 25，`maven.compiler.release=25`，开启 `--enable-preview`；在 `utils-support-parent-starter` 仓库根执行 `mvn -o -q compile -DskipTests` 即可编译。
- **依赖清单**（`pom.xml`）：

| groupId | artifactId | 版本 | 说明 |
| --- | --- | --- | --- |
| `com.chua` | `utils-support-common-starter` | 随父 POM | 契约层：`WebContainer` SPI、`WebContainerSetting`、`AbstractWebContainer` |
| `io.undertow` | `undertow-core` | `2.3.26.Final`（由 middleware-parent 统一管理） | Undertow 核心 |
| `io.undertow` | `undertow-servlet` | `2.3.26.Final`（同上） | Servlet 部署支持 |

---

## 7. 边界：这些能力不在本模块

| 能力 | 实际归属 |
| --- | --- |
| `WebContainer` 契约、`AbstractWebContainer`、`WebContainerSetting`、`DeployUnitType` | `utils-support-common-starter`（`com.chua.common.support.network.container`） |
| Tomcat 嵌入式容器实现 | `utils-support-tomcat-starter` |
| 非 Servlet 的通用 HTTP / TCP / UDP / WebSocket 服务端 | `utils-support-common-starter` 的 `network.server`（`ServerBuilder`） |
| Spring Boot 官方 Undertow 集成（`spring-boot-starter-undertow`） | Spring 生态，与本模块无关；本模块不提供 Spring 自动配置 |
| WebSocket / HTTP/2 的配置开关 | 源码未提供（类注释声明 Undertow 本身支持，但本模块未暴露配置入口） |
| MVC / 路由注解 / Servlet 编程模型 | 本模块只负责容器生命周期与部署，不提供应用框架 |
| HTTPS 端口自定义 | 源码未提供：HTTPS 固定监听 `8443`，HTTP 端口由 `port` 控制 |

**已知行为约束**（源自码）：部署须在 `start()` 之前完成；`EAR` 仅按静态资源处理；WAR 部署不解析 `web.xml`、不注册 WAR 内 `ServletInfo`；运行中 `deploy` 与未启动 `undeploy` 均会抛 `ContainerException`。
