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

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-springboot-starter
├── utils-support-spring-starter
```