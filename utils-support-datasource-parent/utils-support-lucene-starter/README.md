# utils-support-lucene-starter

Lucene全文搜索ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-lucene-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `EntityDocumentConverter` | 实体对象与 Lucene Document 之间的转换器。 |
| `LuceneDataSyncSource` | Lucene 引擎的 DataSync OUTPUT Provider。 将 &lt;Map&gt; 批量写入 Lucene 索引。 |
| `LuceneEngine` | 基于 Apache Lucene 的内存搜索引擎。 (SPI: `lucene`) |
| `LuceneFields` | Lucene 字段名常量。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-lucene-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
├── utils-support-datasync-agent-starter
```