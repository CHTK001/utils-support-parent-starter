# utils-support-date-starter

> utils 体系的**日期时间自然语言解析模块**：基于 common-starter 的 `DateTimeParser` SPI，把「下周一下午三点开会」这类中文时间短语解析成 `java.time.LocalDateTime`，底层使用 xk-time 的 Time-NLP。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.date.support.parser`

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
    <artifactId>utils-support-date-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `DateTimeParser` SPI 的一个实现模块：中文自然语言时间 → `LocalDateTime` | 不是日期工具类集合（格式化、区间、时区等在 common-starter 的 `DateTime` / `DateUtils`） |
| 能力 | 识别一句话里的时间，例如「下周一下午三点开会」（xk-time Time-NLP） | 不负责格式化输出、时区转换、日期运算 |
| 形态 | 纯 SPI 提供方：一个实现类 + 编译期生成的 SPI 索引 | 不是 Spring Boot 自动装配模块：无 Bean、无配置属性、无自动配置类 |
| 依赖 | 运行期只依赖 common-starter 与 xk-time；ast-starter 仅编译期 | 不依赖 Spring、不依赖数据库/网络栈 |

**判断口径**：凡属「把时间字符串解析成 `LocalDateTime`」这一件事的中文自然语言实现，放本模块；日期的格式化、运算、区间、时区等通用能力留在 common-starter 的 `lang.date` 包。

### 1.3 模块构成

- 唯一源码文件：`src/main/java/com/chua/date/support/parser/TimeNlpDateTimeParser.java`（`@author CH`，`@since 4.0.0.42`）。
- 编译期由 `@AutoSpi` 注解处理器生成 `META-INF/extensions/com.chua.common.support.lang.date.DateTimeParser`，内容为实现类全限定名（见第 5 节）。
- 无 `src/main/resources`、无测试目录、无配置文件。

---

## 2. 核心 API 与速查表

### 2.1 本模块唯一公开类：`TimeNlpDateTimeParser`

实现 `com.chua.common.support.lang.date.DateTimeParser`，方法签名：

```java
public LocalDateTime parse(String timeStr)
```

行为约定（与源码逐条对应）：

| 输入 | 行为 |
| --- | --- |
| 空字符串 / `null` | 返回 `null` |
| 标准 ISO 格式（`LocalDateTime.parse` 能成功，如 `2024-01-01T12:00:00`） | 主动返回 `null` 让位给 common-starter 的 `StandardDateTimeParser`，保证精确格式优先、与 SPI 顺序无关 |
| 中文时间短语（如「下周一下午三点开会」） | `TimeNLPUtil.parse(timeStr)` 识别，取第一个非空 `TimeNLP.getLocalDateTime()` |
| 解析异常 / 无结果 | 返回 `null`（不抛异常），由 `DateTime.parse` 继续尝试下一个 SPI 实现 |

### 2.2 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 中文时间短语 → 时间 | `DateTime.parse("下周一下午三点开会")` | common-starter 门面，自动遍历全部 `DateTimeParser` SPI |
| ISO 标准格式解析 | `DateTime.parse("2024-01-01T12:00:00")` | 由 common-starter 内置 `StandardDateTimeParser`（`@Spi("standard")`）精确处理 |
| 指定格式解析 | `DateTime.parse("2024-01-01 12:00:00", "yyyy-MM-dd HH:mm:ss")` | 按 pattern 走 `DateTimeFormatter`，不走自然语言解析 |
| 毫秒时间戳 / `Date` 转换 | `DateTime.parse(epochMilli)` / `DateTime.parse(date)` | 不走自然语言解析 |
| 直接调用 Time-NLP 解析器 | `new TimeNlpDateTimeParser().parse("明天上午十点")` | 无法识别返回 `null` |
| 按名称取 SPI 实现 | `ServiceProvider.of(DateTimeParser.class).getExtension("time-nlp")` | SPI 名称来自类上的 `@Spi("time-nlp")` |
| 遍历全部日期解析器 | `ServiceProvider.of(DateTimeParser.class).collect()` | `DateTime.parse(String)` 内部即此逻辑 |
| 新增自定义解析器 | 实现 `DateTimeParser` 接口 | 见第 5.3 节 |

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-date-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.lang.date.DateTime;
import com.chua.date.support.parser.TimeNlpDateTimeParser;

import java.time.LocalDateTime;

public class DateDemo {
    public static void main(String[] args) {
        // 1) 统一门面：自动在全部 DateTimeParser SPI 实现中选择（本模块提供 time-nlp 实现）
        LocalDateTime meeting = DateTime.parse("下周一下午三点开会");
        System.out.println(meeting);   // 结果随运行时「当前时间」推算

        // 2) ISO 标准格式不受影响，仍由 common-starter 的 standard 实现精确解析
        LocalDateTime iso = DateTime.parse("2024-01-01T12:00:00");
        System.out.println(iso);       // 2024-01-01T12:00

        // 3) 也可以直接使用本模块的解析器；无法识别时返回 null
        LocalDateTime parsed = new TimeNlpDateTimeParser().parse("明天上午十点");
        System.out.println(parsed);
    }
}
```

> 自然语言解析的基准是「运行时刻的当前时间」：同一个短语在不同时间解析，结果不同。需要确定性输出时请使用 ISO 格式或指定 pattern 的重载。

