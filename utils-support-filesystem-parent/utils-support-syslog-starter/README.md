# utils-support-syslog-starter

> 跨平台（Windows / Linux / macOS）**系统日志检索与实时监听**模块：一个 `SystemLogService` 门面自动选择平台提供者，Windows 经 Java FFM 直调 advapi32 事件日志 API，Linux 经 libsystemd journald FFM（回退 `/var/log`），macOS 经 `log show` 命令（回退 `/var/log`）；支持 glob 通配符搜索、级别过滤与 `tail -f` 式轮询推送，查询模型另预留分页游标 `after` 与持续监听 `tail` 字段（内置实现未消费，供自定义 `SystemLogProvider` 实现）。
>
> 版本：`4.0.0.51`｜JDK：`25`｜包名：`com.chua.filesystem.log.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 常用能力速查](#13-常用能力速查)
- [2. 核心 API](#2-核心-api)
  - [2.1 包地图](#21-包地图)
  - [2.2 门面 SystemLogService](#22-门面-systemlogservice)
  - [2.3 查询模型](#23-查询模型)
  - [2.4 实时监听 SyslogPolledDirectory](#24-实时监听-syslogpolleddirectory)
  - [2.5 syslog: / file: 资源协议](#25-syslog--file-资源协议)
  - [2.6 桥接层](#26-桥接层)
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
    <artifactId>utils-support-syslog-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 能力 | Windows Event Log / Linux journald / macOS unified log 的**统一检索门面**与**轮询式实时监听** | 不替代 SLF4J / Logback / Log4j，不收集应用自身日志 |
| 平台 | 三平台自动适配，原生通道不可用时按平台回退（Linux/macOS 回退 `/var/log` 文件读取） | 不含日志采集、转发、集中存储（ELK / Kafka 等）能力 |
| 集成 | 以 `ResourceFinder` 形式接入 `syslog:` / `file:` 统一资源查询协议 | 不是 Spring Boot starter：无自动装配、无配置属性，纯工具库 |
| 依赖 | 仅依赖 `utils-support-common-starter` 与 lombok | 不依赖 Spring、数据库驱动或日志框架实现 |

**判断口径**：凡「读取操作系统日志」的需求放本模块；凡「应用打日志」的需求用日志框架；凡「文件读写/归档」用 `utils-support-common-starter` 的 `FileSystem`。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 一步检索系统日志 | `SystemLogService.getInstance().search("disk", LogLevel.ERROR, 50)` | 门面单例，自动选平台 |
| 复杂查询 | `LogQuery.builder()...` + `search(query)` | 来源 / 级别 / 条数 / 游标 / 排序 |
| 查可用日志源 | `SystemLogService.getInstance().getSources()` | Windows 返回 System/Application/Security 等 |
| 实时监听新日志（替代 `tail -f`） | `SyslogPolledDirectory.builder()...start(...)` | 定时轮询增量推送，`PolledListener` 回调 |
| `syslog:` 协议统一查询 | `new SystemLogResourceFinder().find("syslog:*error*")` | Ant 风格 glob，支持 `&maxResults=`、`&order=` |
| `file:` 协议文件搜索 | `new FileResourceFinder().find("file:/var/log/*.log")` | Windows `where /r`、Linux `locate`、macOS `mdfind`，回退 NIO 遍历 |
| 自定义平台提供者 | 实现 `SystemLogProvider` + `META-INF/services` 登记 | ServiceLoader 优先于内置桥接 |
| 直接绑定原生函数 | `NativeFunctionRegistry.ofLibrary("Advapi32")` | FFM `MethodHandle` 缓存注册表 |

---

## 2. 核心 API

### 2.1 包地图

| 包 | 职责 | 代表类型 |
| --- | --- | --- |
| `com.chua.filesystem.log.support` | 统一门面 | `SystemLogService` |
| `...log.support.model` | 查询条件、日志条目、级别枚举 | `LogQuery`、`LogEntry`、`LogLevel` |
| `...log.support.spi` | 平台提供者 SPI | `SystemLogProvider` |
| `...log.support.spi.impl` | 三平台内置实现 | `WindowsEventLogProvider`、`LinuxJournaldProvider`、`MacOSUnifiedLogProvider` |
| `...log.support.bridge` | FFM 桥接、原生函数注册表、平台检测 | `SystemLogBridge`、`NativeFunctionRegistry`、`PlatformSystems` |
| `...log.support.polling` | 实时轮询监听 | `SyslogPolledDirectory` |
| `...log.support.resolver` | `syslog:` / `file:` 资源协议查找 | `SystemLogResourceFinder`、`FileResourceFinder` |

模块共 **14 个 Java 文件**、**7 个包**，无 Spring 依赖。

### 2.2 门面 SystemLogService

```java
SystemLogService service = SystemLogService.getInstance();   // 双重检查锁单例
```

| 方法 | 说明 |
| --- | --- |
| `search(String pattern)` | 按模式搜索，默认最多 100 条 |
| `search(String pattern, LogLevel minLevel)` | 加最低级别过滤 |
| `search(String pattern, LogLevel minLevel, int maxResults)` | 限定返回条数 |
| `search(LogQuery query)` | 完整查询；平台不可用或内部异常时返回空列表（不抛出） |
| `getSources()` | 当前平台可用的日志源名称列表 |
| `getProvider()` | 取底层 `SystemLogProvider` |
| `isAvailable()` | 当前平台是否解析到可用提供者 |

提供者解析顺序（构造时完成）：先 `ServiceLoader` 加载 `SystemLogProvider` 实现并取第一个 `isPlatformSupported() == true` 的；没有则走 `SystemLogBridge.getInstance().initialize()` + `createProvider()` 按当前平台创建内置实现。

### 2.3 查询模型

**LogQuery**（record，`LogQuery.builder()` 构建，另有两个快捷工厂 `LogQuery.of(pattern)`、`LogQuery.of(pattern, minLevel)`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `source` | `String` | 日志源（如 `System`、`Application`、`Security`、`journald`），空表示所有源 |
| `pattern` | `String` | glob 通配符模式（如 `*disk*`），空表示不过滤；匹配不区分大小写 |
| `minLevel` | `LogLevel` | 最低日志级别，空表示所有级别 |
| `maxResults` | `int` | 最大返回条数，`<= 0` 时取默认 `100`（`DEFAULT_MAX_RESULTS`） |
| `after` | `String` | 分页游标（上一页最后一条的游标值），空表示第一页 |
| `tail` | `boolean` | 是否持续监听新日志（类似 `tail -f`），默认 `false` |
| `order` | `String` | `"desc"`（默认，最新在前）/ `"asc"`（最早在前），常量 `LogQuery.ORDER_DESC` / `LogQuery.ORDER_ASC` |

**LogEntry**（record）：`timestamp`（ISO-8601 格式字符串）、`level`、`source`、`message`、`provider`（`windows` / `linux` / `macos`）、`rawData`（平台原生格式，当前内置实现传 `null`）。

**LogLevel** 枚举：`TRACE(0)`、`DEBUG(1)`、`INFO(2)`、`WARNING(3)`、`ERROR(4)`、`CRITICAL(5)`；`getSeverity()` 取数值，`meetsMinimum(LogLevel)` 判断是否达到最低级别（`null` 视为不限）。

### 2.4 实时监听 SyslogPolledDirectory

实现 `com.chua.common.support.lang.directory.PolledDirectory`，定时调用 `SystemLogService` 拉取最新日志，以上一条日志的 `timestamp` 为游标做增量对比，仅向监听器推送新增条目（事件类型 `WatcherEvent.MODIFY`，`EventObserver.getSource()` 返回 `LogEntry`）。首次轮询只记录游标、不推送历史数据；每轮拉取上限为 `max(pollIntervalSeconds * 10, 50)` 条，按升序查询保证游标单调前进。

```java
SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
        .source("System")            // 可选：日志来源
        .pattern("*error*")          // 可选：消息 glob 模式
        .minLevel(LogLevel.ERROR)    // 可选：最低级别
        .pollIntervalSeconds(2)      // 轮询间隔（秒），默认 5
        .build();

