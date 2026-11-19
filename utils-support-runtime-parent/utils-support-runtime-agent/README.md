# utils-support-runtime-agent

> runtime 体系的 **Java Agent 入口模块**：通过 `premain` / `agentmain` 把 `Instrumentation` 注入目标 JVM，启动 Spy 字节码插桩引擎并拉起 APM 采集；自身只包含 Agent 入口与 shaded fat-jar 打包，插桩规则、APM 实现、对外协议由同仓 `utils-support-runtime-*` 兄弟模块提供。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.agent`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项（Agent 参数）](#4-配置项agent-参数)
- [5. 启动流程与类加载](#5-启动流程与类加载)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-agent</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 注意：本模块通常**不作为普通依赖**引入业务工程，而是打成 shaded fat jar 后，以 `-javaagent` 启动参数或 `attach` 注入两种方式使用（见 [快速开始](#3-快速开始)）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 形态 | 可挂载的 Java Agent：`Premain-Class` 与 `Agent-Class` 均为 `com.chua.runtime.agent.RuntimeAgent` | 不是业务 starter，不含 Spring 自动装配，不提供 HTTP 接口 |
| 能力 | 注入 `Instrumentation`、启动 `SpyBootstrap` 插桩引擎、启动 `ApmBootstrap` 采集、对已加载类补 `retransform` | 不实现插桩规则与字节码转换本身（在 `utils-support-runtime-spy`） |
| 失败语义 | 任何异常都被吞掉并记 JUL 日志，**业务进程继续启动**，APM 最多不可用 | 不是业务的强依赖，不允许阻断宿主 JVM |
| 打包 | maven-shade 合并 spy / apm / protocol / core / common-starter / asm / jackson / sqlite / guava 为单 fat jar | 不包含 Shell 服务端实现（在 `utils-support-runtime-shell`） |

**判断口径**：凡是「怎么插桩」「采什么数据」「数据存哪里」「Shell 怎么交互」，都不在本模块；本模块只回答「Agent 怎么被装进 JVM、装进去之后按什么顺序启动」。

### 1.3 规模

- Java 源文件 **2** 个：`RuntimeAgent.java`、`Bootstrap.java`（包 `com.chua.runtime.agent`）。
- 资源文件：`META-INF/native-image/com.chua/utils-support-runtime-agent/` 下的 `reflect-config.json`、`resource-config.json`、`proxy-config.json`。
- 模块定位（取自父 POM 描述）：运行时管理 `core(接口)/spy(字节码)/agent(agent入口)/starter(主入口+依赖)`。

---

## 2. 核心 API 与速查表

模块对外只有两个类，均为无状态静态入口：

```java
package com.chua.runtime.agent;

public class RuntimeAgent {
    /** 运行中注入入口（Manifest: Agent-Class） */
    public static void agentmain(String args, Instrumentation inst);
    /** JVM 启动时挂载入口（Manifest: Premain-Class） */
    public static void premain(String args, Instrumentation inst);
    /** 默认 Shell 端口：4567 */
    public static int getDefaultPort();
    /** 是否已启动（volatile 标记，重复加载会被忽略并告警） */
    public static boolean isStarted();
}

public final class Bootstrap {
    /** 插桩点回调：由 SpyTransformer 注入的字节码以 INVOKESTATIC 方式调用 */
    public static void onIntercept(Object thisRef, String className,
                                   String methodName, String descriptor, String point);
    /** 异常回调 */
    public static void onException(String className, String methodName, Throwable throwable);
}
```

常用能力速查：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| JVM 启动时挂载 | `RuntimeAgent#premain(String, Instrumentation)` | 启动命令加 `-javaagent:agent.jar=参数` |
| 运行中注入 | `RuntimeAgent#agentmain(String, Instrumentation)` | 由 runtime-starter 的 `RuntimeManager.attachToJvm(pid, agentPath, "key=value")` 调用 |
| 方法插桩回调 | `Bootstrap#onIntercept(...)` | `SpyTransformer` 生成的字节码使用固定描述符调用；内部转发 `RuntimeSpy.onIntercept` |
| 异常回调 | `Bootstrap#onException(String, String, Throwable)` | 内部转发 `RuntimeSpy.onException` |
| 默认 Shell 端口 | `RuntimeAgent#getDefaultPort()` | 返回 `4567` |
| 幂等 / 防重入 | `RuntimeAgent#isStarted()` | 已启动时重复加载仅记录 `WARNING` 后返回 |
| 已加载类补插桩 | `SpyBootstrap.getTransformer().retransformLoadedClasses(inst)` | agent 内部自动执行；attach 场景不做这一步，APM 采集恒为零 |
| GraalVM 原生镜像 | `META-INF/native-image/com.chua/utils-support-runtime-agent/*` | 反射/代理/资源三份配置随 jar 发布 |

---

## 3. 快速开始

### 3.1 打包

```bash
# 在 utils-support-runtime-parent 下执行；shade 在 package 阶段产出 fat jar
mvn -o -q package -DskipTests
# 产物：utils-support-runtime-agent/target/utils-support-runtime-agent-4.0.0.51.jar
```

