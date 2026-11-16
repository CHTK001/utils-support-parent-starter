# utils-support-springboot-starter

> utils 体系的 **Spring Boot 自动装配层**：把 utils-support-spring-starter 的 AOP 拦截体系（限流、熔断、分布式锁、请求折叠、舱壁、超时）与 utils-support-common-starter 的 ObjectContext 容器桥接到 Spring Boot，开箱即用、零配置启动。
>
> 版本：`4.0.0.51`（继承自 `utils-support-core-parent`）｜JDK：`25`（`maven.compiler.source/target=25`，`maven.compiler.parameters=true`）｜包名：`com.chua.springboot.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 自动装配总览](#2-自动装配总览)
- [3. 核心 API](#3-核心-api)
- [4. 快速开始](#4-快速开始)
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
    <artifactId>utils-support-springboot-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 版本号随父模块 `utils-support-core-parent` 走，本模块 pom 使用 `${project.version}` 引用兄弟模块。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Spring Boot 自动配置（AutoConfiguration）模块，基于 `utils-support-spring-starter`，引入即生效 | 不是 Web MVC 模块（无 `spring-webmvc` 等依赖，不提供 Controller 能力） |
| 装配 | 自动注册 6 类并发治理拦截器（限流 / 熔断 / 分布式锁 / 请求折叠 / 舱壁 / 超时）的 Intercept + Advisor | 不定义这些注解与 AOP 类本身——AOP 类位于 `utils-support-spring-starter`；限流 / 熔断 / 分布式锁注解位于 `utils-support-common-starter`，请求折叠注解 `@Collapsible` 位于 `utils-support-spring-starter` |
| 桥接 | 提供 `SpringBootObjectContext`，把 Spring 容器桥接进 ObjectContext 体系（Bean 查找：Spring → 本地注册表 → SPI） | 不包含 ObjectContext 容器实现本身（位于 `utils-support-common-starter`） |
| 实现 | 内置装配 Guava 限流、内存熔断、内存分发器等默认 Provider | 分布式锁默认走 `chronicle` SPI（`utils-support-chronicle-starter`），折叠执行器由 `utils-support-collapse-starter` 提供 |
| 依赖 | 依赖 spring-boot-starter / spring-boot-autoconfigure | 不依赖 web、data、cloud 等 Spring 生态模块 |

**判断口径**：本模块只做「装配」与「桥接」，不复制任何能力的业务实现；具体实现由 `utils-support-spring-starter`（AOP 类与 `@Collapsible` 注解）、`utils-support-common-starter`（Provider 接口、注解与默认实现）和各 `*-starter`（SPI 实现）承担。

### 1.3 规模

- Java 源文件 **15** 个，顶层包 **4** 个：`autoconfigure`（7）、`context`（3）、`properties`（4）、`application`（1）。
- 内置 GraalVM `native-image` 的 `reflect-config.json`（覆盖 11 个类）与 `resource-config.json`（包含 `META-INF/spring.factories`、`META-INF/spring/.*`）。

### 1.4 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 开箱即用的并发治理 AOP | 引入依赖即自动装配 | 限流 / 熔断 / 分布式锁 / 折叠 / 舱壁 / 超时六类 Advisor + Intercept 自动注册 |
| 分布式锁 | `@Autowired LockProvider` | `plugin.lock.*` 配置，默认 `chronicle` 类型（`utils-support-chronicle-starter`） |
| 限流 | `@Autowired RateLimiterProvider` | Guava 实现（`GuavaRateLimiterProvider`），`plugin.rate-limiter.*` 配置 |
| 熔断降级 | `@Autowired CircuitBreakerProvider` | 内存实现 `InMemoryCircuitBreakerProvider("default", 5, 2, 60000)` |
| 请求折叠 | 标注 `@Collapsible`（注解位于 `utils-support-spring-starter`） | 经 `CollapsibleAdvisor` 生效，`collapse.executor.*` 配置全局默认 |
| 舱壁 / 超时拦截 | 无需配置 | `BulkheadAdvisor` / `TimeoutAdvisor` 由 `UtilsSpringBootAutoConfiguration` 自动提供 |
| ObjectContext 桥接 | `@Autowired SpringBootObjectContext` | Bean 查找顺序：Spring → 本地注册表 → SPI |
| 集群对等节点 | `chua.cluster.*` 配置 | `ClusterAutoConfiguration` 提供零配置推导，但当前**未登记进自动装配**（源码缺陷见 §2），实际不生效 |
| 全局设置注册 | `new ModuleEnvironmentRegistration(config)` | 将模块开关/配置注册进 `GlobalSettingFactory` 统一观测 |

---

## 2. 自动装配总览

### 2.1 注册方式

双通道注册（`src/main/resources/META-INF/`）：

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：按序登记 7 个自动配置类；
- `META-INF/spring.factories`：登记 `org.springframework.context.ApplicationContextInitializer=com.chua.spring.support.configuration.ApplicationAwareApplicationContextInitializer`。

### 2.2 各自动配置及产出 Bean

| 自动配置类 | 生效条件 | 产出的 Bean |
| --- | --- | --- |
| `UtilsSpringBootAutoConfiguration` | classpath 存在 `SpringBeanUtils` | `ApplicationAwareApplicationContextInitializer`（创建时执行 `SpringBeanUtils.setApplicationContext(...)`）；`BulkheadIntercept` / `TimeoutIntercept`；`BulkheadAdvisor` / `TimeoutAdvisor`。并 `@Import(UtilsSpringConfiguration.class)` 导入核心配置 |
| `CollapseAutoConfiguration` | classpath 存在 `CollapsibleAdvisor`，且 `collapse.executor.enabled != false`（缺省 true） | `CollapsibleIntercept`（注入 `CollapseProperties` 全局默认值）、`CollapsibleAdvisor` |
| `LockAutoConfiguration` | classpath 存在 `LockProvider`，且 `plugin.lock.enabled != false`（缺省 true） | `LockProvider`（`LockFlow.of("default")` + 锁类型/公平/等待/租约）、`DistributedLockIntercept`、`DistributedLockAdvisor` |
| `RateLimiterAutoConfiguration` | classpath 存在 `RateLimiterProvider`，且 `plugin.rate-limiter.enabled != false`（缺省 true） | `RateLimiterProvider`（`GuavaRateLimiterProvider`，预热时间 > 0 时启用平滑预热）、`RateLimiterIntercept`、`RateLimiterAdvisor` |
| `CircuitBreakerAutoConfiguration` | classpath 存在 `CircuitBreakerProvider` | `CircuitBreakerProvider`（`InMemoryCircuitBreakerProvider("default", 5, 2, 60000)`：熔断器名 `default`、失败阈值 5、成功阈值 2、熔断等待毫秒 60000）、`CircuitBreakerIntercept`、`CircuitBreakerAdvisor` |
| `DispatcherAutoConfiguration` | 无（恒生效） | `DispatcherProvider`（`MemoryDispatcherProvider` + `DispatcherConfig.builder().build()`） |
| `ClusterAutoConfiguration` | 类上标注 `@AutoConfiguration`，但**未登记进 `AutoConfiguration.imports`**，Spring Boot 当前不会加载该类 | 不产出任何 Bean：`clusterServer()` 未标注 `@Bean` 注解（构建好的 `ClusterServer` 不会注册）；`@EnableConfigurationProperties(ClusterProperties.class)` 绑定也不会触发 |
| `ObjectContextAutoConfiguration` | classpath 存在 `ObjectContext`；排在 `UtilsSpringBootAutoConfiguration` 之后执行 | `SpringBootObjectContext`（构造参数：Spring `ApplicationContext` + `ObjectContextConfig.defaults()`） |

> `ClusterAutoConfiguration` 的 `autoDetect()` 零配置推导逻辑（依据源码）：
> 1. **host**：先取 Spring `server.address`，仍为默认 `127.0.0.1` 时用 `com.chua.common.support.network.net.NetUtils.getLocalHost()` 探测本机局域网 IP；
> 2. **scatterId**：仍为默认 `"default"` 时取 `spring.application.name`；
> 3. **nodeId**：未配置时取 `host:port` 的 `DigestUtils.md5Hex` 前 12 位，保证同机多实例唯一。
>
> （注意：`autoDetect()` 为私有方法，仅被未标注 `@Bean` 的 `clusterServer()` 调用，且该类未登记进 `AutoConfiguration.imports`，上述推导当前不会执行。）

---

## 3. 核心 API

### 3.1 SpringBootObjectContext（`com.chua.springboot.support.context`）

实现对 `com.chua.common.support.objects.ObjectContext` 的完整适配，Bean 查找优先级（源码注释）：

```text
getBean(name, type)
  ├─ 1. Spring ApplicationContext.getBean(name, type)
  ├─ 2. 本地 BeanDefinitionRegistry.getBean(name, type)
  └─ 3. SPI ServiceProvider.getExtension(type)   （最后兜底）
