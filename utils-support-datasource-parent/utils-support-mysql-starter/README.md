# utils-support-mysql-starter

MySQL 数据库适配：用户管理、索引管理

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mysql-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MysqlEngine` | MySQL 数据库引擎。 继承自 ，提供 MySQL 特有的便捷数据源配置方法。 使用 HikariCP 连接池，默认最大连接数为 10。 使用示例： (SPI: `mysql`) |
| `MysqlCreateIndexStep` | MysqlCreateIndexStep |
| `MysqlDropIndexStep` | MysqlDropIndexStep |
| `MysqlIndexManager` | MysqlIndexManager |
| `MysqlMetaData` | MySQL 元数据入口。 通过 SPI 机制注册为 MySQL 引擎的元数据实现。 提供表、视图、索引、触发器、存储过程、外键的元数据操作能力。 (SPI: `mysql`) |
| `MysqlMetaForeignKey` | MysqlMetaForeignKey |
| `MysqlMetaIndex` | MysqlMetaIndex |
| `MysqlMetaProcedure` | MysqlMetaProcedure |
| `MysqlMetaTable` | MysqlMetaTable |
| `MysqlMetaTrigger` | MysqlMetaTrigger |
| ... | 共 14 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-mysql-starter
├── utils-support-datasource-starter
```