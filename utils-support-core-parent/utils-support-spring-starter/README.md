# utils-support-spring-starter

> Spring 框架集成/适配层：Spring 容器访问门面（`SpringBeanUtils`）、日期时间 `ConversionService` 与 MVC `Formatter`、AOP 防护注解（`@DistributedLock` / `@Collapsible` 等）、SpEL / 占位符 / 脚本 / Spring MVC 注解解析适配，以及轻量 IoC（`ObjectContext`）与内置 Server 的 Spring 桥接。
>
> 版本：`4.0.0.51`｜JDK：`25`（pom `java.version=25`）｜包名：`com.chua.spring.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
  - [1.4 包地图](#14-包地图)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 引入依赖](#31-引入依赖)
  - [3.2 纯 Spring 最小示例](#32-纯-spring-最小示例)
  - [3.3 Spring MVC 日期参数自动绑定](#33-spring-mvc-日期参数自动绑定)
  - [3.4 AOP 注解：分布式锁与请求折叠](#34-aop-注解分布式锁与请求折叠)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
  - [5.1 META-INF/extensions（SPI 注册文件）](#51-meta-infextensionsspi-注册文件)
  - [5.2 META-INF/services（ServiceLoader 注册文件）](#52-meta-infservicesserviceloader-注册文件)
  - [5.3 spring.factories（Spring SPI）](#53-springfactoriesspring-spi)
  - [5.4 注解式 @Spi 声明](#54-注解式-spi-声明)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界](#7-边界)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-spring-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父 POM：`utils-support-core-parent`（`4.0.0.51`）。编译期依赖同仓 `utils-support-common-starter` 与 `utils-support-network-starter`（均为 `${project.version}`）。

**启用方式**：纯 Spring 环境通过 `@Import(UtilsSpringConfiguration.class)` 启用；Spring Boot 环境由 `utils-support-springboot-starter` 自动装配（该模块与本模块同属 `utils-support-core-parent` 的独立兄弟模块，不属于本 spring-starter）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Spring 环境下的集成适配层：把 utils 体系（common-starter）的能力桥接到 Spring 容器（Bean 访问、类型转换、AOP、SpEL、配置源、轻量 IoC、脚本、内置 Server 路由） | 不是 Spring Boot 自动配置模块——自动装配由 `utils-support-springboot-starter` 承接 |
| 锁/限流/熔断 | 提供基于 `LockFlow` / `RateLimiterFlow` / `CircuitBreakerFlow` / `BulkheadFlow` / `TimeoutFlow` 门面的拦截器与 AOP Advisor 适配 | 不含具体实现（Redis/ZK 等锁 SPI、限流/熔断底层实现在 common 及其它 `*-starter`） |
| 请求折叠 | 原生提供 `@Collapsible` 注解 + `CollapsibleAdvisor` / `CollapsibleIntercept`（v2 合并拆分语义） | 折叠执行器 SPI（名 `collapse`）实现需另引 `utils-support-collapse-starter` |
| 日期时间 | 10 个 Spring `Converter`（String ↔ LocalDate / LocalDateTime / LocalTime，Date ↔ LocalDate / LocalDateTime）+ 3 个 MVC `Formatter`（默认 ISO，支持自定义 pattern 构造器） | 不覆盖第三方日期库（Joda-Time 等） |
| 依赖 | 仅依赖同仓 common / network 两个 starter；Spring 依赖全部为 `provided` | 不传递任何 Spring 依赖——引用方自行提供 Spring 版本 |

### 1.3 规模

- Java 源文件 **49** 个，**11** 个顶层包（含子包）。
- SPI 扩展声明 11 处：`META-INF/extensions/` 9 个文件（其中 `MethodAnnotationIntercept` 含 4 条映射）+ `META-INF/services/` 1 个 + `spring.factories` 1 个键（2 个实现）。
- 内置 GraalVM `native-image` 配置：`META-INF/native-image/com.chua/utils-support-spring-starter/reflect-config.json` 与 `resource-config.json`（resources 包含 `META-INF/extensions/.*`）。
- CHANGELOG：`4.0.0.30` 初始版本（2024-12-19，Java 21+）；当前版本 `4.0.0.51`。

### 1.4 包地图

| 包 | 文件数 | 职责 | 代表类型 |
| --- | --- | --- | --- |
| `configuration` | 6 | 上下文持有、总配置、条件注册、脚本发现切换 | `SpringBeanUtils`、`UtilsSpringConfiguration`、`ApplicationAwareApplicationContextInitializer` |
| `convert` | 15 | 日期时间转换器 / 格式化器与注册配置 | `DateConvertConfiguration`、`FormatterConfiguration`、10 个 `Converter`、3 个 `Formatter` |
| `proxy.intercept` | 9 | 注解拦截器与降级解析 | `DistributedLockIntercept`、`CollapsibleIntercept`、`FallbackResolver` |
| `aop` | 6 | Spring AOP Advisor（`StaticMethodMatcherPointcutAdvisor`） | `DistributedLockAdvisor`、`CollapsibleAdvisor`、`RateLimiterAdvisor` |
| `objects` | 7 | 轻量 IoC 的 Spring 桥接：注册器、构造器解析、字段/方法/配置注入器、环境与配置源适配 | `SpringBeanDefinitionRegister`、`SpringConfigSourceProvider`、`SpringEnvironmentAdapter` |
| `expression` | 1 | SpEL 表达式解析 SPI | `SpelExpressionResolver` |
| `lang.placeholder` | 1 | SpEL 占位符解析 SPI | `SpelPlaceholderResolver` |
| `network` | 1 | Spring MVC 注解 → 内置 Server 路由 | `SpringServerHandlerAnnotationParser` |
| `script` | 1 | 基于 `SpringFactoriesLoader` 的脚本引擎发现 | `SpringScriptProviderResolver` |
| `annotation` | 1 | 请求折叠注解 | `Collapsible` |
| `utils` | 1 | Spring MVC 注解族别名解析 | `SpringMvcAnnotationDefinitionResolver` |

---

## 2. 核心 API 与速查表

> 统一入口：`com.chua.spring.support.configuration.SpringBeanUtils`（静态方法，上下文经 `setApplicationContext` 或 `ApplicationAwareApplicationContextInitializer` 初始化）。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Bean 获取与注册 | `getBean(Class)` / `getBean(String, Class)` / `findBean(String)` / `registerBean(String, BeanDefinition)` | 按类型获取未找到返回 `null`；按名+类型获取未找到抛容器异常；支持运行时注册 Bean 定义 |
| 类型转换 | `convertIfNecessary(Object, Class / Type)` | 委托容器 `ConversionService`（含自动注册的 10 个日期转换器；`Type` 非 `Class` 返回 `null`） |
| 配置与环境 | `getPropertyAs(key, type)` / `getRequiredProperty(key)` / `resolvePlaceholders(text)` / `bindProperties(prefix, type)` | 环境读取、占位符解析、按前缀绑定属性到类型实例（基于 `BeanWrapperImpl`） |
| 事务模板 | `transactionTemplate()` / `transactionTemplate(int propagationBehavior)` | 基于容器 `PlatformTransactionManager`，可指定传播行为（见 `TransactionDefinition`） |
| 事件发布 | `SpringBeanUtils.EventPublisher.of().publish(event).publishAll(e1, e2)` | 链式发布一个或多个应用事件 |
| 异步执行 | `runAsync(Runnable)` / `runAsync(Runnable, TaskExecutor)` / `getTaskExecutor()` | 优先取名为 `taskExecutor` 的 Bean；缺省 `SimpleAsyncTaskExecutor`（线程名前缀 `spring-support-`） |
| URL → Handler | `getMethodInfo(RequestMappingHandlerMapping, url)` | 用 `MATCHER`（`AntPathMatcher`）匹配 `RequestMappingInfo` 的 pattern 返回 `HandlerMethod` |
| MVC 日期绑定 | 自动注册的 `LocalDateFormatter` / `LocalDateTimeFormatter` / `LocalTimeFormatter` | `@RequestParam` / `@PathVariable` 上的 Java 8 时间类型自动格式化与解析 |

其它常用静态方法：`getBeanList(Class)`、`getBeansWithAnnotation(annotationType)`、`isBeanPresent(name)`、`autowireBean(Object)`、`addApplicationListener(listener)`、`registerShutdownHook(callback)`、`getSingletonMutex(context)`、`getPort()`（`server.port`，默认 8080）、`getContextPath()`（`server.servlet.context-path`，默认 `/`）、`getMessageSource()` / `getMessage(code, args...)`、`getTargetBean(bean)` / `getTargetClass(bean)` / `isProxy(bean)`。

注解类入口见 [3.4](#34-aop-注解分布式锁与请求折叠)。

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-spring-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

Spring 依赖（`spring-context`、`spring-webmvc`、`spring-tx` 等）在本模块为 `provided`，**请按实际场景自行引入**；使用 `@DistributedLock`、`@Collapsible` 等功能时，再按需引入对应门面底层实现的 starter（如锁 SPI、`utils-support-collapse-starter`）。

### 3.2 纯 Spring 最小示例

```java
import com.chua.spring.support.configuration.SpringBeanUtils;
import com.chua.spring.support.configuration.UtilsSpringConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class QuickStart {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(UtilsSpringConfiguration.class)) {

            // 绑定上下文到 SpringBeanUtils。
            // Spring Boot 环境无需手动调用：spring.factories 的
            // ApplicationContextInitializer 会自动完成这一步。
            SpringBeanUtils.setApplicationContext(context);

            // 1) 容器访问
            UserService userService = SpringBeanUtils.getBean(UserService.class);

            // 2) 类型转换：ConversionService 已注册 String↔LocalDate/LocalDateTime/LocalTime、
            //    Date↔LocalDate/LocalDateTime
            LocalDateTime time = SpringBeanUtils.convertIfNecessary(
                    "2026-11-17T10:00:00", LocalDateTime.class);
            String text = SpringBeanUtils.convertIfNecessary(
                    LocalDate.of(2026, 11, 17), String.class); // "2026-11-17"

            // 3) 环境读取（默认值：server.port=8080、context-path=/）
            int port = SpringBeanUtils.getPort();
        }
    }
}
```

默认使用 ISO 格式（`yyyy-MM-dd` / `yyyy-MM-dd'T'HH:mm:ss` / `HH:mm:ss`）；如需自定义格式，用带 pattern 的构造器创建 `DateConvertConfiguration(datePattern, timePattern, dateTimePattern)` 与 `FormatterConfiguration(...)` 后注册，替代默认导入。

### 3.3 Spring MVC 日期参数自动绑定

```java
@RestController
public class OrderController {