```

| 方法 | 说明 |
| --- | --- |
| `getBean(name, type)` / `getBeanOfType(type)` | 多级查找（Spring → 本地注册表 → SPI 默认实现） |
| `getBeanOfTypeSafely(type)` | 失败返回 `null`，不抛异常 |
| `getBeanOfTypes(type)` / `getBeanOfTypeCollection(type)` | 合并 Spring 与本地注册表的全量实例 |
| `getBeansWithAnnotation` / `getMethodWithAnnotation` | 按注解查 Bean / 方法（包装为 `MethodDefinition`） |
| `autowire(bean)` | 委托 Spring `AutowireCapableBeanFactory.autowireBean` 完成依赖注入 |
| `publish(event)` | 同步发布到 Spring 容器（`publishEvent`）+ 触发自有 `EventPublisher` |
| `registerBean(...)` | 注册到本地注册表；实例形式会经 `SpringObjectContextBridge` 同步为 Spring 单例，后续可被 `@Autowired` |
| `scan(packages)` | 包扫描（`ObjectContextScanner`） |
| `getEnvironment()` | `Environment` 适配器（内部类 `SpringBootEnvironment`，只读代理 Spring `Environment`；`setProperty`、变更监听、动态配置源等留空） |
| `getApplicationContext()` | 直接暴露 Spring `ApplicationContext` |
| `close()` | 清理本地注册表缓存，不销毁 Bean |

### 3.2 SpringObjectContextBridge（包内工具，非公开）

将外部对象桥接进 Spring 容器：仅在 `ConfigurableApplicationContext` 可用时生效，通过 `DefaultListableBeanFactory.registerSingleton` 以单例注册，beanName 默认取小驼峰类名（与 `TypeBeanDefinition` 命名一致）；已注册名在内部缓存中记录，避免重复注册。

### 3.3 ModuleEnvironmentRegistration（`com.chua.springboot.support.application`）

把各模块的开关和核心配置注册到全局环境（`com.chua.common.support.application.GlobalSettingFactory`），统一管理与观测：

```java
new ModuleEnvironmentRegistration(config);                          // 分组 = config.getClass().getName()，启用
new ModuleEnvironmentRegistration("group", config, enabled);        // 指定分组与启用状态
```

### 3.4 拦截式并发治理（Advisor / Intercept）

本模块不定义注解与 AOP 类，只负责装配：限流 / 熔断 / 分布式锁注解位于 `utils-support-common-starter`，`@Collapsible` 与 AOP 类位于 `utils-support-spring-starter`。确认可用的注解名：`com.chua.common.support.concurrent.lock.annotation.DistributedLock`（分布式锁）、`com.chua.common.support.concurrent.rate.annotation.RateLimiter`（限流）、`com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker`（熔断）、`com.chua.spring.support.annotation.Collapsible`（请求折叠，`CollapseAutoConfiguration` 引用）。折叠注解的两个属性语义见 `CollapseProperties` 注释：注解 `waitThreshold=-1` / `collectingWaitTime=-2` 表示未指定，此时读取 `collapse.executor.*` 全局默认值。

---

## 4. 快速开始

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-springboot-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

引入依赖后 7 个自动配置类即生效，无需 `@Import`、无需 `@EnableXxx`。

### 4.2 最小示例

```java
import com.chua.springboot.support.context.SpringBootObjectContext;
import com.chua.common.support.concurrent.lock.LockProvider;
import org.springframework.stereotype.Component;

