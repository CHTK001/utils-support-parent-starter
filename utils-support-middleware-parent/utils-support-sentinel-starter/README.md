# utils-support-sentinel-starter

> utils 体系并发/限流能力的 **Alibaba Sentinel 实现**：把 Sentinel 的 `SphU.entry` 资源探测接入 common-starter 的 `RateLimiterProvider` SPI，为上层提供按名称装配的限流入口。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.sentinel.support.rate`

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
    <artifactId>utils-support-sentinel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块描述（来自 `pom.xml`）：Alibaba Sentinel 限流实现。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | common-starter `RateLimiterProvider` SPI 的 Sentinel 实现（SPI 名 `sentinel`） | 不是限流规则管理器，模块内没有任何规则配置代码 |
| 能力 | `tryAcquire()` 立即尝试、`tryAcquire(timeout, unit)` 限时等待两种获取许可入口 | 不提供注解、切面、Web 拦截器等接入形态 |
| 集成 | 可被 `ServiceProvider` 按名称装配，也可被 `RateLimiterFlow` 按名称命中 | 不是 Spring Boot starter：无自动装配、无配置属性 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `sentinel-core` | 不依赖 Redis、数据库等外部中间件 |

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.sentinel.support.rate.SentinelRateLimiterProvider`（`@since 4.0.0.42`）。
- 资源文件 **2** 个：SPI 注册文件与 GraalVM native-image `reflect-config.json`。
- 无测试源码、无 Spring 自动装配文件。

---

## 2. 核心 API 与速查表

本模块只有一个实现类 `SentinelRateLimiterProvider`，实现 common-starter 的 `RateLimiterProvider` 接口，并以 `@Spi("sentinel")` 声明 SPI 名称。

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 按 SPI 名获取实现 | `ServiceProvider.of(RateLimiterProvider.class).getExtension("sentinel")` | SPI 实例走无参构造，默认名 `sentinel-limiter` |
| 指定资源名创建 | `new SentinelRateLimiterProvider("资源名")` | `name` 同时作为 Sentinel 资源名 |
| 默认实例 | `new SentinelRateLimiterProvider()` | 名称固定为 `sentinel-limiter` |
| 立即尝试获取许可 | `tryAcquire()` | 被 Sentinel 规则拒绝时返回 `false` |
| 限时等待许可 | `tryAcquire(500, TimeUnit.MILLISECONDS)` | 每 10ms 重试直到超时 |
| 查询名称 | `getName()` | 返回构造时传入的名称 |
| 查询可用许可数 | `availablePermits()` | 继承接口默认实现，恒返回 `-1`（不支持） |
| 门面式调用 | `RateLimiterFlow.of("sentinel-limiter", 100.0).tryAcquire()` | 仅当流名称命中本实现 `getName()` 时才走 Sentinel |

### 2.2 构造器

| 构造器 | 行为 |
| --- | --- |
| `SentinelRateLimiterProvider()` | 使用默认名称 `sentinel-limiter` |
| `SentinelRateLimiterProvider(String name)` | `name` 同时作为限流器名称与 Sentinel 资源名（`resourceName = name`） |

### 2.3 方法

| 方法 | 行为 |
| --- | --- |
| `boolean tryAcquire()` | 执行 `SphU.entry(resourceName)`（try-with-resources 自动释放 `Entry`）；成功返回 `true`，抛出 `BlockException`（被规则拒绝）返回 `false` |
| `boolean tryAcquire(long timeout, TimeUnit timeUnit)` | 以 `System.currentTimeMillis() + timeUnit.toMillis(timeout)` 为截止时间循环尝试；被拒绝时若已到截止时间返回 `false`，否则 `Thread.sleep(10)` 后重试；线程被中断时恢复中断标志并返回 `false` |
| `String getName()` | 返回构造时传入的名称 |
| `int availablePermits()` | 未覆写，沿用接口 default 实现，返回 `-1` 表示不支持查询 |

### 2.4 与 RateLimiterFlow 的关系

common-starter 的限流门面 `RateLimiterFlow` 创建提供者时，会遍历 `RateLimiterProvider` 的全部 SPI 实现，**按 `getName()` 与流名称相等**来选中实现；找不到才回退到内置的 Guava 实现。因此：

- `RateLimiterFlow.of("sentinel-limiter", 100.0)` 会命中 SPI 创建的本实现（无参构造的默认名就是 `sentinel-limiter`）；
- `of(...)` 的 `permitsPerSecond` 参数只作用于 Guava 回退实现，**Sentinel 路径的实际限流阈值由 Sentinel 规则决定**，本模块不读取该参数；
- 其它名称的流（例如 `of("api", 100.0)`）不会用到本实现，会回退 Guava。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sentinel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最小可运行示例

