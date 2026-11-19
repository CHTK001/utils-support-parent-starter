# utils-support-collapse-starter

> utils 体系的**请求折叠（请求合并）批量执行器**默认实现模块：将同一时刻内不同线程发起的相同或同组调用合并为一次批量调用执行，减少下游 I/O 次数、降低服务端线程占用。common 提供 SPI 接口，本模块提供基于虚拟线程的默认实现，引入依赖后经 SPI 自动发现接入。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`；虚拟线程需运行时 JDK 21+，低版本自动回退平台线程）｜包名：`com.chua.collapse.support`

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
    <artifactId>utils-support-collapse-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | 请求折叠（请求合并）批量执行器的**默认实现**：CAS 单收集者 + 无锁队列 + 补收窗口；收集调度与批量执行默认运行在虚拟线程（JDK 21+），平台线程守护线程兜底 | 不定义 SPI 接口与门面（接口、`CollapseConfig`、`CollapseFlow` 等在 `utils-support-common-starter`） |
| 接入 | 通过 SPI 自动发现：`@Spi("collapse")` + `META-INF/services` 注册，引入依赖即生效 | 不含 Spring 注解与自动装配（`@Collapsible` 在 spring/springboot-starter） |
| 范围 | 单个 JVM 内的并发调用折叠，支持「同参广播」与「合并拆分回填」两种模式 | 不做跨进程/分布式折叠，不做结果缓存与持久化 |
| 依赖 | 仅依赖 `utils-support-common-starter`，无 Spring、无第三方库 | 不依赖任何上层业务 starter |

---

## 2. 核心 API 与速查表

### 2.1 常用入口速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建折叠器（推荐） | `common..collapse.CollapseFlow.of / ofMapped` | 链式配置门面，经 SPI 自动接入本模块；无实现时自动降级为直接执行 |
| 直接创建执行器 | `collapse.support.DefaultCollapseExecutorFactory#create` | `@Spi("collapse")` 工厂；两个 `create` 重载对应两种模式 |
| 同参折叠（广播） | `CollapseBatchFunction` | 相同入参（equals）的并发调用合并执行一次，结果广播给组内全部调用方 |
| 合并拆分（回填） | `CollapseResultMapper` + `mergeAll(true)` | 整批合并执行一次，结果按调用者入参拆分回填，适合集合入参的批量查询 |
| 折叠参数 | `CollapseConfig` | `name` / `waitThreshold` / `collectingWaitTime` / `virtualThread` / `mergeAll` |
| 运行指标 | `CollapseExecutor#metrics()` | 调用次数、真实批量执行次数、平均/最大批次、合并率 |
| HTTP 请求折叠 | `HttpClientFactory.collapse(delegate[, config])` | GET 且 URL 相同的并发请求合并为一次真实调用，响应广播（实现在 common-starter `CollapseHttpClient`） |
| Spring 方法折叠 | `@Collapsible`（spring-starter） | springboot-starter 的 `CollapseAutoConfiguration` 自动装配拦截器，标注即可折叠 |

### 2.2 工作原理（默认实现）

`DefaultCollapseExecutor` 利用「任务提交—任务执行」之间的时间间隙进行批量收集，无需等待固定时间窗口：

- **CAS 单收集者**：`AtomicBoolean` 保证同一时刻仅有一个收集调度在进行，配合无锁队列（`ConcurrentLinkedQueue`）实现无锁并发入队与批量出队；
- **补收窗口**：单次收集未达到 `waitThreshold` 时，让出当前收集线程时间片（`collectingWaitTime == 0`）或等待指定毫秒（`collectingWaitTime > 0`）后再补收一次，兼顾实时性与批量化；
- **入参分组**：同一批内按入参 `equals` 分组，相同入参的多次调用合并执行一次批量逻辑，并将结果广播回组内全部调用方（`null` 入参单独成组）；`mergeAll = true` 时整批合并为一组；
- **虚拟线程承载**：JDK 21+ 默认收集调度线程（`{name}-collect`，单线程）与批量执行线程（`{name}-batch`）均运行于虚拟线程，阻塞式批量调用不占用平台线程；平台线程兜底模式下批量执行池最多 200 线程（`MAX_WORKER_THREADS`），同步移交 + 调用方执行策略。