@Component
public class DemoService {

    /** 分布式锁提供者：plugin.lock.type 选择 SPI 实现，默认 chronicle */
    private final LockProvider lockProvider;

    /** Spring ↔ ObjectContext 桥接容器 */
    private final SpringBootObjectContext objectContext;

    public DemoService(LockProvider lockProvider, SpringBootObjectContext objectContext) {
        this.lockProvider = lockProvider;
        this.objectContext = objectContext;
    }

    /** ObjectContext 统一查找：Spring → 本地注册表 → SPI */
    public <T> T find(Class<T> type) {
        return objectContext.getBeanOfType(type);
    }

    /** Bean 定义总数（Spring + 本地注册表） */
    public int beanCount() {
        return objectContext.getBeanDefinitionCount();
    }
}
```

### 4.3 请求折叠示例

```java
import com.chua.spring.support.annotation.Collapsible;
import java.util.Collection;
import java.util.Map;

@Collapsible            // 注解位于 utils-support-spring-starter，Advisor 自动生效
public Map<Long, User> getUsers(Collection<Long> ids) {
    return userMapper.selectByIds(ids);
}
```

> 折叠语义（依据 `@Collapsible` 源码注释）：标注方法必须恰好一个入参且为 `Collection`；返回 `Map`（key = 入参元素）时按"合并拆分"执行，返回非 `Map` 时降级为"同参折叠"；空集合入参直接执行。更多属性（`name`、`key`、`fallback` 等）见注解源码。

> 折叠执行器实现来自 `utils-support-collapse-starter`（SPI 自动发现），缺失时 `CollapseAutoConfiguration` 自动降级为直接执行（依据源码注释）。

---

## 5. 配置项

### 5.1 请求折叠（`collapse.executor.*`，绑定 `CollapseProperties`）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `collapse.executor.enabled` | `true` | 折叠自动装配总开关（`matchIfMissing`） |
| `collapse.executor.wait-threshold` | `10` | 全局默认批量收集阈值（注解未指定时生效） |
| `collapse.executor.collecting-wait-time` | `0` | 全局默认补收等待毫秒（注解未指定时生效） |

```yaml
collapse:
  executor:
    enabled: true
    wait-threshold: 10
    collecting-wait-time: 0
