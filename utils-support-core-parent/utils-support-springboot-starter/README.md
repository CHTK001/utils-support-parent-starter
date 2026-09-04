# utils-support-springboot-starter

Utils Support Spring Boot Starter - Spring Boot 自动装配模块

        基于 utils-support-spring-starter，提供：
        - 自动配置（AutoConfiguration）
        - ApplicationContext 注入
        - 日期转换 / Formatter 自动注册

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-springboot-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `UtilsSpringBootAutoConfiguration` | Utils Spring Boot 自动配置。 导入 （日期转换 / Formatter）， 并保证 持有当前 。 |
| `CollapseAutoConfiguration` | 折叠自动配置：总开关 `collapse.executor.enabled`（缺省启用），装配 `CollapsibleAdvisor` / `CollapsibleIntercept` |
| `CollapseProperties` | 折叠全局默认配置（`collapse.executor.*`），经自动配置注入拦截器 |

---

## 请求折叠全局配置

折叠注解（`@Collapsible`）的 AOP 支持由本模块**自动装配**（无需手动 `@Import`）：

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `collapse.executor.enabled` | `true` | 折叠自动装配总开关 |
| `collapse.executor.wait-threshold` | `10` | 全局默认收集阈值（注解未指定时生效） |
| `collapse.executor.collecting-wait-time` | `0` | 全局默认补收等待毫秒（注解未指定时生效） |

```yaml
collapse:
  executor:
    enabled: true
    wait-threshold: 4
    collecting-wait-time: 10
```

依赖说明：折叠注解在 `utils-support-spring-starter`，默认执行器实现在 `utils-support-collapse-starter`（SPI 自动发现），两者均需引入。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-springboot-starter
├── utils-support-spring-starter
```