### 3.2 方式一：启动时挂载（premain）

```bash
# 关键顺序：-Xbootclasspath/a 必须写在 -javaagent 之前（原因见第 5 节）
java -Xbootclasspath/a:utils-support-runtime-agent-4.0.0.51.jar \
     -javaagent:utils-support-runtime-agent-4.0.0.51.jar=port=4567,storage.type=sqlite,storage.path=./apm-data \
     -jar app.jar
```

启动成功后可在宿主日志中看到（JUL 输出）：

```text
Runtime Agent 启动中，参数: port=4567,storage.type=sqlite,storage.path=./apm-data
[RuntimeAgent] 已追加 Agent JAR 到系统类加载器: ...
[RuntimeAgent] 已追加 Agent JAR 到启动类加载器: ...
Runtime Agent 启动成功
```

### 3.3 方式二：运行中注入（agentmain）

```java
// RuntimeManager 位于 utils-support-runtime-starter（RuntimeAgent javadoc 注明的注入方式）
RuntimeManager.attachToJvm(pid, agentPath, "port=4567,storage.type=inmemory");
```

attach 场景下 Tomcat / Logback / JDBC 等目标类在注入前就已加载，agent 会在 `ApmBootstrap.start()` 注册完插桩规则后，自动调用 `retransformLoadedClasses(inst)` 对已加载类补一次转换；补转换失败只记 `WARNING`，增量加载的类仍会正常插桩。

### 3.4 失败表现

| 现象 | 日志 | 业务影响 |
| --- | --- | --- |
| `SpyBootstrap.init` 返回 false | `Runtime Agent 初始化失败（APM 不可用，业务进程继续）` | 无，进程正常 |
| `ApmBootstrap.start()` 抛异常 | `APM 引擎启动失败（业务进程继续启动）` | 插桩仍在，只是没有采集落库 |
| `premain` 任何其它异常 | `Runtime Agent 启动异常（业务进程继续启动，APM 不可用）` | 无；`premain` 抛异常会导致 JVM 启动失败，因此这里全部捕获 |

---

## 4. 配置项（Agent 参数）

参数写在 `-javaagent:agent.jar=` 之后或 `attachToJvm` 的第三个参数中，格式为**逗号分隔的 `key=value`**：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `plugins` | 插件目录路径 | 源码未提供 |
| `enabled` | 是否启用 | `true` |
| `port` | Shell 端口 | `4567` |
| `storage.type` | APM 存储实现名 | `inmemory` |
| `storage.retention.ms` | 数据保留时长（毫秒） | 7 天 |
| `storage.capacity` | 存储容量上限 | `100000` |
| `storage.path` | 存储落盘目录（`sqlite` 等非内存实现使用） | 临时目录 |

优先级：**Agent 参数 > 系统属性（`apm.storage.*`）> 默认值**。

`storage.type` 的可选值：`inmemory` / `sqlite` / `noop`。选择 `sqlite` 时，sqlite-jdbc 驱动已打进 fat jar，agent 模式（bootstrap classloader）下也可启用。

除上述参数外，本模块**无其它配置文件**：模块 `src/main/resources` 仅含 GraalVM native-image 三份配置，没有 `application.properties` / `META-INF/extensions` 等配置项。

日志：Agent 自身使用 **JUL**（`java.util.logging.Logger`），不依赖 slf4j；fat jar 中 slf4j 被重定位为 `com.chua.runtime.shaded.slf4j`，不会污染宿主日志门面。

---

## 5. 启动流程与类加载

`premain` / `agentmain` 的执行顺序（`agentmain` 直接委托 `premain`）：

```text
1. started 幂等检查（已启动 → WARNING 后返回）
2. appendToClasspath(inst)
     ├─ inst.appendToSystemClassLoaderSearch(agentJar)   // 系统类加载器
     ├─ inst.appendToBootstrapClassLoaderSearch(agentJar) // 启动类加载器
     └─ 反射确认 com.chua.runtime.agent.Bootstrap 在两个加载器中均可见
3. SpyBootstrap.init(args, inst)                          // 启动 Spy 插桩引擎
4. new ApmBootstrap(Paths.get(java.io.tmpdir)).start()    // 启动 APM 采集（失败不阻断）
5. SpyBootstrap.getTransformer().retransformLoadedClasses(inst) // 已加载类补插桩
6. started = true
```

### 为什么必须 `-Xbootclasspath/a` 优先于 `-javaagent`

`java.net.Socket` 等 JDK 类由 **bootstrap classloader** 加载，它们被织入的
`INVOKESTATIC com.chua.runtime.agent.Bootstrap.onIntercept` 必须在 bootstrap classloader 内可解析。
因此 shaded agent jar 会**同时追加到系统类路径与 bootstrap 类路径**，使 `Bootstrap`、runtimespy、拦截器接口在 bootstrap 内一致，避免 `LinkageError`。

同时，启动命令中 `-Xbootclasspath/a:agent.jar` 要写在 `-javaagent:agent.jar` **之前**：这样 `Premain-Class`（`RuntimeAgent`）本身由 bootstrap 加载，避免 javaagent 路径先把 runtime 智能体装入 app classloader、再把同名类塞入 bootstrap，触发 loader constraint violation。

