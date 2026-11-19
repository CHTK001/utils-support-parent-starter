# utils-support-resilience4j-starter

> utils 体系熔断 SPI `CircuitBreakerProvider` 的 **Resilience4j 适配实现**：把 `io.github.resilience4j.circuitbreaker.CircuitBreaker` 适配成 common-starter 定义的熔断器契约（`tryAcquire` / `recordSuccess` / `recordFailure` / `reset` / `isOpen` / `getName`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `java.version=25`、`maven.compiler.release=25`）｜包名：`com.chua.resilience4j.support.circuitbreaker`（唯一包，仅 1 个源文件）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 构造参数与熔断行为](#4-构造参数与熔断行为)
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
    <artifactId>utils-support-resilience4j-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | `CircuitBreakerProvider`（common-starter 熔断 SPI）的 Resilience4j 适配实现 | 不定义新的 SPI 接口；接口与门面都在 `utils-support-common-starter` |
| 实现 | `Resilience4jCircuitBreakerProvider`：包装 resilience4j 的 `CircuitBreaker` / `CircuitBreakerConfig` / `CircuitBreakerRegistry`，标准三态 CLOSED → OPEN → HALF_OPEN | 不带指标上报、事件监听、注解 AOP、控制台 |
| SPI | `@Spi(value = "default", order = 100)`，`@ConditionalOnClass("io.github.resilience4j.circuitbreaker.CircuitBreaker")` | —— |
| 范围 | 仅熔断器。模块 pom 描述为「Resilience4j 熔断/限流/重试实现」，当前源码仅提供熔断器实现，限流/重试源码未提供 | 不含限流、重试、舱壁隔离、时间限制器 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `resilience4j-circuitbreaker` | 不依赖 Spring、数据库驱动、网络栈 |

### 1.3 规模与资源

- Java 源文件 **1** 个：`com.chua.resilience4j.support.circuitbreaker.Resilience4jCircuitBreakerProvider`（`@author CH`，`@since 4.0.0.42`）。
- SPI 扩展声明 **1** 个：`META-INF/extensions/com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider`。
- GraalVM native-image 反射配置 **1** 个：`META-INF/native-image/com.chua/utils-support-resilience4j-starter/reflect-config.json`。
- 无测试代码、无 Spring 自动配置、无配置元数据（源码未提供）。

---

## 2. 核心 API 与速查表

本模块实现 `CircuitBreakerProvider` 的 6 个方法，另有一个 4 参公共构造器。别人最常查的入口如下：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建熔断器 | `new Resilience4jCircuitBreakerProvider(name, failureThreshold, successThreshold, waitDuration)` | 唯一公共构造器；内部经 `CircuitBreakerConfig` 构建配置，再由 `CircuitBreakerRegistry.of(config).circuitBreaker(name)` 创建实例 |
| SPI 名称 | `"default"`（`order = 100`，优先级高于同名的内存实现） | 注册文件内容与 `@Spi` 注解一致 |
| 尝试放行 | `tryAcquire()` | 委托 `circuitBreaker.tryAcquirePermission()`；放行时用 `AtomicLong` 记录调用开始时间（纳秒） |
| 记录成功 | `recordSuccess()` | 委托 `circuitBreaker.onSuccess(耗时, TimeUnit.NANOSECONDS)` |
| 记录失败 | `recordFailure()` | 委托 `circuitBreaker.onError(耗时, TimeUnit.NANOSECONDS, new RuntimeException("circuit breaker failure"))` |
| 是否熔断中 | `isOpen()` | `circuitBreaker.getState() == CircuitBreaker.State.OPEN` |
| 重置 | `reset()` | 委托 `circuitBreaker.reset()`，回到初始关闭状态 |
| 熔断器名称 | `getName()` | 返回 `circuitBreaker.getName()`，即构造时传入的 `name` |

---

## 3. 快速开始

最短可运行方式是**直接构造**（原因见本节末尾的 SPI 边界说明）：

```java
import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.resilience4j.support.circuitbreaker.Resilience4jCircuitBreakerProvider;

// 失败率阈值入参 50（实际映射见第 4 节）、滑动窗口 100 次、
// 半开状态放行 3 次、熔断打开保持 30 秒
CircuitBreakerProvider breaker = new Resilience4jCircuitBreakerProvider("order-service", 50, 3, 30_000L);

String result;
if (breaker.tryAcquire()) {          // 熔断打开时返回 false
    try {
        result = callRemote();       // 业务调用
        breaker.recordSuccess();     // 与 tryAcquire 成对调用
    } catch (Exception e) {
        breaker.recordFailure();     // 失败累计触发熔断
        throw e;
    }
} else {
    result = "fallback";             // 熔断拒绝时的降级结果
}

breaker.isOpen();                    // 是否处于打开状态
breaker.getName();                   // "order-service"
breaker.reset();                     // 手动复位到关闭状态
```

使用要点（与源码实现一致）：

- `recordSuccess()` / `recordFailure()` 只有在**本实例** `tryAcquire()` 成功后调用才生效；未获取许可（或已结算过）时 `callStartNs` 小于 0，两个方法直接返回。
- `callStartNs` 是**单一槽位**（`AtomicLong`）：同一实例上并发调用时，成功/失败的耗时统计归属可能串扰。请按调用串行使用，或为每个调用方创建独立实例。

**SPI 获取方式的重要边界**（均经源码验证）：

- `ServiceProvider.of(CircuitBreakerProvider.class).getExtension("default")` 对本实现**取不到实例**：SPI 无参获取路径（`ServiceDefinition.getObj` → `ClassUtils.newInstance(implClass)`）要求无参构造器，而本类只有上述 4 参构造器，实例化失败后返回 `null`。
- 因此 common-starter 的门面 `CircuitBreakerFlow`（内部按熔断器名称 `getExtension(name)` 取实现，未命中时回落 `InMemoryCircuitBreakerProvider`）**不会自动选中本模块的 Resilience4j 实现**。
- 要使用本实现，请直接构造（如上示例）。

---

## 4. 构造参数与熔断行为

| 参数 | 类型 | 映射到 Resilience4j |
| --- | --- | --- |
| `name` | `String` | 熔断器名称：`CircuitBreakerRegistry.of(config).circuitBreaker(name)`；`getName()` 返回该值 |
| `failureThreshold` | `int` | `failureRateThreshold((float) failureThreshold / 100)`；同时 `slidingWindowSize(failureThreshold * 2)` |
| `successThreshold` | `int` | `permittedNumberOfCallsInHalfOpenState(successThreshold)`：半开状态放行的试探调用数 |
| `waitDuration` | `long` | `waitDurationInOpenState(Duration.ofMillis(waitDuration))`：熔断打开持续的毫秒数，之后进入半开 |

注意（如实陈述源码换算关系）：Resilience4j 的 `failureRateThreshold` 以百分数为单位（50 表示 50%）。本实现将入参除以 100 后传入，因此入参 `50` 实际设置的是 `0.5`（即 0.5%）失败率阈值；若期望 50% 的失败率阈值，入参应传 `5000`。滑动窗口大小固定为入参的 2 倍（入参 `50` → 窗口 100 次调用）。

状态流转（Resilience4j 标准三态）：

1. **CLOSED**：`tryAcquire()` 放行；滑动窗口内失败率达到阈值后进入 **OPEN**。
2. **OPEN**：`tryAcquire()` 返回 `false`（业务侧触发降级）；持续 `waitDuration` 毫秒后进入 **HALF_OPEN**。
3. **HALF_OPEN**：放行至多 `successThreshold` 次试探调用；全部成功则回到 **CLOSED**，出现失败则立即回到 **OPEN**。

---

## 5. 配置项

**无。**

依据：本模块 `src/main/resources` 仅有 SPI 扩展声明与 GraalVM `reflect-config.json` 两个资源，无 `properties` / `yml` / `spring-configuration-metadata.json`；源码亦未读取任何配置键。全部行为参数（失败率、滑动窗口、半开阈值、打开时长、名称）均通过构造器参数传入。

---

## 6. SPI 扩展点

### 6.1 本模块注册的 SPI

- 接口：`com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider`（位于 `utils-support-common-starter`）。
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider`，内容：

```properties
default=com.chua.resilience4j.support.circuitbreaker.Resilience4jCircuitBreakerProvider
```

- `@Spi(value = "default", order = 100)`：与 common-starter 内置的 `InMemoryCircuitBreakerProvider`（`@Spi("default")`）同名，按 order 排序时本实现优先级更高；但如第 3 节所述，无参获取路径取不到本实现实例。
- `@ConditionalOnClass("io.github.resilience4j.circuitbreaker.CircuitBreaker")`：classpath 缺少 resilience4j 时，该 SPI 定义在注册期被条件评估跳过，自动让位给内存实现。
- GraalVM：`META-INF/native-image/com.chua/utils-support-resilience4j-starter/reflect-config.json` 已登记 `Resilience4jCircuitBreakerProvider` 的 `allDeclaredConstructors` / `allPublicConstructors` / `allDeclaredMethods` / `allPublicMethods` / `allDeclaredFields` / `allPublicFields`。

### 6.2 本模块不提供的新扩展点

源码未提供。模块仅包含上述 1 个实现类：不定义 SPI 接口，不提供服务解析器，也不提供自动装配逻辑。

---

## 7. 构建与依赖

- 父模块：`com.chua:utils-support-middleware-parent:4.0.0.51`（聚合于 `utils-support-parent-starter`）。
- JDK：`25`（根 POM `java.version=25`、`maven.compiler.release=25`）。
- 依赖清单（本模块 `pom.xml` 仅声明以下两项，版本均由父 POM 管理）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | 提供 `CircuitBreakerProvider` SPI、`ServiceProvider`、内存实现与 `CircuitBreakerFlow` 门面 |
| `io.github.resilience4j:resilience4j-circuitbreaker` | `2.4.0`（根 POM `dependencyManagement`） | 熔断器实现；根 POM 同时管理 bulkhead / core / ratelimiter / retry / timelimiter 的 `2.4.0`，本模块未引入 |

- 构建命令（仓库统一口径）：

```bash
mvn -o -q compile -DskipTests
```

---

## 8. 边界：这些能力不在本模块

| 能力 | 实际位置 |
| --- | --- |
| `CircuitBreakerProvider` SPI 接口、`CircuitBreakerFlow` 门面、`InMemoryCircuitBreakerProvider` 内存实现 | `utils-support-common-starter`（`com.chua.common.support.concurrent.circuitbreaker` 及其 `provider` 子包） |
| 限流（`RateLimiter` / `RateLimiterFlow`） | `utils-support-common-starter`（`concurrent.rate`，Guava 实现） |
| 重试策略 | `utils-support-common-starter`（`task.retry`）；另有 `utils-support-retry-starter` |
| 舱壁隔离 | `utils-support-common-starter`（`concurrent.bulkhead`） |
| Sentinel 熔断实现 | `utils-support-sentinel-starter`（同属 `utils-support-middleware-parent`） |
| resilience4j 的 bulkhead / ratelimiter / retry / timelimiter 适配 | 源码未提供（根 POM 已统一管理 `2.4.0` 版本，本模块仅依赖 `resilience4j-circuitbreaker`） |
| Spring Boot 自动配置、配置项绑定、指标上报、事件监听 | 源码未提供 |
