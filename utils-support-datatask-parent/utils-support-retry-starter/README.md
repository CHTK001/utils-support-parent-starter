# utils-support-retry-starter

> utils 体系「任务与流程」域下的**重试实现模块**：基于 `guava-retrying`（`com.github.rholder:guava-retrying:2.0.0`）实现 `RetryProvider` SPI，提供声明式重试能力——固定 / 指数 / 斐波那契三种退避策略、异常过滤、重试监听与异常还原。重试契约（`RetryProvider` / `RetryConfig`）与默认 JDK 实现在 `utils-support-common-starter`，本模块只做「Guava 实现」这一件事。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-datatask-parent`）｜JDK：`25`（`java.version=25`，`maven.compiler.release=25`）｜包名：`com.chua.retry.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项（RetryConfig）](#4-配置项retryconfig)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-retry-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `RetryProvider` SPI 的 **Guava Retry 实现**（SPI 名 `guava-retry`），实现类 `GuavaRetryProvider`，`@since 4.0.0.42` | 不定义重试契约——`RetryProvider` / `AbstractRetryProvider` / `RetryConfig` 都在 `utils-support-common-starter` |
| 策略 | 固定 / 指数 / 斐波那契退避、按异常类型过滤、每次失败重试前回调监听、还原业务异常 | 不含限流、熔断、舱壁、请求合并等并发治理能力 |
| 装配 | 通过 `META-INF/services` 注册，可被 `ServiceProvider` 按名获取，也可直接 `new GuavaRetryProvider()` | 无 Spring 自动装配、无配置文件键（源码未提供） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `guava-retrying 2.0.0` | 不依赖 Spring、数据库驱动、网络协议栈 |

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.retry.support.GuavaRetryProvider`。
- SPI 注册文件 **1** 个：`META-INF/services/com.chua.common.support.task.retry.RetryProvider`。
- GraalVM `native-image` 配置 **3** 个：`META-INF/native-image/com.chua/utils-support-retry-starter/` 下的 `reflect-config.json`（注册 `GuavaRetryProvider` 全部构造器/方法/字段）、`proxy-config.json`、`resource-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 引入依赖 | `com.chua:utils-support-retry-starter:4.0.0.51` | 随父 POM `4.0.0.51` 发布 |
| 创建提供者（直接实例化） | `new GuavaRetryProvider()` | 公有无参构造，拿到即用 |
| 按名获取（SPI） | `ServiceProvider.of(RetryProvider.class).getExtension("guava-retry")` | SPI 名称来自 `@Spi("guava-retry")` |
| 配置重试 | `new RetryConfig().setMaxRetries(3).setDelay(500).setBackoffStrategy(...)` | 链式Setter，见第 4 节 |
| 执行有返回值任务 | `provider.execute(Callable<T>, config)` | 全部重试失败后抛最后一次异常 |
| 执行无返回值任务 | `provider.execute(Runnable, config)` | 由 `AbstractRetryProvider` 适配为 `Callable` 委托执行 |
| 只重试特定异常 | `config.setRetryOnException(ex -> ex instanceof IOException)` | 判定为不可重试的异常立即抛出，不消耗重试次数 |
| 监听每次重试 | `config.setRetryListener((n, cause) -> System.out.println(n))` | 失败重试前回调 `onRetry(attemptNumber, cause)` |

### 2.2 类型一览

| 类型 | 归属 | 说明 |
| --- | --- | --- |
| `com.chua.retry.support.GuavaRetryProvider` | 本模块 | 唯一实现类，`extends AbstractRetryProvider`，标注 `@Spi("guava-retry")` |
| `com.chua.common.support.task.retry.RetryProvider` | common-starter | 顶级接口：`execute(Callable, RetryConfig)` / `execute(Runnable, RetryConfig)` |
| `com.chua.common.support.task.retry.AbstractRetryProvider` | common-starter | 抽象基类；子类只需实现 `doExecute(Callable, RetryConfig)` |
| `com.chua.common.support.task.retry.RetryConfig` | common-starter | 重试配置（链式），含 `BackoffStrategy` 枚举与 `RetryListenerCallback` 接口 |
| `com.chua.common.support.task.retry.JdkRetryProvider` | common-starter | 纯 JDK 循环等待的默认实现，退避语义与本模块一致 |
| `com.chua.common.support.task.retry.RetryException` | common-starter | 重试耗尽异常（JDK 实现抛出；本模块会还原为业务异常） |

### 2.3 实现要点（与源码一致）

- **停止策略**：`StopStrategies.stopAfterAttempt(maxRetries + 1)`，即总执行次数 = `maxRetries + 1`（首次执行 + `maxRetries` 次重试）。
- **等待策略映射**（`n` 从 1 开始计，即第 1 次重试）：

  | `BackoffStrategy` | 第 `n` 次重试前等待 | 说明 |
  | --- | --- | --- |
  | `FIXED` | `delay` 毫秒 | 固定间隔（`WaitStrategies.fixedWait`） |
  | `EXPONENTIAL` | `delay × multiplier^(n-1)` 毫秒 | 首次重试等待 `delay`；溢出封顶 `Long.MAX_VALUE` |
  | `FIBONACCI` | `delay × fib(n)` 毫秒（fib(0)=0、fib(1)=1） | 溢出封顶 `Long.MAX_VALUE` |

  > 指数 / 斐波那契为本模块自行实现：`guava-retrying` 自带的 `exponentialWait` / `fibonacciWait` 把入参当作等待上限、增长基数写死，无法满足 `RetryConfig.getMultiplier()` 语义。
- **异常过滤**：`retryOnException` 为 `null` 时重试所有 `Exception`；否则只有判定为可重试的异常才消耗重试次数（与 `JdkRetryProvider` 一致）。
- **重试监听**：仅在该次尝试失败（`attempt.hasException()`）时回调 `onRetry(attemptNumber, cause)`。
- **异常还原**：任务异常从 guava-retrying 的包装中还原——`ExecutionException` 取 `cause`；重试耗尽的 `RetryException` 取最后一次失败尝试的异常；取不到时退回包装异常本身。因此调用方捕获到的是业务异常，而不是 `RetryException`。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-retry-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.task.retry.RetryConfig;
import com.chua.retry.support.GuavaRetryProvider;

import java.util.concurrent.atomic.AtomicInteger;

public class RetryDemo {
    public static void main(String[] args) throws Exception {
        // 1) 创建提供者：直接实例化，或 SPI 按名获取
        GuavaRetryProvider provider = new GuavaRetryProvider();

        // 2) 配置：最多重试 3 次（共执行 4 次），指数退避 500ms 起、倍率 2.0
        RetryConfig config = new RetryConfig()
                .setMaxRetries(3)
                .setDelay(500)
                .setBackoffStrategy(RetryConfig.BackoffStrategy.EXPONENTIAL)
                .setMultiplier(2.0)
                .setRetryListener((attempt, cause) ->
                        System.out.println("第 " + attempt + " 次重试，原因: " + cause));

        // 3) 执行带重试的任务
        AtomicInteger calls = new AtomicInteger();
        String result = provider.execute(() -> {
            if (calls.incrementAndGet() < 3) {          // 前两次模拟失败
                throw new IllegalStateException("下游暂不可用");
            }
            return "ok";
        }, config);

        System.out.println(result);                     // ok（第 3 次执行成功）
    }
}
```

### 3.3 通过 SPI 获取实现

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.retry.RetryProvider;

RetryProvider provider = ServiceProvider.of(RetryProvider.class).getExtension("guava-retry");
```

---

## 4. 配置项（RetryConfig）

本模块**没有** `application.yml` / `properties` 配置键，也没有 Spring 自动装配（源码未提供）；全部配置通过 `RetryConfig` 的链式 Setter 编程式传入。

| 链式 Setter | 默认值 | 说明 |
| --- | --- | --- |
| `setMaxRetries(int)` | `3` | 最大重试次数；总执行次数 = `maxRetries + 1` |
| `setDelay(long)` | `1000`（毫秒） | 首次重试前等待时间；`FIXED` 策略下每次重试均等待该值 |
| `setMultiplier(double)` | `2.0` | 指数退避乘数：第 `n` 次重试前等待 `delay × multiplier^(n-1)` |
| `setBackoffStrategy(BackoffStrategy)` | `FIXED` | 退避策略枚举：`FIXED` / `EXPONENTIAL` / `FIBONACCI` |
| `setRetryOnException(Predicate<Throwable>)` | `null`（重试所有 `Exception`） | 异常过滤器；返回 `false` 的异常立即抛出，不消耗重试次数 |
| `setRetryListener(RetryListenerCallback)` | 空实现（no-op） | 重试监听器；每次失败重试前回调 `onRetry(int attemptNumber, Throwable cause)` |

---

## 5. SPI 扩展点

- **扩展点接口**：`com.chua.common.support.task.retry.RetryProvider`（位于 `utils-support-common-starter`）。
- **本模块注册内容**：`META-INF/services/com.chua.common.support.task.retry.RetryProvider` 文件中登记 `com.chua.retry.support.GuavaRetryProvider`；SPI 名称 `guava-retry` 来自类上的 `@Spi("guava-retry")` 注解。
- **获取方式**：`ServiceProvider.of(RetryProvider.class).getExtension("guava-retry")`；也可以直接 `new GuavaRetryProvider()`。
- **如何新增自己的实现**：
  1. 实现 `RetryProvider`（推荐继承 `AbstractRetryProvider`，只实现 `doExecute(Callable, RetryConfig)`）；
  2. 在实现类上标注 `@Spi("你的名称")`；
  3. 在你自己的模块里创建 `src/main/resources/META-INF/services/com.chua.common.support.task.retry.RetryProvider`，逐行登记实现类全限定名。
- **GraalVM**：`META-INF/native-image/com.chua/utils-support-retry-starter/` 已含 `reflect-config.json`（`GuavaRetryProvider` 全量反射注册）、`proxy-config.json`、`resource-config.json`。

---

## 6. 构建与依赖

- **父链**：`utils-support-parent-starter`（`4.0.0.51`）→ `utils-support-datatask-parent`（聚合 POM）→ `utils-support-retry-starter`。
- **依赖**：
  - `com.chua:utils-support-common-starter`（重试契约所在）；
  - `com.github.rholder:guava-retrying:2.0.0`（重试策略库）。
- **JDK**：`25`（`java.version=25`，`maven.compiler.release=25`）。
- **编译**：在本模块目录执行

  ```bash
  mvn -o -q compile -DskipTests
  ```

  或在 `utils-support-parent-starter` 仓库根目录按模块构建：

  ```bash
  mvn -o -q compile -DskipTests -pl utils-support-datatask-parent/utils-support-retry-starter
  ```

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| 重试契约（`RetryProvider` / `RetryConfig`）与默认 JDK 实现（`JdkRetryProvider`） | `utils-support-common-starter` |
| 链式重试门面（`RetryFlow`：`of(name)` / `maxRetries` / `fallback` / `executeAsync` 返回 `Mono`） | `utils-support-common-starter` |
| 退避器（`BackoffFlow` / `BackoffProvider`） | `utils-support-common-starter` |
| 定时调度 | `task.scheduler.TimeScheduler`（common-starter）、`utils-support-quartz-starter` |
| 流程编排 / 管道 / 分布式任务 | `utils-support-flow-starter`、`task.pipeline`、`taskdistribution`（common-starter） |
| 请求合并、限流、熔断 | `utils-support-collapse-starter`、common-starter `concurrent` 包 |
| 注解式重试入口（如 `@Retryable` 式注解） | 源码未提供 |
| Spring Boot 自动装配 / 配置文件键 | 源码未提供 |

> 本模块只有 1 个实现类；它不持有任何业务规则，只是把 `RetryConfig` 翻译成 guava-retrying 的 `Retryer` 并执行。