    @GetMapping("/orders")
    public List<Order> queryByDate(@RequestParam LocalDate start,
                                   @RequestParam LocalDateTime end) {
        // GET /orders?start=2026-11-01&end=2026-11-17T23:59:59
        // start / end 自动绑定为 Java 8 时间类型
        return orderService.query(start, end);
    }
}
```

### 3.4 AOP 注解：分布式锁与请求折叠

`UtilsSpringConfiguration`（`@EnableAspectJAutoProxy` + `@Import` 日期转换配置）默认注册以下 Bean，均带 `@ConditionalOnMissingBeanDefinition` 退避（同名的自动配置 Bean 已存在时跳过，避免 `BeanDefinitionOverrideException`）：

- `distributedLockIntercept` / `distributedLockAdvisor`（匹配 `com.chua.common.support.concurrent.lock.annotation.DistributedLock`）
- `collapsibleIntercept` / `collapsibleAdvisor`（匹配 `com.chua.spring.support.annotation.Collapsible`，注解兼容接口 / CGLIB 代理场景）

```java
@Component
public class PayService {

    // 分布式锁：基于 LockFlow 门面，需引入锁 SPI 实现（如基于 Redis 的锁 starter）
    @DistributedLock(name = "pay", waitTime = 3000)
    public void pay(String orderId) {
        // 加锁执行
    }
}

