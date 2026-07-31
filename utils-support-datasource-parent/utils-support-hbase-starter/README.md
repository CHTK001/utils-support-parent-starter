# utils-support-hbase-starter

HBase分布式数据库ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hbase-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `HBaseEngine` | HBase 数据库引擎实现，提供基于内存的数据过滤查询能力。 (SPI: `hbase`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-hbase-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```