---

## 4. 配置项

**无。** 依据：

- 本模块 `pom.xml` 未声明任何配置属性；
- 源码（仅 `TimeNlpDateTimeParser` 一个类）没有读取任何系统属性、环境变量或 `@ConfigurationProperties`；
- xk-time 的解析行为在 `TimeNLPUtil` 的默认配置下工作，本模块未提供任何覆盖入口；如需调整属于 xk-time 自身能力，本模块的透传配置项源码未提供。

---

## 5. SPI 扩展点

### 5.1 本模块注册的实现

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.common.support.lang.date.DateTimeParser`（契约定义在 `utils-support-common-starter`） |
| 实现类 | `com.chua.date.support.parser.TimeNlpDateTimeParser` |
| SPI 名称 | `time-nlp`（类上 `@Spi("time-nlp")` + `@AutoSpi(value = "com.chua.common.support.lang.date.DateTimeParser", name = "time-nlp")`） |
| 注册文件 | `META-INF/extensions/com.chua.common.support.lang.date.DateTimeParser`，由 `@AutoSpi` 在编译期生成；当前内容为一行实现类全限定名 |
| 装载方 | common-starter 的 `CustomServiceResolver`：读取 `META-INF/extensions/<接口全限定名>`，每行支持 `实现类全限定名` 或 `别名=实现类全限定名` 两种写法 |

### 5.2 调用链与协作约定

```text
DateTime.parse("下周一下午三点开会")
   └── ServiceProvider.of(DateTimeParser.class).collect()
         ├── StandardDateTimeParser   （common-starter 内置，@Spi("standard")，只吃 ISO 标准格式）
         └── TimeNlpDateTimeParser    （本模块，@Spi("time-nlp")，中文自然语言）
   → 第一个返回非 null 的实现胜出；全部失败再回退 LocalDateTime.parse(timeStr)
```

两条协作约定（`DateTimeParser` 接口 Javadoc 明确要求）：

1. **无法解析返回 `null` 而不是抛异常**，便于链上下一个实现继续尝试；
2. 本模块对「能被 `LocalDateTime.parse` 成功解析的 ISO 串」**主动让出**（返回 `null`），保证精确格式不被自然语言解析「抢答」，且与 SPI 实现的注册顺序无关。

### 5.3 如何扩展一个新的时间解析器

```java
import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.lang.date.DateTimeParser;
import com.chua.common.support.spi.annotations.Spi;

import java.time.LocalDateTime;

@Spi("my-parser")
@AutoSpi(value = "com.chua.common.support.lang.date.DateTimeParser", name = "my-parser")
public class MyDateTimeParser implements DateTimeParser {
    @Override
    public LocalDateTime parse(String timeStr) {
        // 能解析 → 返回 LocalDateTime；不能解析 → 返回 null（不要抛异常）
        return null;
    }
}
```

并登记 `META-INF/extensions/com.chua.common.support.lang.date.DateTimeParser`（引入 `utils-support-ast-starter` 作注解处理器时可由 `@AutoSpi` 编译期自动生成）：

```text
my-parser=com.example.MyDateTimeParser
```

注册后无需改任何调用方代码，`DateTime.parse(String)` 会自动把新实现纳入尝试链。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 pom 统一 `maven.compiler.source/target=25`）。
- 源码 UTF-8、无 BOM。
- Lombok 与 `utils-support-ast-starter`（`AutoSpiAstProcessor`）作为注解处理器参与编译——本模块 pom 的 `annotationProcessorPaths` 中显式声明了二者，以启用 `@AutoSpi` 编译期 SPI 索引生成。

```bash
mvn -pl utils-support-extra-parent/utils-support-date-starter -am clean package
```

### 6.2 依赖清单

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `DateTimeParser` SPI 契约、`DateTime` 门面、`StringUtils` |
| `com.github.xkzhangsan:xk-time`（版本由根 pom 统一托管为 `3.2.4`） | compile | Time-NLP 中文时间解析（`TimeNLP` / `TimeNLPUtil`） |
| `com.chua:utils-support-ast-starter` | provided | 仅编译期：`@AutoSpi` 注解与 SPI 索引生成 |
| `org.projectlombok:lombok` | provided（注解处理器） | 编译期支持 |

---

## 7. 边界：这些能力不在本模块

| 能力 | 在哪里 |
| --- | --- |
| `DateTime` 门面、格式化、区间、时区、`DateUnit`、`ZoneIdEnum` | `utils-support-common-starter`（`com.chua.common.support.lang.date`） |
| ISO 标准格式解析器 `StandardDateTimeParser` | `utils-support-common-starter` |
| `ServiceProvider` SPI 装配框架与 `@Spi` / `@Extension` 注解 | `utils-support-common-starter` |
| `@AutoSpi` 注解与编译期 SPI 索引生成器 `AutoSpiAstProcessor` | `utils-support-ast-starter` |
| Time-NLP 分词与解析规则本身 | 第三方库 `com.github.xkzhangsan:xk-time` |
| Spring Boot 自动装配 / application 配置属性 | 无——本模块虽名为 starter，实为纯 SPI 提供方，未提供任何自动配置类 |
| 定时任务、调度（cron / 固定频率） | `task` 体系（`TimeScheduler` 在 common-starter，Quartz 在 `utils-support-quartz-starter`），与本模块无关 |