@Component
public class UserService {

    /**
     * 请求折叠：并发窗口内合并为一次核心执行，结果按元素归属拆分回填。
     * 要求：恰好一个 Collection 入参；返回 Map（key = 入参元素）。
     * 需引入 utils-support-collapse-starter（折叠执行器 SPI 名 collapse）。
     */
    @Collapsible(name = "batch-find", waitThreshold = 4, collectingWaitTime = 10)
    public Map<Long, User> batchFind(List<Long> ids) {
        // 批量查询：窗口内多个调用方到达时，并集执行一次
    }
}
```

`@Collapsible` 注解属性（`com.chua.spring.support.annotation.Collapsible`）：

| 属性 | 说明 |
| --- | --- |
| `name` | 折叠执行器名称，同一名称共享同一收集器；缺省为 `目标类全限定名.方法名`（代理类经 `ClassUtils.getUserClass` 还原） |
| `key` | 元素归约键 SpEL 表达式（如 `"id"`、`"#this.id"`、`"getKey()"`）；缺省元素自身即键 |
| `fallback` | 降级方法引用：`methodName`（同类）或 `beanName#methodName`（Spring 容器 Bean）；返回非 null 视为降级成功 |
| `waitThreshold` | 收集阈值，达到后立即执行；`-1`（缺省哨兵）→ 全局默认 → 内置默认 `10` |
| `collectingWaitTime` | 补收等待毫秒：`<0` 立即执行、`=0` 让出时间片补收一次（内置默认）、`>0` 等待指定毫秒；`-2`（缺省哨兵）→ 全局默认 |