```java
import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.sentinel.support.rate.SentinelRateLimiterProvider;

import java.util.concurrent.TimeUnit;

public class SentinelRateDemo {

    public static void main(String[] args) {
        // 方式一：SPI 按名获取（SPI 名 sentinel，实例默认名 sentinel-limiter）
        RateLimiterProvider bySpi = ServiceProvider.of(RateLimiterProvider.class).getExtension("sentinel");

        // 方式二：直接创建，名称同时作为 Sentinel 资源名
        SentinelRateLimiterProvider limiter = new SentinelRateLimiterProvider("order-api");

        // 立即尝试获取许可
        if (limiter.tryAcquire()) {
            System.out.println("获得许可，执行业务");
        } else {
            System.out.println("被 Sentinel 规则拒绝");
        }

        // 限时等待许可（最多等 500ms）
        boolean ok = limiter.tryAcquire(500, TimeUnit.MILLISECONDS);
        System.out.println("限时获取结果: " + ok);

        System.out.println("限流器名称: " + limiter.getName());
    }
}
```

> 提示：本模块只负责把调用接入 Sentinel。**未注册任何 Sentinel 流控规则时，`SphU.entry` 默认放行所有请求**；要真正限流，需按 Sentinel 原生方式为资源名加载 `FlowRule` 等规则（见第 7 节边界）。

---

## 4. 配置项

**无。**

依据：模块源码与 `src/main/resources` 中没有 `@ConfigurationProperties`、`spring-configuration-metadata.json`、`spring.factories` 或 `AutoConfiguration.imports`；行为只由构造参数 `name` 决定，不读取任何配置键。Sentinel 的规则（`FlowRule` 等）也不由本模块配置，需使用方通过 Sentinel 原生 API 自行加载。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**，它是 common-starter `com.chua.common.support.concurrent.rate.RateLimiterProvider` SPI 的实现方。

注册文件 `src/main/resources/META-INF/extensions/com.chua.common.support.concurrent.rate.RateLimiterProvider`：

```text
sentinel=com.chua.sentinel.support.rate.SentinelRateLimiterProvider
```

- SPI 名称：`sentinel`（来自类上的 `@Spi("sentinel")`）。
- SPI 实例通过无参构造创建，名称固定为 `sentinel-limiter`；需要其它名称/资源名时直接 `new SentinelRateLimiterProvider(name)`。
- 已同步提供 GraalVM native-image 反射配置 `META-INF/native-image/com.chua/utils-support-sentinel-starter/reflect-config.json`，对该类开放全部构造器、方法与字段。
- 若要接入其它限流引擎（如 Resilience4j），请在各自模块实现 `RateLimiterProvider` 并按同仓 SPI 约定注册，不要在本模块内堆叠实现。

---

## 6. 构建与依赖

模块位于 `utils-support-parent-starter/utils-support-middleware-parent/utils-support-sentinel-starter`，父模块链：

```text
utils-support-parent-starter (4.0.0.51)
└── utils-support-middleware-parent
    └── utils-support-sentinel-starter
```

依赖（来自本模块 `pom.xml`）：

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | 提供 `RateLimiterProvider` SPI、`@Spi` 注解与 `ServiceProvider` 装配 |
| `com.alibaba.csp:sentinel-core` | Sentinel 核心引擎，版本由 `utils-support-middleware-parent` 统一管理为 `1.8.10` |

仓库内编译验证（与本仓其它模块一致）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| 限流规则（`FlowRule`/`DegradeRule` 等）的定义、加载、控制台、集群流控 | 本模块只做 `SphU.entry` 接入；规则请按 Sentinel 原生方式由使用方配置 |
| Guava 限流实现（SPI 名 `guava`）与 `RateLimiterFlow` 门面 | `utils-support-common-starter` |
| 熔断器、舱壁隔离、请求合并、重试 | `utils-support-common-starter` 的 `concurrent.circuitbreaker` / `bulkhead` / `collapse` 等，及 `utils-support-resilience4j-starter` |
| 注解式限流（如 `@SentinelResource` 切面）、Web 拦截器/Filter 适配 | 源码未提供，本模块未实现 |
| Spring Boot 自动装配与配置属性 | 源码未提供（无 `spring.factories` / `AutoConfiguration.imports` / 配置元数据） |
| 分布式/Redis 限流、限流指标上报 | 源码未提供 |
| 单元测试 | 源码未提供（模块无测试目录） |