watcher.addListener(new PolledListener() {
    @Override
    public void onModify(WatcherEvent event, EventObserver observer) {
        LogEntry entry = (LogEntry) observer.getSource();
        System.out.println("[" + entry.level() + "] " + entry.source() + ": " + entry.message());
    }
});

watcher.start(new DirectoryPollerEnvironment(null, 2, TimeUnit.SECONDS)); // 也可只设间隔
// watcher.getLastTimestamp();  // 当前游标
// watcher.upgrade();           // 手动执行一次轮询
watcher.close();                 // 停止并清空监听器
```

| 成员 | 说明 |
| --- | --- |
| `Builder.source(String)` / `pattern(String)` / `minLevel(LogLevel)` | 查询过滤条件 |
| `Builder.pollIntervalSeconds(int)` | 轮询间隔，默认 5 秒 |
| `Builder.service(SystemLogService)` | 注入自定义门面（测试用），缺省用 `SystemLogService.getInstance()` |
| `start(DirectoryPollerEnvironment)` | 启动；未传执行器时内部启动守护线程 `SyslogPoller-<source>`（未指定来源时为 `SyslogPoller-all`），环境为 `null` 时沿用 builder 的间隔 |
| `start(DirectoryPollerEnvironment, DirectoryPollerExecutor)` | 传入执行器（如 `VirtualThreadPollerExecutor`）驱动轮询 |
| `upgrade()` | 执行一次轮询对比 |
| `getLastTimestamp()` | 最后已知日志时间戳游标 |
| `close()` | 停止轮询、清空监听器与游标 |
| `isDelegatedOperatingSystem()` | 返回 `false`：本实现为定时轮询，不委托 OS 事件 |

### 2.5 syslog: / file: 资源协议

两个 `ResourceFinder` 已在 `META-INF/services/com.chua.common.support.file.resource.ResourceFinder` 登记，可被统一资源查询框架按协议调用。

**SystemLogResourceFinder**（协议 `syslog`，常量 `SystemLogResourceFinder.PROTOCOL`）：

```text
syslog:*error*                 -- 消息含 "error"（大小写不敏感）
syslog:*error*&maxResults=10   -- 同上，最多 10 条
syslog:disk                    -- 精确匹配 "disk"
syslog:?error                  -- 任意单字符前缀 + "error"
```

- glob 风格附加参数：`maxResults`（别名 `max`、`size`）、`order`（`asc` / `desc`）。
- 旧式 `?key=value` 语法仍支持：`syslog:?pattern=*error*&maxResults=50`、`syslog:?pattern=*disk*&level=ERROR`，另有 `source`、`order`。
- 命中结果包装为只读 `Resource`：内容为 `[timestamp] [LEVEL] [source] message` 的 UTF-8 字节，`getUrlPath()` 形如 `syslog-<timestamp>-<source>`。

**FileResourceFinder**（协议 `file`）：优先调用 OS 原生搜索工具——Windows `where /r`、Linux `locate`（plocate/mlocate，库缺失时尝试自动安装并 `updatedb`）、macOS `mdfind`（Spotlight）；任一失败回退 Java NIO `Files.walkFileTree`。匹配模式沿用 glob（`*` → `.*`、`?` → `.`）；`**` 时最大遍历深度 128，否则按模式中的路径层数收敛。

### 2.6 桥接层

| 类型 | 说明 |
| --- | --- |
| `SystemLogBridge` | 单例（`getInstance()`）。`initialize()` 按平台加载原生库：Windows 加载 `Advapi32`、Linux 加载 `systemd`（失败则 Linux 回退 `/var/log`）；`createProvider()` 按平台 `new` 对应内置实现；`getWin32Registry()` / `getLinuxRegistry()` 暴露函数注册表 |
| `NativeFunctionRegistry` | FFM 原生函数注册表。`ofLibrary(name)` 借助 common-starter 的 `NativeUtils.load` 加载库（`System.loadLibrary` → 类路径 `/native/{platform}/` → 用户目录 `~/.native/`，含架构回退）；`register(name, descriptor)` 按「函数名@签名」缓存 `MethodHandle`，符号缺失返回 `null`；`require(...)` 符号缺失抛 `UnsatisfiedLinkError`；另有 `hasSymbol(name)`、`getSymbolLookup()`、`getLinker()` |
| `PlatformSystems` | 平台检测：`isWindows()`、`isLinux()`、`isMacOs()`、`isUnixLike()`、`getOsName()`，底层委托 common-starter 的 `OsFamily` |

内置实现要点：

| SPI 名 | 实现 | 原生通道 | 日志源 | 回退 |
| --- | --- | --- | --- | --- |
| `windows` | `WindowsEventLogProvider` | FFM 绑定 Advapi32：`OpenEventLogW` / `ReadEventLogW` / `CloseEventLog` / `GetNumberOfEventLogRecords`（另绑 Kernel32 `GetLastError`） | `System`、`Application`、`Security` | 无（FFM 不可用时 `isPlatformSupported()` 为 `false`） |
| `linux` | `LinuxJournaldProvider` | FFM 绑定 libsystemd：`sd_journal_open` / `sd_journal_next` / `sd_journal_previous` / `sd_journal_get_data` / `sd_journal_close` / `sd_journal_seek_tail` / `sd_journal_seek_head` 等，读取 `MESSAGE`、`_SYSTEMD_UNIT`、`SYSLOG_IDENTIFIER`、`PRIORITY` 字段 | `journald`、`syslog`、`auth`、`kern`、`daemon`、`cron`、`user` | `/var/log/syslog`、`/var/log/messages`、`/var/log/auth.log`、`/var/log/kern.log`、`/var/log/daemon.log` |
| `macos` | `MacOSUnifiedLogProvider` | `ProcessBuilder` 执行 `/usr/bin/log show --style syslog --last 24h --color none`（带谓词时加 `--predicate`） | `unified`、`system`、`install`、`kernel` | `/var/log/system.log`、`/var/log/install.log` |

级别映射：Windows 事件类型 `1`→ERROR、`2`→WARNING、`16`→ERROR、其余→INFO；journald `PRIORITY` `0–2`→CRITICAL、`3`→ERROR、`4`→WARNING、`5–6`→INFO、`7`→DEBUG；Linux/macOS 文件回退按行内 `error/fail/warn/debug` 等关键词判级。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-syslog-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 检索系统日志

```java
import com.chua.filesystem.log.support.SystemLogService;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import java.util.List;

