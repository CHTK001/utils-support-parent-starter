# utils-support-neo4j-starter

Neo4j图数据库ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-neo4j-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `Neo4jEngine` | Neo4j 图数据库引擎实现，通过 Bolt 协议连接 Neo4j 执行 Cypher 查询。 (SPI: `neo4j`) |
| `Neo4jEngineDataSource` | Neo4j 引擎数据源实现，包装 Neo4j Bolt 驱动实例。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-neo4j-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```