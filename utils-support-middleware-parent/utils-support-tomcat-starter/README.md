# utils-support-tomcat-starter

> Apache Tomcat **嵌入式容器集成**：把 `WebContainer` SPI 落地为 Tomcat 实现，并提供 `TOMCAT` 运行时启动器，用于部署与运行 WAR 归档。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.tomcat.support`

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
    <artifactId>utils-support-tomcat-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 容器实现 | `WebContainer` SPI 的 **Tomcat 实现**（SPI 名 `tomcat` / `apache-tomcat`），基于 `tomcat-embed-core` 9.0.93 | 不包含 Undertow、Jetty 等其他容器的实现 |
| 运行时启动器 | `RuntimeLauncher` SPI 的 **TOMCAT 实现**（SPI 名 `TOMCAT`），按 artifact 约定部署 WAR | 不负责 WAR 的构建与打包 |
| 依赖 | 只依赖 `utils-support-common-starter`（契约）、`utils-support-runtime-core`（启动器 SPI）与 Tomcat 嵌入式组件 | 不依赖 Spring / Spring Boot，无自动装配 |
| 配置方式 | 编程式 `WebContainerSetting`（契约层统一定义） | 无 `application.yml` / properties 配置前缀，无 `server.xml` 解析 |

### 1.3 规模与文件地图

- Java 源文件 **2** 个，均位于 `com.chua.tomcat.support`：

```
src/main/java/com/chua/tomcat/support/
├── container/TomcatWebContainer.java     # WebContainer SPI 的 Tomcat 实现
└── launcher/TomcatRuntimeLauncher.java   # RuntimeLauncher SPI 的 TOMCAT 实现
```

- SPI 注册文件 **2** 个（`src/main/resources/META-INF/services/`）：

```
com.chua.common.support.network.container.WebContainer → com.chua.tomcat.support.container.TomcatWebContainer
com.chua.runtime.core.manager.RuntimeLauncher         → com.chua.tomcat.support.launcher.TomcatRuntimeLauncher
```

- GraalVM `native-image` 配置 **3** 个（`src/main/resources/META-INF/native-image/com.chua/utils-support-tomcat-starter/`）：`reflect-config.json`（两个实现类全反射开放）、`proxy-config.json`（`WebContainer`、`RuntimeLauncher` 代理）、`resource-config.json`（包含 `META-INF/services/.*`）。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿一个 Tomcat 容器 | `ServiceProvider.of(WebContainer.class).getExtension("tomcat")` | SPI 名：`tomcat`、`apache-tomcat` |
| 部署单个 WAR | `container.deploy("/path/app.war")` | 按扩展名推断类型，按文件名推导上下文路径 |
| 批量部署配置单元 | `container.deployAll()` | 只部署 `autoDeploy=true` 的单元 |
| 启动 / 停止 / 重启 | `container.start()` / `stop()` / `restart()` | 状态机：`NEW → INITIALIZED → STARTING → RUNNING → STOPPING → STOPPED`（失败 `FAILED`） |
| 卸载应用 | `container.undeploy(contextPath)` | 需容器已启动，否则抛 `ContainerException` |
| 查询状态与端口 | `container.getStatus()` / `isRunning()` / `getPort()` | `getPort()` 返回实际监听端口（端口 0 自动分配后） |
| 作为运行时启动器 | `RuntimeLauncher.find("TOMCAT").start(artifact)` | `artifact.executable` = WAR 路径；`args` = 部署路径 / 端口 / 容器名 |
| 开启 SSL/TLS | `setting.getSsl().setEnabled(true)` … | 支持 KeyStore（JKS/PKCS12）或 PEM 证书 |
| 开启访问日志 | `setting.setAccessLogEnabled(true)` | `AccessLogValve`，pattern `common`，默认目录 `logs` |

---

## 2. 核心 API 与速查表

### 2.1 两个入口类

| 类 | SPI 名 | 实现的接口 | 职责 |
| --- | --- | --- | --- |
| `TomcatWebContainer` | `tomcat`、`apache-tomcat` | `WebContainer`（继承 `AbstractWebContainer`） | 嵌入式 Tomcat 的初始化、部署、启动、停止、卸载 |
| `TomcatRuntimeLauncher` | `TOMCAT` | `RuntimeLauncher` | 面向工件（`RuntimeArtifact`）的启动 / 停止 / 状态查询，内部委托 `WebContainer` |

两者均为 `@since 4.0.0.42`。

### 2.2 TomcatWebContainer 的生命周期方法

继承自契约接口 `com.chua.common.support.network.container.WebContainer`：

| 方法 | 说明 |
| --- | --- |
| `initialize(WebContainerSetting)` | 绑定配置；仅允许 `NEW → INITIALIZED`，重复初始化抛 `ContainerException` |
| `deploy(String archivePath)` | 按文件名推断 `DeployUnitType` 与上下文路径后部署 |
| `deploy(String archivePath, String contextPath, DeployUnitType type)` | 指定类型与上下文部署；支持本地路径与远程 URL（自动下载） |
| `deployRemote(String url)` / `deployRemote(url, contextPath, type)` | 显式从远程 URL 下载后部署 |
| `deployAll()` | 批量部署 `setting.deployUnits` 中 `autoDeploy=true` 的单元 |
| `undeploy(String contextPath)` | 卸载指定上下文（`tomcat.getHost().removeChild(ctx)`） |
| `start()` / `stop()` / `restart()` | 启动、停止（stop + destroy）、重启（先 stop 再 start） |
| `getStatus()` / `isRunning()` / `getPort()` / `getName()` | 状态、运行判断、实际监听端口、容器名（`"tomcat"`） |

部署类型支持（`DeployUnitType`）：

| 类型 | Tomcat 实现的行为 |
| --- | --- |
| `WAR` | 解压 WAR 后以目录形式 `addWebapp` 部署 |
| `JAR` | 按 WAR 模式部署（FAT JAR 场景） |
| `EAR` | 按 WAR 模式部署 |
| `FAT_JAR` / `SPRING_BOOT` | 由基类归一化为 `JAR` 后进入上述流程 |
| `MAIN` | 由基类反射调用 Main 类的 `main` 方法启动（`doDeployMain`） |
| 其他 | 抛出 `ContainerException("不支持的部署类型: ...")` |

部署细节（源码行为）：

- WAR 先解压到 `{java.io.tmpdir}/guacamole-deploy/{contextPath 去掉 /}` 目录，再部署解压后的目录（避免 `fixDocBase` 失败）。
- `Context.setDelegate(true)`，父类加载器设为本模块的 classloader，保证 WAR 能委托找到 `javax.servlet` 等容器类。
- 工作目录：`{java.io.tmpdir}/tomcat-{时间戳}`，同时设置系统属性 `catalina.home` / `catalina.base`，并在其下创建 `webapps` 目录作为 Host 的 appBase。
- 支持多应用同时部署（多 Context），`contexts` 以 contextPath 为键管理。

### 2.3 TomcatRuntimeLauncher 的 artifact 约定

`RuntimeLauncher` 面向 `com.chua.runtime.core.model.RuntimeArtifact`：

| artifact 字段 | 含义 | 默认 |
| --- | --- | --- |
| `executable` | WAR 文件路径（`Path`） | 缺失时返回失败 `CmdResult`（stderr：`TOMCAT artifact 缺少 WAR 文件路径`） |
| `args[0]` | 部署路径（contextPath） | `/` |
| `args[1]` | 端口 | `8080` |
| `args[2]` | 容器 SPI 名称 | `tomcat`（可选 `undertow`） |

行为要点：

- `type()` 返回 `"TOMCAT"`；`status()` 在容器运行时返回 `RuntimeStatus.RUNNING`，否则 `STOPPED`。
- `start()`：若容器已在运行则直接返回成功（幂等）；否则按上表解析参数，host 固定 `0.0.0.0`，`autoDeploy=true`，通过 `ServiceProvider.of(WebContainer.class).getExtension(containerName)` 加载容器（找不到返回失败 `CmdResult`），依次 `initialize → deployAll → start`，成功返回 `CmdResult`（`exitCode=0`，stdout 含端口）。
- `stop()`：容器为 `null` 时返回「未在运行」；否则 `container.stop()` 并置空。
- 返回值统一为 `com.chua.common.support.lang.cmd.CmdResult`。

---

## 3. 快速开始

### 3.1 直接使用 WebContainer 部署 WAR

与 `TomcatRuntimeLauncher.start()` 内部完全同构：

```java
import com.chua.common.support.network.container.DeployUnitType;
import com.chua.common.support.network.container.WebContainer;
import com.chua.common.support.network.container.WebContainerSetting;
import com.chua.common.support.spi.ServiceProvider;