折叠语义按返回类型自适应（v2）：

- 返回 `Map`：**合并拆分**——窗口内全部调用入参取并集执行一次核心方法，再按元素归属拆分子 Map 回填各调用者；
- 返回非 `Map`：**同参折叠广播**——相同实参的并发调用合并执行一次并广播结果；
- 实参为空集合 / 折叠执行器 SPI（`collapse`）未引入：直接执行不折叠。

其它防护注解的拦截器（`com.chua.common.support.proxy.intercept.*`）通过 `MethodAnnotationIntercept` SPI 在 Invoker 代理场景自动发现：

| 注解 | 拦截器 | 默认行为（源码解析值） |
| --- | --- | --- |
| `@RateLimiter` | `RateLimiterIntercept` | `RateLimiterFlow` 门面；`permitsPerSecond` 默认 1、`warmupPeriod` 0、`waitTime` 0 |
| `@CircuitBreaker` | `CircuitBreakerIntercept` | `CircuitBreakerFlow` 门面；`failureThreshold` 默认 5、`successThreshold` 默认 2、`waitDuration` 默认 60000（或 `recoveryTime`） |
| `@Bulkhead` | `BulkheadIntercept` | `BulkheadFlow` 门面；`maxConcurrent` 默认 10、`fair` |
| `@Timeout` | `TimeoutIntercept` | `TimeoutFlow` 门面；`timeout` 默认 3000ms，超时降级 |
| `@Cacheable` / `@Transactional` | `CacheableIntercept` / `TransactionalIntercept` | 占位直通（直接 `proceed()`；`@Transactional` 会检测 `TransactionSynchronizationManager.isActualTransactionActive()`），使 Invoker 代理链路可感知注解存在 |

上述注解的 Spring AOP Advisor（`com.chua.spring.support.aop.*`）已提供，可按需自行注册为 Bean。

---

## 4. 配置项

本模块**无内置配置项**。

依据：模块内不存在 `@ConfigurationProperties`、`@Value` 属性注入，也没有任何配置前缀；`DateConvertConfiguration` / `FormatterConfiguration` 的自定义日期格式只能通过带 pattern 的构造器显式传入，源码未提供属性解析入口。

运行期读取的环境键（非本模块配置项，语义见 `SpringBeanUtils`）：

| 键 | 用途 | 默认值 |
| --- | --- | --- |
| `server.port` | `SpringBeanUtils.getPort()` | `8080` |
| `server.servlet.context-path` | `SpringBeanUtils.getContextPath()` | `/` |

`@Collapsible` 的全局默认键（`Collapsible` 注解的 Javadoc 声明，Spring Boot 场景）：

| 键 | 说明 |
| --- | --- |
| `collapse.executor.wait-threshold` | `waitThreshold` 未显式指定（`-1`）时生效的全局默认；未配置全局时使用内置默认 10 |
| `collapse.executor.collecting-wait-time` | `collectingWaitTime` 未显式指定（`-2`）时生效的全局默认；未配置全局时使用内置默认 0 |

> 这两个键由 Spring Boot 自动装配注入 `CollapseConfig`（`CollapsibleIntercept(CollapseConfig)` 构造器接收），本模块源码本身不直接读取。