### 2.3 运行指标

`DefaultCollapseExecutor#metrics()` 返回有序映射：

| 键 | 含义 |
| --- | --- |
| `executedCount` | 调用次数 |
| `batchExecutionCount` | 真实批量执行次数 |
| `avgBatchSize` | 平均批次大小 |
| `maxBatchSize` | 最大批次大小 |
| `mergeRate` | 合并率（`1 - 真实执行/调用`，越高折叠收益越大） |

### 2.4 生命周期与异常语义

- `execute(input)` 阻塞至本次批量执行完成；批量逻辑抛出的异常按组广播，并在调用方**解除包装后原样抛出**（`ExecutionException` 取 `cause`）；
- 拆分回填模式下：`CollapseResultMapper#map` 返回 `null` → 整批以 `IllegalStateException("折叠结果映射器返回空结果")` 失败；返回的映射缺少某个调用者入参 → 该调用者以 `IllegalStateException("折叠结果中缺少调用者对应结果")` 失败；
- `close()` 后再 `execute` 抛 `IllegalStateException("CollapseExecutor[名称] has been closed.")`；`close()` 时未处理的排队任务以 `RejectedExecutionException` 完成异常，随后关闭调度与执行线程池。

---

## 3. 快速开始

### 3.1 门面方式（推荐）：CollapseFlow

无需 Spring，`CollapseFlow`（common-starter）经 SPI 自动发现本模块的工厂并创建执行器：

```java
import com.chua.common.support.concurrent.collapse.CollapseFlow;

// 同参折叠：相同入参的并发调用合并执行一次，结果广播
CollapseFlow<String, String> detail = CollapseFlow.of("user-detail",
        keys -> userService.get(keys.iterator().next()));  // 批量函数：入参为合并后的 key 集合
detail.threshold(10).collectingWaitTime(2);
String user = detail.execute("u-1001");                    // 并发调用自动折叠

// 合并拆分：多个调用者各带小集合，合并为一次批量查询后按调用者拆回子结果
CollapseFlow<List<Long>, Map<Long, User>> batch = CollapseFlow.ofMapped("user-batch",
        callers -> userMapper.selectByIdsAndSplit(callers)); // 返回 Map<调用者入参, 该调用者的子结果>
batch.threshold(20).collectingWaitTime(2);
Map<Long, User> mine = batch.execute(List.of(1L, 2L));

detail.close();
batch.close();
```

> `execute` 声明 `throws Throwable`，批量逻辑的异常原样透传。未引入本模块时，`CollapseFlow` 自动降级为直接执行（语义不变、无折叠收益）。

### 3.2 直接使用 SPI 工厂

```java
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.spi.ServiceProvider;

CollapseExecutorFactory factory =
        ServiceProvider.of(CollapseExecutorFactory.class).getExtension("collapse");

CollapseConfig config = new CollapseConfig();
config.setWaitThreshold(10);

CollapseExecutor<String, String> executor = factory.create(config,
        keys -> userService.get(keys.iterator().next()));
String user = executor.execute("u-1001");
executor.close();
```

---

## 4. 配置项

### 4.1 CollapseConfig

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `name` | `String` | `"collapse"` | 执行器名称/分组标识，同一名称的执行器共享同一收集器 |
| `waitThreshold` | `int` | `10` | 批量收集的最小阈值，收集到该数量的调用后立即执行批量逻辑；`0` 表示不等待（每批立即执行）；负值非法（`setWaitThreshold` 抛 `IllegalArgumentException`） |
| `collectingWaitTime` | `long` | `0` | 未达到阈值时的补收等待策略（毫秒）：小于 0 立即执行；等于 0 让出收集线程时间片后补收一次；大于 0 等待指定毫秒后补收一次 |
| `virtualThread` | `boolean` | `true` | 是否启用虚拟线程（JDK 21+）；启用后收集调度与批量执行默认运行在虚拟线程上 |
| `mergeAll` | `boolean` | `false` | 是否整批合并执行（不做入参分组）：`true` 时同一批次内全部调用合并为一组，执行一次批量逻辑，配合 `CollapseResultMapper` 按调用者拆分回填；`false` 按入参 `equals` 分组广播 |