// 1. 通过 SPI 获取 Tomcat 容器
WebContainer container = ServiceProvider.of(WebContainer.class).getExtension("tomcat");

// 2. 组装配置
WebContainerSetting setting = new WebContainerSetting();
setting.setPort(8080);
setting.setHost("0.0.0.0");

WebContainerSetting.DeployUnit unit = new WebContainerSetting.DeployUnit();
unit.setPath("/path/to/app.war");
unit.setContextPath("/");
unit.setType(DeployUnitType.WAR);
unit.setAutoDeploy(true);
setting.addDeployUnit(unit);

// 3. 初始化 → 批量部署 → 启动
container.initialize(setting);
container.deployAll();
container.start();

// 4. 使用与停止
container.isRunning();          // true
container.getPort();            // 8080（实际监听端口）
container.undeploy("/");        // 卸载（需已启动）
container.stop();
```

也可以只部署单个归档（类型与上下文路径自动推断）：

```java
container.initialize(setting);
container.deploy("/path/to/app.war");
container.start();
```

### 3.2 作为运行时启动器使用

```java
import com.chua.runtime.core.manager.RuntimeLauncher;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;

import java.nio.file.Path;
import java.util.List;

RuntimeLauncher launcher = RuntimeLauncher.find("TOMCAT");   // SPI 按类型名查找

