# utils-support-oracle-starter

Oracle数据库ORM+CDC

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-oracle-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `OracleDispatcherProvider` | Oracle CDC 分发器提供者，模拟 Oracle LogMiner 变更数据捕获事件流。 (SPI: `oracle`) |
| `OracleCreateIndexStep` | Oracle 创建索引链式步骤实现。 语法： `CREATE [UNIQUE] [BITMAP] INDEX 索引名 ON 表名 (列名)`，缺省普通 B 树索引，位图索引需显式索取。 |
| `OracleDropIndexStep` | Oracle 删除索引链式步骤实现。 语法： Oracle 中 DROP INDEX 不需要指定表名。 |
| `OracleIndexManager` | Oracle 索引管理器 SPI 实现。 标识符统一走 `SqlName` 白名单校验，JDBC 失败抛出 `IllegalStateException`。 |
| `OracleAlterUserStep` | Oracle 修改用户链式步骤实现。 支持修改密码、授予权限和回收权限： |
| `OracleCreateUserStep` | Oracle 创建用户链式步骤实现。 Oracle 语法： 创建后自动授予 CONNECT 角色。 |
| `OracleUserManager` | Oracle 用户管理器 SPI 实现。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-oracle-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```