// 1) 最短写法：包含 "disk" 的 ERROR 及以上日志，最多 50 条
List<LogEntry> entries = SystemLogService.getInstance()
        .search("disk", LogLevel.ERROR, 50);

// 2) 高级查询：指定来源、模式、条数、排序
LogQuery query = LogQuery.builder()
        .source("System")           // Windows 源；Linux 可用 "journald" 等
        .pattern("*disk*")          // glob 通配符，大小写不敏感
        .minLevel(LogLevel.WARNING)
        .maxResults(100)
        .order(LogQuery.ORDER_DESC) // 最新在前
        .build();
List<LogEntry> results = SystemLogService.getInstance().search(query);

// 3) 当前平台可用的日志源
System.out.println(SystemLogService.getInstance().getSources());
```

### 3.3 实时监听新日志（替代 tail -f）

```java
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.polling.SyslogPolledDirectory;
import java.util.concurrent.TimeUnit;

SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
        .minLevel(LogLevel.ERROR)
        .pollIntervalSeconds(2)
        .build();

watcher.addListener(new PolledListener() {
    @Override
    public void onModify(WatcherEvent event, EventObserver observer) {
        LogEntry entry = (LogEntry) observer.getSource();
        System.out.println("[" + entry.level() + "] " + entry.source() + ": " + entry.message());
    }
});