`Bootstrap.onIntercept` 的方法签名必须与 `RuntimeSpy` 中同名方法一致（`spy` 转换生成的字节码使用固定描述符）；该方法把受拦截实例（`Socket` / `HttpURLConnection` / 文件流等，静态方法或 JDK 内部时为 `null`）写入 intercept 上下文供处理器使用。插件桩自身任何异常都只记 `FINE` 日志，**绝不让业务方法失败**。

---

## 6. SPI 扩展点

- 本模块自身**不定义 SPI 接口、不注册扩展实现**：`src/main/resources` 下没有 `META-INF/extensions` 目录，扩展点全部来自依赖模块。
- APM 存储实现按 `storage.type` 名称（`inmemory` / `sqlite` / `noop`）装配，实现位于 `utils-support-runtime-apm`；本模块只透传参数。
- 打包侧保真 SPI：maven-shade 的 `ServicesResourceTransformer` 合并各依赖的 `META-INF/services`；native-image `resource-config.json` 保留 `META-INF/services/.*` 与 `META-INF/extensions/.*` 两类资源，保证 shaded jar 在 native-image 下仍能完成 SPI 发现。
- `reflect-config.json` 为 `com.chua.runtime.agent.Bootstrap`、`RuntimeAgent`、`RuntimeAgent$1` 开放全部构造器/方法/字段反射；`proxy-config.json` 为空。

---

## 7. 构建与依赖

### 7.1 依赖清单

| 依赖 | Scope | 原因（取自 pom 注释） |
| --- | --- | --- |
| `com.chua:utils-support-runtime-spy` | compile | Spy 字节码插桩引擎（`SpyBootstrap` / `RuntimeSpy`） |
| `com.chua:utils-support-runtime-apm` | compile | APM 引擎（`ApmBootstrap`） |
| `com.chua:utils-support-runtime-protocol` | compile | 运行时通信协议 |
| `org.xerial:sqlite-jdbc` | runtime | sqlite 落盘；驱动打进 agent fat jar，agent 模式（bootstrap CL）下仍可启用 `apm.storage.type=sqlite` |
| `com.google.guava:guava` | runtime | sqlite-jdbc 的 `org.sqlite.core.DB` 引用了 guava 的 `Table`；驱动 jar 本身不声明该依赖 |
| `com.fasterxml.jackson.core:jackson-databind` | runtime | 存储层属性列走 `com.chua.common.support.lang.json.Json`，依赖 Jackson；隔离加载器看不到宿主 classpath，缺了属性列会降级为扁平文本 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | runtime | Jackson JSR-310（日期时间）支持 |

### 7.2 shade 打包

- `maven-jar-plugin` 写入 Manifest：`Premain-Class` / `Agent-Class` = `com.chua.runtime.agent.RuntimeAgent`，`Can-Redefine-Classes` / `Can-Retransform-Classes` / `Can-Set-Native-Method-Prefix` = `true`，并带默认 Implementation 条目。
- `maven-shade-plugin`（`createDependencyReducedPom=false`）合并：runtime-spy / runtime-apm / runtime-protocol / runtime-plugin / runtime-core / utils-support-common-starter / asm（asm、asm-commons、asm-tree、asm-util）/ slf4j-api / sqlite-jdbc / guava（含 failureaccess）/ jackson-databind / jackson-core / jackson-annotations / jackson-datatype-jsr310。
- 重定位：`org.slf4j` → `com.chua.runtime.shaded.slf4j`。
- 过滤：剔除所有依赖的 `META-INF/*.SF` / `*.DSA` / `*.RSA` 签名文件。

### 7.3 父模块

隶属 `com.chua:utils-support-runtime-parent:4.0.0.51`，兄弟模块：`utils-support-runtime-core`、`utils-support-runtime-plugin`、`utils-support-runtime-spy`、`utils-support-runtime-apm`、`utils-support-runtime-shell`、`utils-support-runtime-starter`、`utils-support-runtime-protocol`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在模块 |
| --- | --- |
| 插桩规则、`SpyTransformer` 字节码转换、`RuntimeSpy` 上下文 | `utils-support-runtime-spy` |
| APM 指标采集、`storage.type` 存储实现（`inmemory` / `sqlite` / `noop`） | `utils-support-runtime-apm` |
| 运行时通信协议与数据模型 | `utils-support-runtime-protocol` |
| Shell 服务端实现（`port` 参数指向的服务） | `utils-support-runtime-shell` |
| 主入口、依赖编排、`RuntimeManager.attachToJvm` 等运维 API | `utils-support-runtime-starter` |
| 接口契约（`core`）与插件机制（`plugin`） | `utils-support-runtime-core` / `utils-support-runtime-plugin` |
| 通用工具（`ReflectUtils` 等）与 SPI 框架 | `utils-support-common-starter` |

本模块**不做**：不定义插桩规则、不落库、不提供查询/展示界面、不管理业务连接池；它是把上述能力**装进目标 JVM 的那枚壳**。
