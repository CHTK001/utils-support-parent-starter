# utils-support-syslog-starter

系统日志模块，提供跨平台（Windows/Linux/macOS）系统日志检索与实时监听功能。
        主要功能：
        - 通配符搜索：支持 glob 模式匹配系统日志条目
        - 实时监听：PolledDirectory 轮询实时推送新增日志（替代 tail -f）
        - Windows：通过 Java 25 FFM API 直调 advapi32 Event Log API
        - Linux：通过 FFM 调用 libsystemd journald API，回退 /var/log 文件读取
        - macOS：通过 ProcessBuilder 调用 log show 命令，回退 /var/log 文件读取
        - ResourceProvider 集成：支持 syslog: 协议统一查询
        - SPI 可扩展：自定义 SystemLogProvider 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-syslog-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 实时监听系统日志（替代 tail -f）

```java
import com.chua.common.support.lang.directory.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.filesystem.log.support.polling.SyslogPolledDirectory;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;

// 监听所有级别、全部来源，默认 5 秒轮询间隔
SyslogPolledDirectory watcher = SyslogPolledDirectory.builder().build();

watcher.addListener(new PolledListener() {
    @Override
    public void onModify(WatcherEvent event, EventObserver observer) {
        LogEntry entry = (LogEntry) observer.getSource();
        System.out.println("[" + entry.level() + "] " + entry.source() + ": " + entry.message());
    }
});

watcher.start(DirectoryPollerEnvironment.defaults());

// ---------- 过滤错误级别，2 秒轮询 ----------
SyslogPolledDirectory errorWatcher = SyslogPolledDirectory.builder()
        .minLevel(LogLevel.ERROR)
        .pollIntervalSeconds(2)
        .build();

errorWatcher.addListener((event, observer) -> {
    LogEntry entry = (LogEntry) observer.getSource();
    System.out.println("[ERROR] " + entry.message());
});

errorWatcher.start(DirectoryPollerEnvironment.defaults());

// ---------- 指定日志源 + 消息模式 ----------
SyslogPolledDirectory sysWatcher = SyslogPolledDirectory.builder()
        .source("System")          // Windows Event Log "System" 源
        .pattern("*error*")        // 匹配含 "error" 的消息
        .minLevel(LogLevel.WARNING)
        .pollIntervalSeconds(3)
        .build();

sysWatcher.start(DirectoryPollerEnvironment.defaults());
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `FastFileSearchNativeBridge` | 快速文件搜索原生库桥接 - Java 25 FFM (Panama) 绑定 Windows NTFS MFT 直读搜索（需要管理员权限）。 |
| `NativeFunctionRegistry` | FFM 原生函数注册表 - 缓存 MethodHandle 避免重复 downcall 开销 基于 Java 25 FFM API，在运行时动态绑定系统库函数。 |
| `PlatformSystems` | 平台检测工具类 |
| `SystemLogBridge` | 系统日志桥接器 - 统一管理各平台 FFM FunctionRegistry 负责： 识别当前运行平台 初始化对应平台的 FFM 绑定 提供统一的原生函数注册表 |
| `SystemLogService` | 系统日志服务门面 - 统一入口，自动选型平台 Provider |
| `LogLevel` | 系统日志级别枚举 |
| `Builder` | 系统日志查询条件 日志源 (如 System, Application, Security, journald)，null 表示所有源 通配符搜索模式 (如 " |
| `SyslogPolledDirectory` | 系统日志实时轮询监听 - 实现 PolledDirectory 接口，定时拉取新增日志并推送事件 |
| `FileResourceFinder` | File system resource finder - registers "file:" protocol. Uses OS-native search  |
| `SystemLogResourceFinder` | System log ResourceFinder implementation - registers "syslog:" protocol Integrat |
| `LinuxJournaldProvider` | Linux 系统日志提供者 - libsystemd FFM + /var/log 文件回退 (SPI: `linux`) |
| `MacOSUnifiedLogProvider` | macOS 系统日志提供者 - log show 命令 + /var/log 文件回退 (SPI: `macos`) |
| ... | 共 14 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-syslog-starter
├── utils-support-common-starter
```
