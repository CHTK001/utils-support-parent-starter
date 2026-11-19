# utils-support-runtime-plugin

> runtime 体系的**插件机制底座**：定义插件 SPI（`Plugin`）与字节码插桩点词汇表（`InterceptPoint`），并提供从磁盘目录扫描插件、隔离类加载、注册与生命周期管理的完整装载链路；自身不含插桩规则、APM 采集与 Agent 注入逻辑。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，取自父 POM）｜包名：`com.chua.runtime.plugin`（含子包 `com.chua.runtime.plugin.loader`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 插件目录布局与加载时序](#4-插件目录布局与加载时序)
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
    <artifactId>utils-support-runtime-plugin</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 形态 | 纯 Java 库模块：插件 SPI 契约 + 磁盘扫描装载器 | 不是 Spring starter：无自动装配、无 HTTP 接口 |
| 契约 | 定义插件 SPI `Plugin`、插桩点枚举 `InterceptPoint`、插件上下文 `PluginContext` | 不实现任何插桩规则、不做字节码转换 |
| 加载 | 一个插件目录一个 `PluginClassLoader`（child-first 隔离加载，支持 `lib/` 传递依赖） | 不提供单插件热卸载/热替换 API（注册表仅有 `clear()`） |
| 依赖 | 只依赖 `utils-support-common-starter`（`Wraps`、`ReflectUtils`） | 不依赖 Spring、数据库驱动、网络协议栈 |

**判断口径**：凡是「怎么插桩、采什么数据、Agent 怎么注入 JVM」都不在本模块；本模块只回答「插件长什么样、怎么被发现、怎么被加载与启停」。

### 1.3 规模

- Java 源文件 **7** 个：`com.chua.runtime.plugin` 包 3 个（`Plugin`、`PluginContext`、`InterceptPoint`），`com.chua.runtime.plugin.loader` 包 4 个（`PluginManager`、`PluginScanner`、`PluginRegistry`、`PluginClassLoader`）。
- 资源文件：`META-INF/native-image/com.chua/utils-support-runtime-plugin/` 下的 `reflect-config.json`、`resource-config.json`、`proxy-config.json`（`proxy-config.json` 为空数组 `[]`）。
- 无 `src/test` 测试源码（源码树仅 `src/main`）。

---

## 2. 核心 API 与速查表

### 2.1 `Plugin` —— 插件 SPI 接口

所有插件实现类必须实现此接口：

```java
package com.chua.runtime.plugin;

public interface Plugin {
    String name();                                   // 插件名称
    String version();                                // 插件版本
    void init(PluginContext context) throws Exception; // 初始化，拿到上下文
    void start() throws Exception;                   // 插件开始工作
    void stop() throws Exception;                    // 停止插件
    String status();                                 // 状态描述
    boolean isRunning();                             // 是否已启动
}
```

注册方式：在插件目录内提供 `META-INF/services/com.chua.runtime.plugin.Plugin` 文件，一行一个实现类全名。

### 2.2 `InterceptPoint` —— 字节码插桩点枚举

定义所有可插桩的字节码位置，共 **19** 个常量：

| 常量 | key（`getKey()`） | 含义 |
| --- | --- | --- |
| `ENTRY` | `entry` | 方法入口（执行前） |
| `EXIT` | `exit` | 方法出口（正常返回后） |
| `EXCEPTION` | `exception` | 异常出口（抛异常时） |
| `LOG_PRE` / `LOG_POST` | `log_pre` / `log_post` | 日志调用前 / 后 |
| `NET_CONNECT_PRE` / `NET_CONNECT_POST` | `net_connect_pre` / `net_connect_post` | 套接字连接前 / 后 |
| `NET_READ_PRE` | `net_read_pre` | 套接字读取前 |
| `NET_WRITE_PRE` | `net_write_pre` | 套接字写入前 |
| `FILE_OPEN_PRE` / `FILE_OPEN_POST` | `file_open_pre` / `file_open_post` | 文件打开前 / 后 |
| `FILE_READ_PRE` | `file_read_pre` | 文件读取前 |
| `HTTP_REQUEST_PRE` | `http_request_pre` | HTTP 请求发送前 |
| `HTTP_RESPONSE_POST` | `http_response_post` | HTTP 响应接收后 |
| `DB_SQL_PRE` / `DB_SQL_POST` | `db_sql_pre` / `db_sql_post` | 数据库 SQL 执行前 / 后 |
| `THREAD_CREATE_PRE` | `thread_create_pre` | 线程创建前 |
| `CLASS_LOAD_PRE` | `class_load_pre` | 类加载前 |
| `DURATION` | `duration` | 方法执行耗时统计 |

静态查找：`InterceptPoint.of(String key)` —— 按 key 匹配，**未命中返回 `null`**（不抛异常）。

### 2.3 `PluginContext` —— 插件上下文

插件运行时配置和数据访问入口，构造时绑定插件目录：

```java
package com.chua.runtime.plugin;

public class PluginContext {
    public PluginContext(Path pluginDir);        // 绑定插件目录
    public Path getPluginDir();                  // 插件根目录
    public Map<String, String> getProperties();  // 内存属性表（ConcurrentHashMap）
    public void setProperty(String key, String value);
    public String getProperty(String key);
    public String getProperty(String key, String defaultValue);
    public Object getRuntimeManager();           // 运行时管理器引用（类型即 Object）
    public void setRuntimeManager(Object runtimeManager);
    public void info(String message);            // JUL INFO，前缀 "[PluginContext]"
    public void warn(String message);            // JUL WARNING
    public void error(String message, Throwable throwable); // JUL SEVERE
}
```

注意：`error(message, throwable)` 的格式串只有一个 `%s`，`throwable` 作为多余参数被 `String.format` 忽略、也未传给 `Logger`，因此**异常堆栈不会随该日志输出**。

### 2.4 `loader` 包 —— 扫描、注册与生命周期

```java
package com.chua.runtime.plugin.loader;

/** 门面：组合 PluginScanner 与 PluginRegistry */
public class PluginManager {
    public PluginManager(Path pluginRoot);                          // 父加载器 = 系统类加载器
    public PluginManager(Path pluginRoot, ClassLoader parentLoader);
    public int load();            // 扫描 + 注册，返回数量；IOException 被捕获记日志后返回 0
    public void start();          // 启动所有已注册插件
    public void stop();           // 停止所有插件
    public Plugin get(String name);        // 按名取插件（键 = 插件目录名）
    public PluginInfo getInfo(String name);
    public List<String> getNames();
    public List<PluginInfo> getRunning();  // 过滤 isRunning() == true
    public void setRuntimeManager(Object runtimeManager); // 注入自身及每个 PluginContext
    public Path getPluginRoot();
    public PluginRegistry getRegistry();
    public int size();
}

/** 扫描器：遍历插件根目录下的一级子目录并逐个装载 */
public class PluginScanner {
    public PluginScanner(Path pluginRoot);
    public PluginScanner(Path pluginRoot, ClassLoader parentLoader);
    public List<PluginInfo> scan() throws IOException; // 目录不存在会自动创建后返回空列表
    public List<PluginInfo> getPlugins();              // 不可变视图
    public record PluginInfo(String name, Plugin plugin,
                             PluginClassLoader classLoader, PluginContext context) { }
}

/** 注册表：按名称管理已加载插件 */
public class PluginRegistry {
    public void register(PluginInfo info); // 重名抛 IllegalArgumentException("插件已存在: " + name)
    public Plugin get(String name);
    public PluginInfo getInfo(String name);
    public List<String> getNames();
    public List<PluginInfo> list();        // 不可变视图
    public int size();
    public boolean contains(String name);
    public void clear();
    public void startAll();                // 单插件异常只记日志，不影响其他插件
    public void stopAll();
    public List<PluginInfo> getRunning();
}

/** 插件类加载器：每插件独立 classpath，child-first */
public class PluginClassLoader extends URLClassLoader {
    public PluginClassLoader(String pluginName, Path pluginDir,
                             ClassLoader parent) throws IOException;
    public Plugin loadPlugin(String className) throws Exception; // ReflectUtils.instantiate 实例化
    public List<String> scanPlugins();     // 解析 META-INF/services/com.chua.runtime.plugin.Plugin
    public String getPluginName();
    public Path getPluginDir();
    public Set<String> getLoadedClasses(); // 已加载类名集合（不可变视图）
}
```

要点：

- **`PluginInfo.name()` 是插件目录名**（`pluginDir.getFileName()`），不是 `Plugin#name()` 的返回值；`get` / `getInfo` / `getNames` / `contains` 的键都是目录名。一个目录内若有多个 SPI 实现类，会产出多条同 `name` 的记录，故该值标识「插件包」而非「插件实现」。
- `PluginManager.load()` 失败不抛异常：捕获 `IOException` 记 `SEVERE` 后返回 `0`。

### 2.5 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 实现一个插件 | 实现 `com.chua.runtime.plugin.Plugin` | 7 个方法：`name` / `version` / `init` / `start` / `stop` / `status` / `isRunning` |
| 让插件被发现 | `META-INF/services/com.chua.runtime.plugin.Plugin` | 一行一个实现类全名，空行与 `#` 注释行忽略 |
| 扫描并加载插件目录 | `new PluginManager(Path).load()` | 返回加载数量；目录不存在会自动创建 |
| 启停全部插件 | `manager.start()` / `manager.stop()` | 单插件异常只记日志，不阻断其他插件 |
| 按名取插件 | `manager.get(目录名)` | 键是插件目录名，不是 `Plugin#name()` |
| 查运行中的插件 | `manager.getRunning()` | 按 `Plugin#isRunning()` 过滤 |
| 给插件传配置 | `PluginContext#setProperty` / `getProperty` | 内存属性表，无持久化、无内置键 |
| 声明可插桩位置 | `InterceptPoint` 枚举 | 19 个插桩点；`InterceptPoint.of(key)` 按 key 查找 |

---

## 3. 快速开始

### 3.1 实现一个插件

```java
package demo;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;

public class DemoPlugin implements Plugin {

    private PluginContext context;
    private volatile boolean running;

    @Override public String name() { return "demo"; }
    @Override public String version() { return "1.0.0"; }

    @Override public void init(PluginContext context) {
        this.context = context;
        context.info("demo 初始化，插件目录=" + context.getPluginDir());
    }

    @Override public void start() { running = true; }
    @Override public void stop()  { running = false; }
    @Override public String status() { return running ? "RUNNING" : "STOPPED"; }
    @Override public boolean isRunning() { return running; }
}
```

### 3.2 放进插件目录

```text
plugins/                                      ← pluginRoot（PluginManager 构造参数）
└── demo-plugin/                              ← 插件目录（目录名即注册键）
    ├── META-INF/
    │   └── services/
    │       └── com.chua.runtime.plugin.Plugin  ← 内容一行：demo.DemoPlugin
    ├── demo/
    │   └── DemoPlugin.class
    └── lib/                                   ← 可选：传递依赖 JAR
        └── some-dep.jar
```

### 3.3 宿主加载

```java
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.loader.PluginManager;
import java.nio.file.Path;

public class Host {
    public static void main(String[] args) {
        PluginManager manager = new PluginManager(Path.of("plugins"));
        int count = manager.load();   // 扫描 + 反射实例化 + init
        manager.start();              // 逐个 start()

        // 注意：注册表按「插件目录名」索引，而不是 Plugin#name() 的返回值
        Plugin demo = manager.get("demo-plugin");
        System.out.println(count + " / " + demo.status());

        manager.stop();
    }
}
```

### 3.4 期望日志（JUL 输出）

```text
正在加载插件: demo-plugin
插件[demo-plugin] 加载成功: demo
注册插件: demo-plugin
加载了 1 个插件
插件[demo-plugin] 启动成功
1 / RUNNING
插件[demo-plugin] 停止成功
```

---

## 4. 插件目录布局与加载时序

### 4.1 目录识别规则（`PluginScanner.isPluginDirectory`）

- 只遍历 `pluginRoot` 下的一级**子目录**；以 `.` 开头、名为 `lib`、名为 `config` 的一级子目录被排除。
- 满足其一即视为插件目录：① 目录内直接存在 `META-INF/services/com.chua.runtime.plugin.Plugin`；② 目录直接子级中含 `.jar` 文件。
- 类 Javadoc 声明支持「扫描顶层 JAR 文件 / 嵌套目录下的 JAR」；当前实现 `scan()` 只遍历一级子目录，顶层散置的 JAR 文件不会被当作插件加载。

### 4.2 加载时序（`PluginManager.load()` → `PluginScanner.scanPlugin` → `PluginRegistry.register`）

```text
1. scan()：遍历 pluginRoot 一级子目录（不存在的根目录会自动创建）
2. 对每个插件目录：
   a. new PluginClassLoader(目录名, 目录, 父加载器)     // 类路径 = 目录本身 + lib/*.jar
   b. scanPlugins()：读 META-INF/services/com.chua.runtime.plugin.Plugin
      （跳过空行与 # 注释行；为空则记 WARNING「未找到 SPI 配置」并跳过该目录）
   c. 对每个实现类名：
      loadPlugin(className) → ReflectUtils.instantiate 反射实例化
      new PluginContext(插件目录) → plugin.init(context)
      打包为 PluginInfo(目录名, plugin, classLoader, context) 加入列表
      （任一实现类加载/初始化抛异常：记 SEVERE，跳过该实现类，不影响其余）
3. 逐条 registry.register(info)（重名抛 IllegalArgumentException）
4. 返回 registry.size()
```

`start()` / `stop()` 分别委托 `registry.startAll()` / `stopAll()`：按注册顺序逐个调用，单个插件异常只记日志。

### 4.3 类加载策略（`PluginClassLoader`）

- **child-first**：先查 `findLoadedClass`；非 bootstrap 类先 `findClass`（本插件目录 + `lib/*.jar`），找不到再回落父加载器链。
- **bootstrap 委托**：`java.` / `javax.` / `sun.` / `jdk.` / `com.sun.` / `com.oracle.` 前缀的类一律由父加载器加载。
- 源码注释明确：不能重写 `findClass`，否则 `ClassLoader.loadClass` 回调 `findClass` 会与本类形成无限互调。
- 构造器声明 `throws IOException`；`lib` 目录下 JAR 转换 URL 失败只记 `WARNING`。

---

## 5. 配置项

**无。** 依据：

- `src/main/resources` 仅含 3 份 GraalVM native-image 元数据（`reflect-config.json` / `resource-config.json` / `proxy-config.json`），没有 `application.properties`、`META-INF/extensions` 等业务配置文件；
- 源码中未出现任何 `System.getProperty` 读取或配置键常量；
- `PluginContext` 的属性表是运行时内存 `ConcurrentHashMap`，初始为空，由宿主程序通过 `setProperty` 注入，本模块不预置任何键、不持久化。

日志：本模块代码统一使用 **JUL**（`java.util.logging.Logger`），源码中未出现 slf4j 调用（父 POM 虽统一注入 `slf4j-api`，本模块源码未使用）。

---

## 6. SPI 扩展点

- **本模块是 `Plugin` SPI 的定义方，自身不注册任何实现**：`src/main/resources` 下没有业务性的 `META-INF/services` 或 `META-INF/extensions` 声明。
- **发现机制不走 `java.util.ServiceLoader`**：`PluginClassLoader.scanPlugins()` 手工解析插件目录内的 `META-INF/services/com.chua.runtime.plugin.Plugin`（跳过空行与 `#` 注释），再由 `loadPlugin(className)` 逐个反射实例化——插件类因此与宿主 classpath 隔离。
- **`InterceptPoint` 是插桩点词汇表**：19 个 key 供插桩规则（消费方在同仓 `utils-support-runtime-spy` 等模块）统一引用；本模块只定义，不消费。
- **GraalVM native-image 保真**：`resource-config.json` 保留 `META-INF/services/.*` 与 `META-INF/extensions/.*` 两类资源；`reflect-config.json` 为 `com.chua.runtime.plugin.Plugin`、`com.chua.runtime.plugin.PluginClassLoader` 开放全部构造器/方法/字段反射，另列有 `com.chua.runtime.plugin.DefaultPluginManager`（该类的源码在本工作区**源码未提供**）；`proxy-config.json` 为空数组。

---

## 7. 构建与依赖

### 7.1 依赖清单

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `Wraps`（集合包装）、`ReflectUtils#instantiate`（反射实例化插件） |
| `org.projectlombok:lombok` | provided（父 POM 统一注入） | 本模块 7 个源文件均未使用 Lombok 注解 |
| `org.slf4j:slf4j-api` | compile（父 POM 统一注入） | 本模块源码未使用，日志走 JUL |

构建命令（工作区交付口径）：

```bash
mvn -o -q compile -DskipTests
```

### 7.2 谁依赖本模块

- `utils-support-runtime-spy`、`utils-support-runtime-apm`、`utils-support-runtime-protocol` 以 compile 依赖引入本模块；
- `utils-support-runtime-agent` 打 shade fat jar 时 include 本模块。

### 7.3 父模块与兄弟模块

隶属 `com.chua:utils-support-runtime-parent:4.0.0.51`，其子模块共 8 个：`utils-support-runtime-core`、`utils-support-runtime-plugin`（本模块）、`utils-support-runtime-spy`、`utils-support-runtime-apm`、`utils-support-runtime-agent`、`utils-support-runtime-shell`、`utils-support-runtime-starter`、`utils-support-runtime-protocol`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在模块 |
| --- | --- |
| Agent 入口（`premain` / `agentmain`、fat jar 打包） | `utils-support-runtime-agent` |
| 插桩规则、`SpyTransformer` 字节码转换、`RuntimeSpy` 上下文 | `utils-support-runtime-spy` |
| APM 指标采集与存储实现 | `utils-support-runtime-apm` |
| 运行时通信协议与数据模型 | `utils-support-runtime-protocol` |
| 主入口、依赖编排、`RuntimeManager` 等运维 API | `utils-support-runtime-starter` |
| Shell 服务端实现 | `utils-support-runtime-shell` |
| 接口契约（core） | `utils-support-runtime-core` |
| 通用工具（`ReflectUtils`、`Wraps` 等）与 SPI 框架 | `utils-support-common-starter` |

本模块**不做**：不注入 `Instrumentation`、不转换字节码、不采集/存储指标、不提供管理界面；它是插件机制的**契约与装载底座**。
