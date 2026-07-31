# utils-support-hibernate-starter

Hibernate ORM DDL

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hibernate-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `HibernateDdlManager` | Hibernate DDL 管理器，通过 JDBC 元数据读取表结构， 并结合 方言体系生成数据库感知的 DDL 语句。 (SPI: `hibernate`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-hibernate-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```