RuntimeArtifact artifact = RuntimeArtifact.builder()
        .id("demo")
        .name("demo")
        .executable(Path.of("./demo.war"))                    // WAR 文件路径
        .args(List.of("/", "8080", "tomcat"))                 // 部署路径 / 端口 / 容器 SPI 名
        .build();

launcher.start(artifact);       // CmdResult，exitCode=0 表示启动成功
launcher.status();              // RuntimeStatus.RUNNING
launcher.stop(artifact);        // 停止
launcher.status();              // RuntimeStatus.STOPPED
```

### 3.3 开启 SSL/TLS 与访问日志

```java
WebContainerSetting.SslConfig ssl = setting.getSsl();
ssl.setEnabled(true);
ssl.setKeyStorePath("/path/to/keystore.p12");
ssl.setKeyStorePassword("changeit");
ssl.setKeyStoreType("PKCS12");     // 默认值
ssl.setSslProtocol("TLS");         // 默认值

setting.setAccessLogEnabled(true);
setting.setAccessLogDirectory("logs");
```

---

## 4. 配置项

本模块**没有** Spring 配置文件前缀或系统属性开关；全部配置通过契约类 `com.chua.common.support.network.container.WebContainerSetting` 编程式设置。下表为该配置类的全部字段（默认值与源码一致）；「Tomcat 消费」列标注 `●` 表示 `TomcatWebContainer` 会实际读取，`○` 表示本模块源码未提供消费点。

### 4.1 WebContainerSetting

| 字段 | 类型 | 默认值 | 说明 | Tomcat 消费 |
| --- | --- | --- | --- | --- |
| `host` | String | `0.0.0.0` | 绑定主机地址 | ● `tomcat.setHostname` |
| `port` | int | `8080` | 绑定端口 | ● `tomcat.setPort` |
| `contextPath` | String | `/` | 默认上下文路径 | ○（实际上下文由 `DeployUnit.contextPath` / launcher `args[0]` 决定） |
| `unpackWar` | boolean | `true` | 是否自动解压部署单元 | ○（实现内建解压 WAR） |
| `maxThreads` | int | `200` | 最大工作线程数 | ● connector `maxThreads` |
| `minSpareThreads` | int | `10` | 最小空闲线程数 | ● connector `minSpareThreads` |
| `connectionTimeout` | int | `60000` | 连接超时（毫秒） | ● connector `connectionTimeout` |
| `charset` | String | `UTF-8` | 字符编码 | ● connector `URIEncoding` |
| `maxRequestBodySize` | long | `10485760`（10MB） | 最大请求体大小（字节） | ● connector `maxPostSize` |
| `accessLogEnabled` | boolean | `false` | 是否启用访问日志 | ● `AccessLogValve` |
| `accessLogDirectory` | String | `logs` | 访问日志输出目录 | ● valve `directory` |
| `gracefulShutdownTimeout` | int | `30` | 优雅关闭等待（秒） | ○ 源码未提供消费点 |
| `downloadDir` | String | 系统 临时目录 | 远程文件下载缓存目录 | ○（由基类 `AbstractWebContainer` 远程下载时使用） |
| `deployUnits` | List&lt;DeployUnit&gt; | 空列表 | 部署单元列表（`addDeployUnit` 追加） | ● `deployPendingUnits` |
| `ssl` | SslConfig | 默认实例 | SSL/TLS 配置 | ● `configureSsl` |

### 4.2 WebContainerSetting.DeployUnit

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `type` | DeployUnitType | `WAR` | 部署单元类型 |
| `path` | String | — | 归档文件绝对路径或 `classpath:` 路径 |
| `contextPath` | String | — | 上下文路径；为空时按文件名推导 |
| `autoDeploy` | boolean | `true` | 是否在启动时部署 |

### 4.3 WebContainerSetting.SslConfig

| 字段 | 类型 | 默认值 | 说明 | Tomcat 消费 |
| --- | --- | --- | --- | --- |
| `enabled` | boolean | `false` | 是否启用 SSL/TLS | ● |
| `keyStorePath` | String | — | KeyStore 文件路径（JKS/PKCS12） | ● connector `keystoreFile` |
| `keyStorePassword` | String | — | KeyStore 密码 | ● connector `keystorePass` |
| `keyStoreType` | String | `PKCS12` | KeyStore 类型 | ● connector `keystoreType` |
| `certPath` | String | — | SSL 证书文件路径（PEM） | ● 与 `keyPath` 同时设置时 `clientAuth=false` |
| `keyPath` | String | — | SSL 私钥文件路径（PEM） | ● 同上 |
| `keyPassword` | String | — | SSL 私钥密码 | ○ 源码未提供消费点 |
| `sslProtocol` | String | `TLS` | SSL 协议 | ● connector `sslProtocol` |

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI

| SPI 接口 | key | 实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.container.WebContainer` | `tomcat`、`apache-tomcat` | `TomcatWebContainer` | `META-INF/services/com.chua.common.support.network.container.WebContainer` |
| `com.chua.runtime.core.manager.RuntimeLauncher` | `TOMCAT` | `TomcatRuntimeLauncher` | `META-INF/services/com.chua.runtime.core.manager.RuntimeLauncher` |

