# utils-support-duckdb-starter

DuckDB嵌入式OLAP数据库ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-duckdb-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DuckDBEngine` | DuckDB 引擎实现，基于内存数据过滤。 (SPI: `duckdb`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-duckdb-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```