```

### 5.2 分布式锁（`plugin.lock.*`，绑定 `LockProperties`）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `plugin.lock.enabled` | `true` | 分布式锁自动配置总开关（`matchIfMissing`） |
| `plugin.lock.type` | `chronicle` | 锁类型标识，对应 `LockProvider` 的 SPI 实现（默认 `chronicle`，来自 `utils-support-chronicle-starter`） |
| `plugin.lock.fair` | `false` | 是否公平锁 |
| `plugin.lock.wait-time` | `0` | 获取锁等待毫秒，小于等于 0 表示不等待 |
| `plugin.lock.lease-time` | `-1` | 锁租约毫秒，`-1` 表示不自动释放 |

### 5.3 限流（`plugin.rate-limiter.*`，绑定 `RateLimiterProperties`）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `plugin.rate-limiter.enabled` | `true` | 限流自动配置总开关（`matchIfMissing`） |
| `plugin.rate-limiter.name` | `default` | 限流器名称 |
| `plugin.rate-limiter.permits-per-second` | `1000` | 每秒许可数（QPS），double |
| `plugin.rate-limiter.warmup-period` | `0` | 预热时间（秒），大于 0 时启用 Guava 平滑预热模式 |

### 5.4 集群（`chua.cluster.*`，绑定 `ClusterProperties`）

> ⚠️ `ClusterAutoConfiguration` 当前未登记进 `AutoConfiguration.imports`，下列配置项目前不会生效（见 §2.2）。

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `chua.cluster.enabled` | `true` | 集群自动配置总开关（`matchIfMissing`） |
| `chua.cluster.node-id` | 自动生成 | 节点标识，缺省取 `host:port` 的 md5Hex 前 12 位 |
| `chua.cluster.host` | `127.0.0.1` | 本机主机，未配置时按 `server.address` → 局域网自动探测 |
| `chua.cluster.scatter-id` | `default` | 业务分组标识，未配置时取 `spring.application.name` |
| `chua.cluster.seeds` | `[]` | 集群种子节点列表（`主机:端口`） |
| `chua.cluster.service-paths` | `[]` | 本节点服务路径列表 |
| `chua.cluster.port` | `0` | 本节点业务端口（0 = 自动分配） |
| `chua.cluster.scatter-port` | `0` | scatter 通信端口（0 = 端口 + 2） |
| `chua.cluster.http-enabled` | `true` | 是否启用 HTTP 入口 |
| `chua.cluster.tcp-enabled` | `false` | 是否启用 TCP 入口 |
| `chua.cluster.balance` | `weight` | 负载均衡策略（权重 / round / 随机） |
| `chua.cluster.timeout-millis` | `3000` | 请求超时毫秒 |
| `chua.cluster.auto-discovery-interval-millis` | `1000` | 自动发现间隔毫秒 |
| `chua.cluster.server-entries[]` | `[]` | 声明的远端服务条目（`service-path` / `host` / `port` / `protocol`（http / tcp / udp，默认 `http`）/ `scatter-id`（为空时不按分组过滤））。同一服务路径下只允许同一种协议 |

### 5.5 对象上下文（`object-context.*`）

`ObjectContextAutoConfiguration` 的源码注释给出的配置示例：

```yaml
object-context:
  spi-enabled: true
  annotation-scan-enabled: false
  scan-packages:
    - com.example.app