---

## 5. SPI 扩展点

### 5.1 META-INF/extensions（SPI 注册文件）

`src/main/resources/META-INF/extensions/` 共 9 个文件：

| 文件 | 登记内容 |
| --- | --- |
| `com.chua.common.support.expression.ExpressionResolver` | `spel=com.chua.spring.support.expression.SpelExpressionResolver` |
| `com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser` | `spring=com.chua.spring.support.network.SpringServerHandlerAnnotationParser` |
| `com.chua.common.support.proxy.annotation.MethodAnnotationIntercept` | `DistributedLock` → `DistributedLockIntercept`；`RateLimiter` → `RateLimiterIntercept`；`Cacheable` → `CacheableIntercept`；`Transactional` → `TransactionalIntercept` |
| `com.chua.common.support.objects.resolver.BeanConstructorResolver` | `spring=...objects.resolver.impl.SpringBeanConstructorResolver` |
| `com.chua.common.support.objects.register.BeanDefinitionRegister` | `spring=...objects.register.impl.SpringBeanDefinitionRegister` |
| `com.chua.common.support.objects.inject.BeanDefinitionServiceInjector` | `spring=...objects.inject.impl.SpringBeanDefinitionServiceInjector` |
| `com.chua.common.support.objects.inject.BeanDefinitionMethodInjector` | `spring=...objects.inject.impl.SpringBeanDefinitionMethodInjector` |
| `com.chua.common.support.objects.inject.BeanDefinitionConfigInjector` | `spring=...objects.inject.impl.SpringBeanDefinitionConfigInjector` |
| `com.chua.common.support.objects.environment.ConfigSourceProvider` | `spring=...objects.environment.SpringConfigSourceProvider` |

### 5.2 META-INF/services（ServiceLoader 注册文件）

| 文件 | 登记内容 |
| --- | --- |
| `com.chua.common.support.utils.AnnotationDefinitionResolver` | `com.chua.spring.support.utils.SpringMvcAnnotationDefinitionResolver`（动态扫描 `org.springframework.web.bind.annotation` 包，把以 `@RequestMapping` 为元注解的窄注解建立 narrow → wide 别名映射，缓存用 `WeakHashMap` 防类加载器泄漏） |

### 5.3 spring.factories（Spring SPI）

`org.springframework.context.ApplicationContextInitializer` 注册两个实现：

| 实现 | 作用 |
| --- | --- |
| `ApplicationAwareApplicationContextInitializer` | 容器初始化时执行 `SpringBeanUtils.setApplicationContext(context)` |
| `ScriptProviderSpringRegistrar` | 把 `ScriptProviders` 的脚本引擎发现替换为 `SpringScriptProviderResolver`（基于 `SpringFactoriesLoader`），与 Spring SPI 机制保持一致 |

### 5.4 注解式 @Spi 声明

随 classpath 扫描注册（`@Spi` 注解，部分带 `@ConditionalOnClass` 条件）：

| 类 | 声明 | 说明 |
| --- | --- | --- |
| `CollapsibleIntercept` | `@Spi("com.chua.spring.support.annotation.Collapsible")`，`order=100` | 折叠拦截器；折叠执行器取自 SPI 名 `collapse`（需 `utils-support-collapse-starter`）；`DisposableBean` + `ApplicationContextAware`（注册全局降级容器） |
| `DistributedLockIntercept` | `@Spi("...lock.annotation.DistributedLock")` | `LockFlow.of(name)` 链式加锁执行 |
| `RateLimiterIntercept` | `@Spi("...rate.annotation.RateLimiter")` | 属性支持 SpEL / 占位符解析（继承 `AbstractMethodAnnotationIntercept`） |
| `CircuitBreakerIntercept` | `@Spi("...circuitbreaker.annotation.CircuitBreaker")` | 同上 |
| `BulkheadIntercept` | `@Spi("...bulkhead.annotation.Bulkhead")` | 同上 |
| `TimeoutIntercept` | `@Spi("...timeout.annotation.Timeout")` | 同上 |
| `CacheableIntercept` | `@Spi("org.springframework.cache.annotation.Cacheable")`，`order=200` | 占位直通 |
| `TransactionalIntercept` | `@Spi("org.springframework.transaction.annotation.Transactional")`，`order=200` | 占位直通（感知实际事务状态） |
| `SpelExpressionResolver` | `@Spi(value="spel", order=100)` + `@ConditionalOnClass(SpelExpressionParser)` | 解析 `#{...}`，支持 `@beanName` 引用容器 Bean；根对象绑定目标实例，调用方传入的上下文变量（如 `method` / `args` / `targetClass`）原样设为 SpEL 变量 |
| `SpelPlaceholderResolver` | `@Spi("spel")` + `@ConditionalOnClass(SpelExpressionParser)` | 占位符链中的 `#{...}` 求值（`@beanName` 引用） |
| `SpringServerHandlerAnnotationParser` | `@Spi("spring")` + `@ConditionalOnClass(RequestMapping)` | 解析 `@Controller` / `@RestController` 上方法级 `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` 并生成路由，供 common 内置 Server 使用 |
| objects 包桥接实现 | 各为 `@Spi("spring")` | 构造器解析（`@Autowired` / `@Qualifier`）、只读 Bean 定义注册器（委托 `ApplicationContext`）、字段/方法/配置（`@Value` + SpEL）注入器、配置源提供者 |