### 4.2 本模块自身的配置

**无。** 本模块为零配置模块：源码仅包含 2 个类（`DefaultCollapseExecutor`、`DefaultCollapseExecutorFactory`）与 1 个 SPI 注册文件，无 Spring 自动装配类、无 `application.properties`/`application.yml` 配置键。引入依赖后经 SPI 自动发现生效；所有行为参数均通过上表 `CollapseConfig` 在代码中设置。

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI

| 接口 | SPI 名称 | 本模块实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.concurrent.collapse.CollapseExecutorFactory` | `collapse` | `com.chua.collapse.support.DefaultCollapseExecutorFactory` | `META-INF/services/com.chua.common.support.concurrent.collapse.CollapseExecutorFactory` |

`DefaultCollapseExecutorFactory` 覆写了工厂的两个 `create` 方法：广播模式（`CollapseBatchFunction`）与拆分回填模式（`CollapseResultMapper`；接口默认实现抛 `UnsupportedOperationException`，本模块已支持）。

### 5.2 使用方如何获取

```java
CollapseExecutorFactory factory =
        ServiceProvider.of(CollapseExecutorFactory.class).getExtension("collapse");
```

`CollapseFlow` 门面内部即按名称 `collapse` 做 SPI 探测；`HttpClientFactory.collapse(...)` 与 `@Collapsible` 亦经由同一 SPI 获得折叠能力。

### 5.3 如何替换 / 自定义实现

- 实现 `CollapseExecutorFactory`，标注 `@Spi("collapse")`（或登记到同名 `META-INF/services` 文件），即可替换默认实现；实现类需提供无参构造器；
- 也可通过 `CollapseFlow#executorFactory(CollapseExecutorFactory)` 回调注入自定义工厂：设置后**优先于 SPI 探测**，且必须在本门面首次 `execute` 之前设置（之后设置抛 `IllegalStateException`）。

---

## 6. 构建与依赖

### 6.1 依赖

```
utils-support-collapse-starter
└── utils-support-common-starter    （SPI 契约、ServiceProvider、集合工具）
```

仅此一个直接依赖，无 Spring、无第三方库。

### 6.2 构建命令与 JDK

```bash
# 在 utils-support-parent-starter 仓库内
mvn -o -q compile -DskipTests
```

- 编译版本：`maven.compiler.source/target = 25`（仓库根 `pom.xml` 的 `java.version=25`）；
- 运行时：虚拟线程需 JDK 21+；实现内部以 `Thread.ofVirtual()` 探测可用性，低版本自动回退平台线程（守护线程），功能语义不变。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| SPI 接口与配置（`CollapseExecutor`、`CollapseExecutorFactory`、`CollapseConfig`、`CollapseBatchFunction`、`CollapseResultMapper`） | `utils-support-common-starter`（`com.chua.common.support.concurrent.collapse`） |
| 链式门面 `CollapseFlow`（含无实现时的降级直调） | `utils-support-common-starter` |
| HTTP 请求折叠 `CollapseHttpClient` 与 `HttpClientFactory.collapse(...)` | `utils-support-common-starter`（`network.client`） |
| Spring 注解 `@Collapsible`、`CollapsibleIntercept`、`CollapsibleAdvisor` | `utils-support-spring-starter` |
| Spring Boot 自动装配 `CollapseAutoConfiguration` | `utils-support-springboot-starter` |
| 定时任务、数据同步、数据检索等 | 同父工程 `utils-support-quartz-starter`、`utils-support-datasync-starter`、`utils-support-datasearch-starter` 等 |

另需注意：本模块的折叠是**单 JVM 内**的并发合并，不提供跨进程/分布式协调，也不做结果缓存；批量函数应为只读/幂等逻辑——折叠会改变方法实际执行次数，非幂等逻辑不适用。