按名称获取：

```java
WebContainer tomcat = ServiceProvider.of(WebContainer.class).getExtension("tomcat");
WebContainer alias  = ServiceProvider.of(WebContainer.class).getExtension("apache-tomcat");
RuntimeLauncher launcher = ServiceProvider.of(RuntimeLauncher.class).getExtension("TOMCAT");
```

### 5.2 切换底层容器

`TomcatRuntimeLauncher` 的 `args[2]` 是容器 SPI 名称（默认 `tomcat`，可选 `undertow`）。Undertow 实现位于同级模块 `utils-support-undertow-starter`；classpath 引入后，同名 artifact 只需把 `args[2]` 改为 `undertow`，启动器代码无需变化。

### 5.3 新增一个 WebContainer 实现

容器契约与通用逻辑（状态机、远程 URL 下载、上下文路径推导、MAIN 类部署）在 `utils-support-common-starter` 的 `AbstractWebContainer` 中；适配新容器只需实现三个抽象方法：

```java
@Spi("mycontainer")
public class MyWebContainer extends AbstractWebContainer {
    @Override public String getName() { return "mycontainer"; }
    @Override protected void doStart() { /* 启动容器 */ }
    @Override protected void doStop()  { /* 停止容器 */ }
    @Override protected void doDeploy(String archivePath, String contextPath, DeployUnitType type) { /* 部署 */ }
    // 可选：doUndeploy(String contextPath)、doDeployMain(String mainClass, String contextPath)
}
```

