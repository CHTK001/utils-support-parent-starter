# utils-support-spring-starter

Utils Support Spring Starter - Spring 框架集成模块

        提供 Spring 环境下的工具支持：
        - Spring 环境配置注入（SpringBeanUtils / ApplicationContextInitializer）
        - 日期时间 ConversionService / Formatter 配置
        - 代理注解拦截（@DistributedLock / @RateLimiter）
        - Spring MVC 地址解析 SPI
        - Request/Response 工具

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-spring-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ApplicationAwareApplicationContextInitializer` | 应用上下文感知初始化器，在 Spring 容器初始化时设置 ApplicationContext 到 SpringBeanUtils @version 1.0. |
| `SpringBeanUtils` | Spring Bean 工具类，提供 ApplicationContext 持有、Bean 获取注册、类型转换等功能。 |
| `UtilsSpringConfiguration` | Spring 集成总配置：导入日期转换与 MVC Formatter。 纯 Spring 环境可通过 启用； Spring Boot 环境由 自动装配。 |
| `DateConvertConfiguration` | 日期时间转换器配置类 自动注册以下转换器到 Spring 的 ConversionService： String ↔ LocalDate String ↔ Lo |
| `DateToLocalDateConverter` | Date 转 LocalDate 转换器 将 转换为 |
| `DateToLocalDateTimeConverter` | Date 转 LocalDateTime 转换器 将 转换为 |
| `FormatterConfiguration` | 日期时间格式化器配置类 自动注册以下格式化器到 Spring MVC： LocalDateFormatter LocalDateTimeFormatter Lo |
| `LocalDateFormatter` | LocalDate 格式化器 用于 Spring MVC 参数绑定，支持 类型的自动格式化与解析 |
| `LocalDateTimeFormatter` | LocalDateTime 格式化器 用于 Spring MVC 参数绑定，支持 类型的自动格式化与解析 |
| `LocalDateTimeToDateConverter` | LocalDateTime 转 Date 转换器 将 转换为 |
| `@Collapsible` | 请求折叠注解：窗口内并发调用合并为一次核心执行，结果按归属拆分回填（v2 语义，仅支持恰好 1 个 Collection 入参） |
| `CollapsibleAdvisor` / `CollapsibleIntercept` | 折叠 AOP 实现：注解解析、执行器 SPI 获取、key() SpEL 归约、fallback 降级、全局默认三级解析 |
| `FallbackResolver` | 降级方法解析：`beanName#methodName` 跨 Bean 统一降级 / 纯方法名同类降级 |
| ... | 共 23 个类 |

---

## 请求折叠（@Collapsible）

基于 collapse-executor 思想的请求折叠：将并发窗口内的相同调用合并为一次核心执行，显著降低下游压力（如热点数据查询、批量接口扇出）。

### 1. 添加实现依赖

注解与 AOP 在本模块，默认执行器实现需另引（SPI 自动发现）：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-collapse-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用示例

```java
@Component
public class UserService {

    /**
     * 并发调用在窗口内合并为一次核心执行，子结果按 key 精确回填
     */
    @Collapsible(name = "batch-find", waitThreshold = 4, collectingWaitTime = 10)
    public Map<Long, User> batchFind(List<Long> ids) {
        // 一次真实调用：批量查询
    }
}
```

调用方无需任何改动，并发调用自动折叠：

```java
Map<Long, User> a = userService.batchFind(List.of(1L, 2L)); // 可能与 b、c 合并执行
Map<Long, User> b = userService.batchFind(List.of(3L));
Map<Long, User> c = userService.batchFind(List.of(4L, 5L)); // 窗口内到达，合并为一次
```

### 3. 注解属性

| 属性 | 说明 |
|------|------|
| `name` | 执行器名；默认取目标类全限定名.方法名（多实现 Bean 天然按实现类隔离，不会互相合并） |
| `key` | 归约 key SpEL（可选）：按元素求值（如 `"id"`、Map 元素用 `"['id']"`），同 key 元素去重合并；缺省以元素自身为 key |
| `fallback` | 降级引用（可选）：`beanName#methodName` 跨 Bean 统一降级，或纯方法名取同类方法；批量失败按调用方各自降级 |
| `waitThreshold` | 收集阈值：累计达阈值立即执行；`-1`（缺省哨兵）→ 全局默认 → 内置默认 10 |
| `collectingWaitTime` | 补收等待毫秒：等待窗口内继续收集；`-2`（缺省哨兵）→ 全局默认 → 内置默认 0 |

### 4. 返回类型语义（v2）

- 返回 `Map`：**合并拆分**模式 —— 窗口内全部调用入参并集执行一次，结果按 key 归属拆分回填；
- 返回非 `Map`：**同参折叠广播**模式 —— 仅相同入参的调用合并，结果广播；
- 空集合 / SPI 无实现：直调透传。

### 5. 降级与熔断组合

```java
@Collapsible(name = "query", fallback = "fallbackService#emptyMap")
@CircuitBreaker(name = "query-cb", fallback = "fallbackService#emptyMap")
public Map<Long, User> query(List<Long> ids) {
    // 折叠窗口内合并执行；折叠失败按调用方降级；熔断计数在折叠之上生效
}
```

### 6. 注意事项

- 折叠是**尽力合并**：合并与否取决于并发到达时序，不保证固定窗口；
- `@Collapsible` 仅对 Spring Bean 的 public 方法生效（AOP 约束，private/final/static 不生效）；
- 核心方法只接受**恰好 1 个 `Collection` 入参**，否则抛 `IllegalStateException`；
- 不同 Bean 显式设置相同 `name` 且并发混批时抛 `IllegalStateException`（防静默错误结果）；
- key 提取基于元素对象，复杂元素类型请写对应 SpEL。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-spring-starter
├── utils-support-common-starter
├── utils-support-network-starter
```