```

> 实际读取逻辑位于 `utils-support-common-starter` 的 `ObjectContextConfigLoader`（读取 classpath 的 `application.yml` / `application.yaml` / `application.properties` 等），由 `ObjectContextConfig.defaults()` 触发。

### 5.6 Actuator 端点

无。`spring-boot-starter-actuator` 仅作为 `provided` + `optional` 依赖存在（「Actuator 端点支持（可选）」），本模块不提供任何端点实现（依据：pom.xml）。

---

## 6. SPI 扩展点

本模块不定义 SPI 接口，只**消费** `utils-support-common-starter` 的 SPI 接口并装配默认实现；全部产出 Bean 均带 `@ConditionalOnMissingBean`，用户在应用里自定义同类型 `@Bean` 即可整体替换：

| 接口（定义于 common-starter） | 装配位置 | 默认实现 / 可选项 |
| --- | --- | --- |
| `com.chua.common.support.concurrent.lock.LockProvider` | `LockAutoConfiguration` | 默认 SPI 名称 `chronicle`（`utils-support-chronicle-starter`）；`plugin.lock.type` 切换（`semaphore`、`circular` 等在 common-starter） |
| `com.chua.common.support.concurrent.rate.RateLimiterProvider` | `RateLimiterAutoConfiguration` | `GuavaRateLimiterProvider` |
| `com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider` | `CircuitBreakerAutoConfiguration` | `InMemoryCircuitBreakerProvider("default", 5, 2, 60000)` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `DispatcherAutoConfiguration` | `MemoryDispatcherProvider(DispatcherConfig.builder().build())` |
| 折叠执行器实现 | `CollapseAutoConfiguration`（`CollapsibleIntercept` 内部委托） | 由 `utils-support-collapse-starter` 经 SPI 自动发现；缺失时降级为直接执行（依据源码注释） |

---

## 7. 构建与依赖

### 7.1 编译要求

- JDK 25（`maven.compiler.source/target=25`，`maven.compiler.parameters=true`）。
- 源码 UTF-8、无 BOM。
- Spring Boot 相关依赖版本由更上层依赖管理决定（本模块 pom 未锁定版本号）。

```bash
mvn -pl utils-support-core-parent/utils-support-springboot-starter -am clean package
```

### 7.2 依赖

| 依赖 | 用途 | 作用域 |
| --- | --- | --- |
| `utils-support-spring-starter` | 核心：`UtilsSpringConfiguration`、`SpringBeanUtils`、AOP 类（Advisor / Intercept）与 `@Collapsible` 注解（限流 / 熔断 / 分布式锁注解在 `utils-support-common-starter`） | compile |
| `spring-boot-starter` / `spring-boot-autoconfigure` | 自动装配基础 | compile |
| `utils-support-chronicle-starter` | 分布式锁默认 SPI 实现（`chronicle`） | compile |
| `spring-boot-configuration-processor` | 配置属性元数据生成 | provided |
| `utils-support-osgi-starter` | OSGI 模块支持 | provided |
| `spring-boot-starter-actuator` | Actuator 端点支持（可选） | provided + optional |

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 / 现状 |
| --- | --- |
| 注解与 AOP 拦截类（`@Collapsible`、各 `*Advisor` / `*Intercept`、`SpringBeanUtils`、`UtilsSpringConfiguration`、`ApplicationAwareApplicationContextInitializer` 等） | `@Collapsible` 与 AOP 类位于 `utils-support-spring-starter`；限流 / 熔断 / 分布式锁注解位于 `utils-support-common-starter`；本模块只装配 |
| 折叠执行器实现 | `utils-support-collapse-starter`（SPI 自动发现，缺失降级直接执行） |
| 分布式锁 `chronicle` 实现 | `utils-support-chronicle-starter`；`semaphore` / `circular` 等锁 SPI 在 `utils-support-common-starter` |
| 日期转换 / Formatter 自动注册 | 由 `UtilsSpringConfiguration` 经 `@Import({DateConvertConfiguration.class, FormatterConfiguration.class})` 导入完成；注册类位于 `utils-support-spring-starter` |
| 「API 统一配置（版本控制、编解码、灰度、CORS、Mock、Feature Flag）」 | pom 描述提及，本模块源码中无对应装配类 |
| OSGI 模块上下文集成 | `utils-support-osgi-starter` 为 `provided` 依赖，OSGI 实现不在本模块 |
| Actuator 端点 | `spring-boot-starter-actuator` 为 `provided` + `optional`，端点实现需自行引入 |
| `ClusterServer` 自动注册 | 源码中 `clusterServer()` 方法未标注 `@Bean` 注解，且 `ClusterAutoConfiguration` 未登记进 `AutoConfiguration.imports`，集群节点当前不会自动注册为 Bean |
| Web MVC / Controller / 请求路由 | 本模块无任何 Web 依赖与 Servlet 能力 |