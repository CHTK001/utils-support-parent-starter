# utils-support-quartz-starter

> utils 体系**任务调度家族的 Quartz 实现**：把统一调度契约 `SchedulerProvider` 落到 Quartz 引擎上，支持 Cron 表达式与固定频率两种触发模型，内置实例隔离、错过触发补偿与 Cron 星期字段换算，并可接入外部托管（容器/集群）调度器。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.quartz.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API](#2-核心-api)
- [3. 快速开始](#3-快速开始)
- [4. 行为细节](#4-行为细节)
- [5. 配置项](#5-配置项)
- [6. SPI 与扩展点](#6-spi-与扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-quartz-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 契约 | `com.chua.common.support.task.scheduler.SchedulerProvider` 的 Quartz 实现，SPI 名 `quartz` | 不定义新契约；接口与门面 `TimeScheduler` 在 `utils-support-common-starter` |
| 触发模型 | `CronTrigger`（6 字段 Cron：秒 分 时 日 月 周）与 `FixedTrigger`（固定频率，可带初始延迟） | 不支持其它 `Trigger` 实现，传入会抛 `IllegalArgumentException` |
| 形态 | 纯 Java 工具模块：可直接 `new`，也可走 SPI 按名装配 | 不是 Spring Boot starter：无自动装配、无配置文件绑定 |
| 存储 | 默认内存 JobStore；可用 `Properties` 构造器传入 Quartz 原生配置（如 JDBC JobStore、集群） | 不内置持久化/集群的默认配置 |
| 隔离 | 每个提供者独占一个 Quartz 作业分组与一份任务解析表，同一 JVM 多提供者互不覆盖 | — |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建默认调度器 | `new QuartzSchedulerProvider()` | 单线程、守护线程、内存 JobStore、独占实例名 |
| 按 SPI 名获取 | `ServiceProvider.of(SchedulerProvider.class).getExtension("quartz")` | 与 JDK 实现 `JdkSchedulerProvider` 可共存 |
| Cron 调度 | `schedule("nightly", task, new CronTrigger("0 0 2 * * ?"))` | 返回 `ScheduledTask` |
| 固定频率调度 | `schedule("heartbeat", task, new FixedTrigger(5, TimeUnit.SECONDS))` | 另有 `(initialDelay, interval, unit)` 重载 |
| 变更触发策略 | `reschedule(id, newTrigger)` | 任务不存在返回 `null`；底层作业丢失时按登记表重建 |
| 取消 / 查状态 | `cancel(id)` / `isRunning(id)` | 取消成功返回 `true` |
| 查看全部任务 | `getScheduledTasks()` | `List<ScheduledTask>` 快照 |
| 接入托管调度器 | `new QuartzSchedulerProvider(scheduler)` | `shutdown()` 不关闭传入的调度器 |
| 自定义 Quartz 配置 | `new QuartzSchedulerProvider(properties)` | 线程数、JDBC JobStore、集群等 |
| 优雅关闭 | `shutdown()` | 自建调度器最多等待在跑任务 5 秒 |

---

## 2. 核心 API

### 2.1 模块内的类型

本模块源码只有一个 Java 文件 `com.chua.quartz.support.QuartzSchedulerProvider`（`@since 4.0.0.42`），含两个嵌套类型：

| 类型 | 说明 |
| --- | --- |
| `QuartzSchedulerProvider` | 调度器提供者，类上标注 `@Spi("quartz")`，继承 `AbstractSchedulerProvider` |
| `QuartzSchedulerProvider.TaskResolver` | 委托作业回取任务体的函数接口：`Runnable resolve(String taskId)`，未登记返回 `null` |
| `QuartzSchedulerProvider.DelegateJob` | Quartz `Job` 委托实现：不持有任务体，按作业分组回到所属提供者的任务解析表取任务体执行；解析表缺失任务时抛 `JobExecutionException`，不静默空跑 |

### 2.2 构造器

| 构造器 | 行为 |
| --- | --- |
| `QuartzSchedulerProvider()` | 独占默认配置：实例名 `chua-quartz-<递增序号>`、单线程、守护线程、内存 JobStore；不读取类路径下的 `quartz.properties`，也不会占用 `StdSchedulerFactory.getDefaultScheduler()` 这一 JVM 单例 |
| `QuartzSchedulerProvider(Properties config)` | 按指定 Quartz 配置创建调度器；需要自定义线程数、JDBC JobStore 或集群时使用 |
| `QuartzSchedulerProvider(Scheduler scheduler)` | 使用外部托管（容器/集群）调度器；关闭本提供者只回收自己名下的作业，不关闭传入的调度器 |

### 2.3 公共方法（继承自 `SchedulerProvider` / `AbstractSchedulerProvider`）

| 方法 | 说明 |
| --- | --- |
| `schedule(Runnable task, Trigger trigger)` | 自动生成 UUID 标识并调度 |
| `schedule(String id, Runnable task, Trigger trigger)` | 指定标识登记任务并交给 Quartz 调度，返回 `ScheduledTask` |
| `reschedule(String id, Trigger trigger)` | 更新触发器并重新调度；任务不存在返回 `null`；底层作业已丢失时按登记表重建 |
| `cancel(String id)` | 取消并移除任务登记与 Quartz 作业；任务不存在返回 `false` |
| `isRunning(String id)` | 任务已登记且未取消返回 `true` |
| `isRunning()` | 调度器整体是否运行中 |
| `getScheduledTasks()` | 全部已登记任务的快照列表 |
| `shutdown()` | 置位运行状态、取消并清空任务表后关闭底层调度器 |

### 2.4 触发器与任务对象（来自 `utils-support-common-starter`）

| 类型 | 关键成员 | 说明 |
| --- | --- | --- |
| `CronTrigger` | `CronTrigger(String cron)`、`getCron()` | 标准 6 字段 Cron：`秒 分 时 日 月 周` |
| `FixedTrigger` | `FixedTrigger(long interval, TimeUnit)`、`FixedTrigger(long initialDelay, long interval, TimeUnit)`、`getInitialDelay()` / `getInterval()` / `getTimeUnit()` | 固定间隔，按 `startTime + N * interval + initialDelay` 计算触发点 |
| `Trigger` | `nextExecutionTime()`、`nextExecutionTime(LocalDateTime)`、`getFireTimes(int)`、`getFireTimes(int, LocalDateTime)` | 触发时间计算契约 |
| `ScheduledTask` | `getId()`、`getTask()`、`getTrigger()`、`isCancelled()`、`cancel()`、`updateTrigger(Trigger)` | 已调度任务句柄 |

---

## 3. 快速开始

### 3.1 最短示例

```java
import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.FixedTrigger;
import com.chua.common.support.task.scheduler.ScheduledTask;
import com.chua.common.support.task.scheduler.SchedulerProvider;
import com.chua.quartz.support.QuartzSchedulerProvider;

import java.util.concurrent.TimeUnit;

public class QuartzDemo {
    public static void main(String[] args) {
        SchedulerProvider scheduler = new QuartzSchedulerProvider();

        // 每 5 秒一次
        ScheduledTask heartbeat = scheduler.schedule(
                "heartbeat", () -> System.out.println("心跳"), new FixedTrigger(5, TimeUnit.SECONDS));

        // 每天 02:00:00
        ScheduledTask nightly = scheduler.schedule(
                "nightly", () -> System.out.println("日结"), new CronTrigger("0 0 2 * * ?"));

        System.out.println(scheduler.isRunning("heartbeat"));      // true
        System.out.println(scheduler.getScheduledTasks().size());  // 2

        scheduler.cancel("heartbeat");                                    // 取消
        scheduler.reschedule("nightly", new CronTrigger("0 0 3 * * ?"));  // 改到 03:00

        scheduler.shutdown();   // 优雅关闭
    }
}
```

### 3.2 通过门面 `TimeScheduler` 使用

```java
import com.chua.common.support.task.scheduler.TimeScheduler;
import com.chua.quartz.support.QuartzSchedulerProvider;

import java.util.concurrent.TimeUnit;

TimeScheduler scheduler = new TimeScheduler(new QuartzSchedulerProvider());
scheduler.scheduleCron("daily-task", () -> System.out.println("执行"), "0 0 12 * * ?");
scheduler.scheduleFixedRate("poll", () -> System.out.println("轮询"), 10, TimeUnit.SECONDS);
scheduler.shutdown();
```

> `TimeScheduler.of()` 默认使用 JDK 实现 `JdkSchedulerProvider`；要换成 Quartz，需显式传入本模块的提供者。

### 3.3 通过 SPI 按名获取

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.scheduler.SchedulerProvider;

SchedulerProvider scheduler = ServiceProvider.of(SchedulerProvider.class).getExtension("quartz");
```

### 3.4 接入容器托管 / 集群调度器

```java
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import com.chua.quartz.support.QuartzSchedulerProvider;

Scheduler quartz = StdSchedulerFactory.getDefaultScheduler();   // 或自行构建的集群调度器
QuartzSchedulerProvider provider = new QuartzSchedulerProvider(quartz);
// ... schedule / reschedule / cancel
provider.shutdown();   // 只回收本提供者名下作业，不关闭 quartz
```

---

## 4. 行为细节

### 4.1 实例隔离

- 每个提供者实例独占一个 Quartz 作业分组（`com-chua-quartz-<递增序号>`）和一份任务解析表（以 `com.chua.quartz.resolver.<分组>` 为键放入调度器上下文）。
- 同一 JVM 内的多个提供者互不覆盖；`DelegateJob` 通过作业分组回到所属提供者的登记表取任务体。
- 无参构造创建的是本提供者独占的调度器（单线程、守护线程），不占用 `StdSchedulerFactory.getDefaultScheduler()` JVM 单例，避免"一个提供者关闭、其它提供者任务全部停摆"。

### 4.2 错过触发补偿（misfire）

| 触发器 | 策略 | Quartz 指令 |
| --- | --- | --- |
| `CronTrigger` | 错过即等下一次 | `withMisfireHandlingInstructionDoNothing()` |
| `FixedTrigger` | 跳过错过的触发点、保持原节奏（不补跑） | `startAt(nextExecutionTime())` + `withMisfireHandlingInstructionNextWithRemainingCount()` |

> 与 `FixedTrigger#nextExecutionTime` "只算当前之后的下一个触发点、不补跑"的语义一致；Quartz 默认的 SMART_POLICY 会在调度器停顿后集中补跑，造成任务风暴，本模块刻意避开。

### 4.3 Cron 星期字段换算

框架与 Quartz 的星期编号不同：框架按 `0/7=周日、1=周一…6=周六`，Quartz 按 `1=周日、2=周一…7=周六`。本模块在提交前自动换算：

- 只换算星期字段的**数字写法**（含 `a`、`a-b`、`a/n`、`a-b/n`、`*`，以及 `0-7`、`1/2` 这类回绕/步进写法，逐日枚举后换算，不会漏日）；`SUN-SAT` 等名称写法两套解析器口径一致，保持原样。
- 保留 `L`、`#` 修饰。
- 日与周**同时限定**时抛 `IllegalArgumentException`（Quartz 无法表达框架的并集语义），需把其中一侧写成 `*` 或 `?`。
- Cron 字段数不是 6 时原样透传，交由 Quartz 报错。

### 4.4 关闭语义

| 调度器来源 | `shutdown()` 行为 |
| --- | --- |
| 自建（无参 / `Properties` 构造） | 先 `shutdown(true)` 等待在跑任务，最多等 5 秒；超时改用 `shutdown(false)` 不再等待 |
| 外部托管（`Scheduler` 构造） | 只删除本提供者作业分组下的作业，并从调度器上下文移除本提供者的任务解析表键；不关闭传入的调度器 |

### 4.5 失败语义

- 调度器已关闭后再调度：抛 `IllegalStateException`（"石英石 调度器已关闭，无法调度任务"）。
- 底层 `SchedulerException`：包装为 `RuntimeException` 抛出（"调度任务失败 / 重新调度任务失败 / 取消任务失败 / 关闭 石英石 调度器失败"）。
- `DelegateJob` 执行时若任务解析表缺失该任务：抛 `JobExecutionException`，不静默空跑。

---

## 5. 配置项

本模块**零配置**：没有 Spring 自动装配，也不读取任何配置文件（无 `application*.yml`、无 `quartz.properties` 绑定）。唯一可配置点是 `QuartzSchedulerProvider(Properties)` 传入的 Quartz 原生 `Properties`。

源码中引用的 Quartz 配置键及无参构造默认值：

| 键 | 说明 | 无参构造默认值 |
| --- | --- | --- |
| `org.quartz.scheduler.instanceName` | 调度器实例名 | `chua-quartz-<递增序号>`（每个提供者独占） |
| `org.quartz.threadPool.threadCount` | 工作线程数 | `1` |
| `org.quartz.threadPool.makeThreadsDaemons` | 工作线程是否为守护线程 | `true` |

其余 Quartz 原生配置（JDBC JobStore、集群、插件等）按 Quartz 官方 `Properties` 语义透传，本模块不额外封装、不改变其含义。

---

## 6. SPI 与扩展点

- 本模块是 SPI 的**实现方**：实现 `com.chua.common.support.task.scheduler.SchedulerProvider`，SPI 名 `quartz`（类上 `@Spi("quartz")`）。
- 登记文件：`src/main/resources/META-INF/services/com.chua.common.support.task.scheduler.SchedulerProvider`，内容为 `com.chua.quartz.support.QuartzSchedulerProvider`。
- 获取方式：`ServiceProvider.of(SchedulerProvider.class).getExtension("quartz")`，或直接 `new QuartzSchedulerProvider(...)`。
- 本模块**不定义新的 SPI 扩展点**。要替换/新增调度实现，请在 `utils-support-common-starter` 的契约上扩展：实现 `SchedulerProvider` 或继承 `AbstractSchedulerProvider`（该模块内置 JDK 实现 `JdkSchedulerProvider`）。
- GraalVM native-image 配置已随模块提供：`reflect-config.json`（注册 `QuartzSchedulerProvider` 全量反射）、`proxy-config.json`（`SchedulerProvider` 代理）、`resource-config.json`（包含 `META-INF/services/.*`）。

---

## 7. 构建与依赖

```text
utils-support-quartz-starter
├── com.chua:utils-support-common-starter        # 调度契约、触发器模型、SPI 框架
└── org.quartz-scheduler:quartz:2.5.2            # 调度引擎
```

- 父模块：`utils-support-datatask-parent`（其上为 `utils-support-parent-starter`，版本 `4.0.0.51`）。
- JDK：`25`（根 POM `java.version=25`、`maven.compiler.release=25`）。
- 同级模块：`utils-support-retry-starter`、`utils-support-collapse-starter`、`utils-support-datasync-starter`、`utils-support-datasearch-starter`、`utils-support-datarecovery-starter`、`utils-support-datasync-agent-starter`、`utils-support-ueba-starter`（同属 `utils-support-datatask-parent`）。
- 编译验证（仓库根目录执行）：`mvn -o -q compile -DskipTests`，以 `EXIT=0` 为通过。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| 调度契约 `SchedulerProvider`、门面 `TimeScheduler`、`CronTrigger` / `FixedTrigger` / `ScheduledTask`、Cron 解析器 | `utils-support-common-starter`（`com.chua.common.support.task.scheduler`） |
| JDK 默认调度实现 | `JdkSchedulerProvider`（`utils-support-common-starter`） |
| 声明式注解 `@Scheduler`（`cron` / `fixedRate` / `fixedDelay`） | 注解定义在 `utils-support-common-starter`；本模块不提供注解扫描与处理器（源码未提供） |
| 分布式任务分发、状态机、持久化恢复 | `taskdistribution` 体系（`utils-support-common-starter` 及相应 starter），与本模块无关 |
| 固定延迟（fixedDelay，"上次跑完再等 N"）触发模型 | 本模块把 `FixedTrigger` 适配为固定频率语义（Quartz `SimpleScheduleBuilder` + `startAt`），无 fixedDelay 语义 |
| Quartz JDBC JobStore / 集群的默认配置 | 源码未提供；只能通过 `Properties` 构造器按 Quartz 官方方式自行配置 |
| Spring 自动装配、配置文件绑定 | 源码未提供；本模块不依赖 Spring |