并在 `src/main/resources/META-INF/services/com.chua.common.support.network.container.WebContainer` 中登记一行：

```
com.example.MyWebContainer
```

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（`maven.compiler.source/target=25`，父 pom 统一管理）。
- 源码 UTF-8、无 BOM；Lombok 参与编译。

```bash
mvn -pl utils-support-middleware-parent/utils-support-tomcat-starter -am clean package
```

### 6.2 依赖清单

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `4.0.0.51`（`${project.version}`） | `WebContainer` / `AbstractWebContainer` / `WebContainerSetting` / `DeployUnitType` 契约，`ServiceProvider` SPI，`CmdResult` |
| `com.chua:utils-support-runtime-core` | `4.0.0.51`（`${project.version}`） | `RuntimeLauncher` / `RuntimeArtifact` / `RuntimeStatus` |
| `org.apache.tomcat.embed:tomcat-embed-core` | `9.0.93` | Tomcat 嵌入式内核（`Tomcat`、`StandardContext`、`AccessLogValve` 等） |
| `org.apache.tomcat.embed:tomcat-embed-jasper` | `9.0.93` | JSP 编译支持 |

> 父模块 `utils-support-middleware-parent` 通过 `<tomcat.version>9.0.93</tomcat.version>` 统一管理 Tomcat 版本。

### 6.3 GraalVM Native Image

模块自带 `META-INF/native-image/com.chua/utils-support-tomcat-starter/` 下的三份配置，开箱支持 native 编译：

- `reflect-config.json`：`TomcatWebContainer`、`TomcatRuntimeLauncher` 的构造器 / 方法 / 字段全开放；
- `proxy-config.json`：为 `WebContainer`、`RuntimeLauncher` 生成动态代理；
- `resource-config.json`：包含 `META-INF/services/.*`（SPI 注册文件）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Undertow 容器实现 | 在同级模块 `utils-support-undertow-starter`（`TomcatRuntimeLauncher` 的 `args[2]` 可选 `undertow`） |
| 运行时管理（进程守护、systemd / Windows 服务、Java Agent 管理） | 在依赖 `utils-support-runtime-core`（`RuntimeManager`、`ServiceManager` 等）；本模块只提供 `TOMCAT` 启动器 |
| Spring Boot 自动装配 / 配置前缀 | 无 Spring 依赖，源码未提供 `AutoConfiguration` 或 `application.yml` 键 |
| Tomcat XML 配置（`server.xml` / `context.xml` 解析） | 源码未提供；只支持 `WebContainerSetting` 编程式配置 |
| WAR 构建与打包 | 需自行用 Maven/Gradle 打出 WAR；本模块只负责部署与运行 |
| Servlet 应用代码、Filter/Listener 注册 | 不在本模块职责内 |
| HTTP 客户端 / 通用 HTTP 服务端 | 在 `utils-support-common-starter` 的 `network.client` / `network.server` |
| 对象存储、文件系统、消息队列等 | 在 `utils-support-*` 对应 starter（如 `utils-support-minio-starter`、`utils-support-filesystem-*`、`utils-support-kafka-starter`） |

---

## 许可证

Apache License 2.0
