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
| ... | 共 23 个类 |

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