---

## 6. 构建与依赖

**POM 事实**（`pom.xml`）：

- 父 POM：`com.chua:utils-support-core-parent:4.0.0.51`
- Java：`java.version=25`、`maven.compiler.source/target=25`、`maven.compiler.parameters=true`、编码 `UTF-8`
- 编译依赖（compile）：`utils-support-common-starter`、`utils-support-network-starter`（`${project.version}`）
- 编译依赖（provided）：`spring-context`、`spring-context-support`、`spring-webmvc`、`spring-tx`、`jakarta.validation-api`、`jakarta.annotation-api`、`jakarta.servlet-api`

```
utils-support-spring-starter 4.0.0.51
├── utils-support-common-starter   (compile, 同版本)
├── utils-support-network-starter  (compile, 同版本)
├── spring-context / spring-context-support / spring-webmvc / spring-tx  (provided)
└── jakarta.validation-api / jakarta.annotation-api / jakarta.servlet-api   (provided)
```

- Spring 依赖均为 `provided`：本模块不传递 Spring 版本，功能按需生效——Spring 不在 classpath 时，`@ConditionalOnClass` / SPI 条件使适配类自动跳过。
- 构建：随 `utils-support-core-parent` 一起构建，在仓库根目录执行 `mvn -pl utils-support-core-parent/utils-support-spring-starter -am clean install`（或直接对全仓执行 `mvn clean install`）。
- `build/plugins` 为空，无专用构建插件。

---

## 7. 边界

| 领域 | 现状 |
| --- | --- |
| HTTP 客户端 / 文件 / AI / 存储 / 定时任务 / 数据库连接池 | 不在本模块，属 `utils-support-common-starter` 及对应 `*-starter` |
| 数据源 / Flyway / 网络 | 属于 `utils-support-datasource-starter`、`utils-support-flyway-starter`、`utils-support-network-starter` |
| Spring Boot 自动配置 | 在 `utils-support-springboot-starter`（本模块配置类 Javadoc 明示） |
| 锁 / 限流 / 熔断 / 舱壁 / 超时底层实现 | 拦截器只做门面适配，具体实现依赖 common 及对应 `*-starter` 的 SPI，未引入时相应注解不生效 |
| 请求折叠执行器 | 未引入 `utils-support-collapse-starter` 时，`@Collapsible` 记录警告并直调不折叠 |
| Request / Response 工具 | pom 描述文字曾提及，但当前源码中不存在对应实现类（以源码为准，未杜撰） |
| `registerController` / `unregisterController` | `@Deprecated(since="4.0.0.42", forRemoval=true)`，依赖 Spring 内部 API（`detectHandlerMethods` / `getMappingForMethod`） |
| 内置 Server 路由 | `SpringServerHandlerAnnotationParser` 仅解析 `@Controller` / `@RestController` 及 6 种映射注解，且需要 `UrlMappingServerFilter` 才能生效 |
| 脚本引擎 | 仅做 `SpringFactoriesLoader` 发现切换，脚本能力本身来自 common；非 Spring 场景回退默认 `ServiceProvider` 发现 |