watcher.start(new DirectoryPollerEnvironment(null, 2, TimeUnit.SECONDS));
// ...需要停止时
watcher.close();
```

### 3.4 通过 syslog: 协议统一查询

```java
import com.chua.common.support.file.resource.Resource;
import com.chua.filesystem.log.support.resolver.SystemLogResourceFinder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

Set<Resource> hits = new SystemLogResourceFinder().find("syslog:*error*&maxResults=10");
for (Resource r : hits) {
    System.out.println(r.getUrlPath() + " -> " + new String(r.content(), StandardCharsets.UTF_8));
}
```

---

## 4. 配置项

**无。** 依据：模块源码未定义任何配置类、配置文件或系统属性键；检索与监听的全部行为参数（来源、模式、级别、条数、游标、排序、轮询间隔）都通过 `LogQuery` 与 `SyslogPolledDirectory.Builder` 显式传入，默认值即源码常量（`maxResults = 100`、`order = "desc"`、`pollIntervalSeconds = 5`）。

运行期唯一环境要求：Windows / Linux 原生通道基于 Java FFM，JVM 需开启本地访问 `--enable-native-access=ALL-UNNAMED`（本模块 pom 已为 surefire 插件预配置该 `argLine`，当前无测试源码；应用运行时需自行加上）。原生库查找路径由 common-starter 的 `NativeUtils` 决定，见 [2.6 桥接层](#26-桥接层)。

---

## 5. SPI 扩展点

本模块对外唯一的 SPI 是 **`com.chua.filesystem.log.support.spi.SystemLogProvider`**：

| 方法 | 说明 |
| --- | --- |
| `List<LogEntry> search(LogQuery query)` | 按查询条件检索，结果按时间降序返回 |
| `List<String> getSources()` | 返回当前提供者可用的日志源列表 |
| `boolean isPlatformSupported()` | 当前运行平台是否适配本实现 |

发现机制：`SystemLogService` 与 `SystemLogResourceFinder` 都先用 `java.util.ServiceLoader` 加载 `SystemLogProvider`，取第一个 `isPlatformSupported() == true` 的实现；未命中再由 `SystemLogBridge` 按平台创建内置实现。因此**自定义实现推荐通过标准 ServiceLoader 登记**：

```text
# src/main/resources/META-INF/services/com.chua.filesystem.log.support.spi.SystemLogProvider
com.example.MySystemLogProvider
```

内置三个实现分别标注 `@Spi("windows")` / `@Spi("linux")` / `@Spi("macos")`（`@SpiDescribe` 描述为 `windows-event-log` / `linux-journald` / `macos-unified-log`，`type = "log"`），由 `SystemLogBridge` 在运行期按平台直接创建。若模块被嵌入 GraalVM native-image，`reflect-config.json` 已为两个 `ResourceFinder` 注册反射元数据。

---

## 6. 构建与依赖

- **坐标**：`com.chua:utils-support-syslog-starter:4.0.0.51`，父模块 `utils-support-filesystem-parent`，顶层仓库 JDK 为 `25`。
- **依赖**：`com.chua:utils-support-common-starter`（同版本，提供 SPI 注解、`DirectoryPollerEnvironment`、`ResourceFinder`、`NativeUtils`、`OsFamily`、`Wraps` 等）、`org.projectlombok:lombok`（`provided`）。
- **测试**：maven-surefire-plugin 配置 `argLine = --enable-native-access=ALL-UNNAMED`。
- **GraalVM**：`src/main/resources/META-INF/native-image/com.chua/utils-support-syslog-starter/` 下含 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。
- **平台要求**：Windows 需系统事件日志服务可用；Linux journald 通道需 `libsystemd`（缺失自动回退 `/var/log`）；macOS 需 `/usr/bin/log`（缺失自动回退 `/var/log`）。`file:` 协议的原生工具（`where` / `locate` / `mdfind`）缺失时回退 NIO 全盘遍历。

从源码构建（在本模块目录）：

```text
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| 通用文件读写、归档与压缩（zip / tar / csv / json / txt 等） | `utils-support-common-starter` 的 `FileSystem`、`IoUtils` |
| Windows NTFS MFT 直读等原生文件检索加速 | 不在本模块：pom 注明由文件检索 starter（`utils-support-filesearch-starter`）承载，本模块不依赖 native 包 |
| 应用日志门面与实现（SLF4J / Logback / Log4j） | 不在本模块；本模块只读取**操作系统级**日志，不接管应用日志输出 |
| 日志采集、转发、集中存储与检索（ELK、Kafka、数据库落库等） | 不在本模块 |
| Spring Boot 自动装配、`@ConfigurationProperties` 配置绑定 | 不在本模块；这是纯工具库，无 Spring 依赖 |
| 进程内日志轮转、审计、脱敏 | 不在本模块；`LogEntry.rawData` 透传能力保留，但内置实现未填充 |
