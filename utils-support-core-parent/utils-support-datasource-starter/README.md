# utils-support-datasource-starter

数据源核心引擎：AbstractEngine、JdbcEngine、MemoryWhereParser、Lambda 包装器

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasource-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SymmetricConnectorConfig` | SymmetricDS 数据库连接器配置 SPI 接口。 |
| `SymmetricEnvironmentSetup` | SymmetricDS 数据库环境自动配置 SPI 接口。 |
| `DataScheme` | 数据方案接口，对应数据库中的一个库（Schema）。 |
| `DataSourceCreator` | 数据源创建器接口。 统一管理多种类型的数据源（JDBC DataSource、DataScheme 等）， 通过 SPI 机制可以注册不同的实现（如 Calci |
| `DataTable` | 数据表接口，对应数据库中的一张表。 |
| `MutableDataTable` | 可变数据表实现。 |
| `DslManager` | DDL / DSL 管理器 SPI 接口。 提供表结构读取与 DDL 语句生成能力。 |
| `AbstractDialect` | 方言抽象基类。 提供默认的分页 SQL 生成（LIMIT/OFFSET 语法）和默认类型映射。 |
| `ClickHouse20Dialect` | ClickHouse20Dialect |
| `ClickHouseDialect` | ClickHouseDialect |
| ... | 共 62 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-datasource-starter
├── utils-support-common-starter
```