# utils-support-log-starter

> 日志文件（`.log`）文件系统操作模块：把日志文件作为一种可读写的「文件系统」接入 `FileSystem` SPI，提供 grep / 正则 / 反转 / tail / 时间范围过滤读取，追加与覆盖写入、时间戳前缀、延迟批量与立即落盘，并内置 mmap 分段双缓冲的异步高吞吐写入器。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，测试开启 `--enable-preview`）｜包名：`com.chua.log.support.file.impl`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 核心 API](#3-核心-api)
  - [3.1 LogFileSystem](#31-logfilesystem)
  - [3.2 LogReadBuilder（读取）](#32-logreadbuilder读取)
  - [3.3 LogWriteBuilder（写入）](#33-logwritebuilder写入)
  - [3.4 AsyncLogWriter（异步写入）](#34-asynclogwriter异步写入)
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
    <artifactId>utils-support-log-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块：`com.chua:utils-support-filesystem-parent:4.0.0.51`（同级还有 excel、pdf、word、json、filesearch 等文件系统模块）。模块描述（取自 pom）：**日志文件 (.log) 文件系统操作模块，支持 tail、grep、时间过滤、追加写入**。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `FileSystem` SPI 的 `log` 实现，专做 `.log` 日志文件的按行读与按行写 | 不是日志框架，不提供 Logger API、日志级别、格式化模板 |
| 读取 | grep 关键词 / 正则 / 反转（`grep -v`）、tail 末尾 N 行、时间范围过滤 | 不做日志解析成结构化对象、不做远程检索 |
| 写入 | 追加 / 覆盖、时间戳前缀、自定义前后缀与换行符、延迟批量（`write` + `finish`）与立即落盘（`writeAndFlush`） | 不做日志轮转、按天滚动、压缩归档、远程采集 |
| 异步 | `AsyncLogWriter`：mmap 分段双缓冲 + 后台虚拟线程批量刷盘，业务线程不阻塞 | 不是消息队列，不做多机汇聚 |
| 依赖 | 只依赖 `utils-support-common-starter` + lombok（provided） | 不依赖 Spring、不依赖数据库驱动、不依赖任何日志实现 |

### 1.3 规模

- Java 源文件 **4** 个，全部位于 `com.chua.log.support.file.impl`：
  `LogFileSystem`、`LogReadBuilder`、`LogWriteBuilder`、`AsyncLogWriter`。
- SPI 扩展声明 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.file.FileSystem`。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`（注册 `LogFileSystem`）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。
- 主要类型自 `4.0.0.42` 起提供（源码 `@since` 标注）。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿到日志文件系统 | `FileSystem.create("log")` | SPI 名 `log`；`.log` 文件也可 `FileSystem.auto(new File("app.log"))` 自动推断 |
| 按关键词过滤日志 | `LogReadBuilder.grep("ERROR")` | 行子串包含匹配 |
| 正则过滤 / 反转 | `grep(pattern, true)` / `invertMatch()` | 正则匹配；反转等价 `grep -v` |
| 只看末尾 N 行 | `tail(50)` | 类似 `tail -n`，在其它过滤之后截取 |
| 按时间范围筛日志 | `timeRange(from, to)` | 自动识别常见日志时间戳，也可 `withTimestampFormat(...)` 指定 |
| 追加写日志（带时间戳） | `write(file).append().withTimestamp(true).write(...).finish()` | `write` 入队、`finish` 统一落盘 |
| 立即落盘一条 | `writeAndFlush(line)` | 不入队，直接写文件 |
| 高吞吐异步写 | `new AsyncLogWriter.Builder(file).build()` 的 `writeLine(line)` | mmap 分段双缓冲，失败自动降级为队列批量追加 |

---

## 2. 快速开始

```java
import com.chua.common.support.file.FileSystem;
import com.chua.log.support.file.impl.LogReadBuilder;
import com.chua.log.support.file.impl.LogWriteBuilder;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

public class LogDemo {
    public static void main(String[] args) {
        FileSystem logFs = FileSystem.create("log");
        File logFile = new File("app.log");

        // 写入：追加模式 + 自动时间戳前缀，write 入队，finish 统一落盘
        ((LogWriteBuilder) logFs.write(logFile))
                .append()
                .withTimestamp(true)
                .write("系统启动成功")
                .write("用户登录: userId=1001")
                .finish();

        // 读取：grep 过滤 + 只看末尾 50 行
        List<String> errorLines = ((LogReadBuilder) logFs.read(logFile))
                .grep("ERROR")
                .tail(50)
                .lines();

        // 读取：按时间范围过滤（最近 1 天）
        List<String> todayLines = ((LogReadBuilder) logFs.read(logFile))
                .timeRange(LocalDateTime.now().minusDays(1), LocalDateTime.now())
                .lines();
    }
}
```

不关心 SPI 时也可以直接 `new LogReadBuilder(file)` / `new LogWriteBuilder(file)`；高吞吐场景改用 `AsyncLogWriter`：

```java
try (AsyncLogWriter writer = new AsyncLogWriter.Builder(new File("app.log"))
        .preallocateSize(64 * 1024 * 1024)
        .segmentCount(4)
        .flushIntervalMs(100)
        .build()) {
    writer.writeLine("业务线程立即返回");
    writer.writeLine("另一条日志");
} // close() 时停止后台线程并最终刷盘
```

---

## 3. 核心 API

### 3.1 LogFileSystem

`@Spi("log")`，实现 `com.chua.common.support.file.FileSystem`。

| 方法 | 说明 |
| --- | --- |
| `getType()` | 返回 `"log"` |
| `read(File file)` | 返回 `LogReadBuilder` |
| `write(File file)` | 返回 `LogWriteBuilder` |

获取方式：`FileSystem.create("log")`；或对 `.log` 扩展名文件用 `FileSystem.auto(file)`（扩展名推断的 `default` 分支按扩展名取 SPI 实现，同样命中本模块）。

### 3.2 LogReadBuilder（读取）

继承 `com.chua.common.support.file.builder.ReadBuilder`，链式条件可叠加（AND 逻辑）。

| 链式方法 | 说明 |
| --- | --- |
| `grep(String keyword)` | 关键词过滤（行子串包含匹配），清除已有正则 |
| `grep(String pattern, boolean regex)` | `regex=true` 按正则编译匹配，`false` 等价单参版本 |
| `invertMatch()` | 反转匹配（类似 `grep -v`），仅返回不匹配的行 |
| `tail(int lines)` | 只保留过滤后结果的末尾 N 行（负数按 0 处理，`<=0` 不截取） |
| `timeFrom(LocalDateTime from)` | 时间下限（含） |
| `timeTo(LocalDateTime to)` | 时间上限（含） |
| `timeRange(LocalDateTime from, LocalDateTime to)` | 闭区间时间范围 |
| `withTimestampFormat(String pattern)` | 自定义时间戳格式；不设则自动识别 |
| `withCharset(String charset)` | 指定字符集（覆盖父类实现，返回本类型）；默认随父类为 `Charset.defaultCharset()` |
| `withCallback(ReadCallback callback)` | 继承自父类；`lines()` 执行时逐行回调 `onBody`，结束后回调 `onComplete` |

| 读取方法 | 说明 |
| --- | --- |
| `lines()` | 读取全部行并依次应用 grep → 时间 → tail 过滤，返回 `List<String>`；文件不存在/不可读返回空列表 |
| `readLines(Consumer<String> lineHandler)` | 逐行流式回调，不缓存全部行；应用 grep 与时间过滤，**不应用 tail** |
| `streamLines()` | 覆盖父类，等价 `lines().stream()` |
| `asLines()` / `asString()` / `read()` | 覆盖父类：行列表 / 按系统换行符拼接的文本 / 返回行列表 |

时间戳识别：取行首最多 30 个字符（trim 后）解析；先试自定义格式，再依次尝试内置格式，均失败则该行在时间过滤中**默认保留**。内置格式（按优先级）：

```text
yyyy-MM-dd HH:mm:ss,SSS
yyyy-MM-dd HH:mm:ss.SSS
yyyy-MM-dd'T'HH:mm:ss.SSSXXX
yyyy-MM-dd HH:mm:ss
yyyy/MM/dd HH:mm:ss
yyyy-MM-dd
dd/MMM/yyyy:HH:mm:ss Z
ISO_LOCAL_DATE_TIME
```

### 3.3 LogWriteBuilder（写入）

继承 `com.chua.common.support.file.builder.WriteBuilder`，`write` 只入队，`finish()` 统一写盘。

| 链式方法 | 说明 |
| --- | --- |
| `append()` | 追加模式（**默认**），写到文件末尾 |
| `overwrite()` | 覆盖模式，从头写入 |
| `withTimestamp(boolean enabled)` | 是否自动添加 `[yyyy-MM-dd HH:mm:ss] ` 时间戳前缀（默认关闭） |
| `withTimestampFormat(String pattern)` | 自定义时间戳格式（默认 `yyyy-MM-dd HH:mm:ss`） |
| `withPrefix(String prefix)` | 行前缀（在时间戳之前） |
| `withSuffix(String suffix)` | 行后缀（追加在行尾） |
| `withLineSeparator(String lineSeparator)` | 自定义换行符（默认 `System.lineSeparator()`） |
| `withCharset(String charset)` | 指定字符集（覆盖父类实现，返回本类型）；默认随父类为 `Charset.defaultCharset()` |
| `withCallback(WriteCallback callback)` | 继承自父类；`finish()` 触发 `onStart`/`onBeginWrite`/`onProgress`/`onComplete` |

| 写入方法 | 说明 |
| --- | --- |
| `write(String line)` / `write(List<String> lines)` | 入队，等待 `finish()` |
| `write(Object data)` | 覆盖父类；`String` 原样、`List` 逐项展开、`Map` 与其它类型 `toString()` 成一行 |
| `writeAndFlush(String line)` / `writeAndFlush(List<String> lines)` | 不入队，立即写盘 |
| `finish()` | 将队列内容按行格式化后统一写入 |

行为细节：

- 行格式：`prefix + [时间戳] + 内容 + suffix`（仅 `withTimestamp(true)` 时有时间戳，格式为 `[yyyy-MM-dd HH:mm:ss] `）。
- 父目录不存在会自动创建（`mkdirs`）。
- 写入失败抛 `RuntimeException("日志写入失败: " + file)`，并通过 `WriteCallback.onComplete(false)` 通知。

### 3.4 AsyncLogWriter（异步写入）

`implements AutoCloseable`，`@since 4.0.0.42`。业务线程调用 `writeLine` 立即返回，后台虚拟线程（线程名 `async-log-<文件名>`）按 `flushIntervalMs` 周期刷盘。

双模式架构：

- **mmap 模式（默认）**：按线程 `threadId` 哈希路由到固定段（默认 4 段，自动取 2 的幂），每段 A/B 双缓冲（ping-pong）环形写入；单区容量 = `max(preallocateSize / (2 × segmentCount), 1MB)`；刷盘前等待在途写入归零，避免环形覆盖竞态；每线程缓存 `MappedByteBuffer.duplicate()` 视图。
- **降级模式**：文件映射创建失败（平台限制、空间不足等）时自动降级为 `LinkedBlockingQueue` + 后台虚拟线程按批（每批最多 1024 条）`FileChannel` 追加写入。

| Builder 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `charset(Charset)` | `StandardCharsets.UTF_8` | 字符集 |
| `lineSeparator(String)` | `System.lineSeparator()` | 换行符 |
| `preallocateSize(long)` | `64MB` | mmap 总预分配大小 |
| `segmentCount(int)` | `4` | 分段数，自动向上取整为 2 的幂 |
| `flushIntervalMs(long)` | `100` | 刷盘间隔（毫秒） |

| 方法 | 说明 |
| --- | --- |
| `writeLine(String line)` | 异步写一行；`null` 忽略；单行字节（含换行符）超过段容量抛 `RuntimeException("日志行超过 mmap 段容量: " + file)` |
| `close()` | 停止后台线程、最终刷盘、关闭通道并清理 ThreadLocal 视图 |

---

## 4. 配置项

**无。** 依据：本模块没有 `application*.yml` / `*.properties` 配置文件，也没有 `@ConfigurationProperties` 或 Spring 配置类（本模块不依赖 Spring）；全部行为参数都通过 Builder 链式方法传入，默认值见第 3 节各表。也没有需要登记的系统属性或环境变量。

---

## 5. SPI 扩展点

- **本模块提供（而非消费）的 SPI**：向 `com.chua.common.support.file.FileSystem` 注册名称 `log`，注册文件 `META-INF/extensions/com.chua.common.support.file.FileSystem` 内容为：

  ```text
  log=com.chua.log.support.file.impl.LogFileSystem
  ```

- **使用方式**：`FileSystem.create("log")`；`.log` 扩展名文件经 `FileSystem.auto(file)` 自动命中本实现。
- **本模块不定义新的 SPI 接口**；若要扩展日志能力（新过滤器、新写入目标），应在 `utils-support-common-starter` 的 `FileSystem` / `ReadBuilder` / `WriteBuilder` 契约上扩展实现，不要在本模块内另起旁路实现（同一能力两份实现会破坏 SPI 装配）。
- **GraalVM native-image**：`reflect-config.json` 对 `LogFileSystem` 开放全部构造器/方法/字段的反射；`resource-config.json` 包含 `META-INF/extensions/.*`，保证 SPI 注册文件在原生镜像中可见。

---

## 6. 构建与依赖

依赖（取自 `pom.xml`）：

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | 同版本（`${project.version}`），提供 `FileSystem` SPI、`ReadBuilder`/`WriteBuilder`、`Wraps`、`StringUtils` 等 |
| `org.projectlombok:lombok` | `provided`，继承自父模块，仅编译期（`@Slf4j`） |

构建命令（在本模块或 `utils-support-filesystem-parent` 目录执行）：

```bash
mvn -o -q compile -DskipTests
```

本模块为纯库模块，无 Spring Boot 自动装配、无启动器逻辑；引入即获得 `FileSystem` SPI 的 `log` 实现。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| Logger 门面、日志级别、Appender、布局等日志框架能力 | 用 SLF4J + Logback/Log4j 等直接接入，本模块只处理 `.log` 文件本身的读写 |
| 日志轮转、按天滚动、压缩归档、远程采集/检索 | 不在本模块；需要时在上层组合实现 |
| csv / json / xml / txt / zip / tar 等通用格式读写 | `utils-support-common-starter` 的 `FileSystem` 内置实现 |
| excel / pdf / word / yaml / 7z / zip4j 等格式 | 同层各 `utils-support-*-starter`（如 `utils-support-excel-starter`、`utils-support-pdf-starter`、`utils-support-word-starter`） |
| 对象存储、文件检索 | `filestorage` / `filesearch` 对应模块 |
