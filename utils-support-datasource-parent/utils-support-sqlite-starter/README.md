# utils-support-sqlite-starter

SQLite 数据库支持模块，提供 SqliteDialect 和 SqlitePolledDirectory

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sqlite-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SqlitePolledDirectory` | SQLite 数据库轮询目录实现，基于 JDBC 查询的快照对比机制。 |
| `SqliteEngine` | SQLite 嵌入式数据库引擎。 (SPI: `sqlite`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-sqlite-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```