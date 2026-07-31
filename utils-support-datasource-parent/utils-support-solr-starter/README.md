# utils-support-solr-starter

Solr搜索引擎ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-solr-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SolrDataSyncSource` | Solr 引擎的 DataSync OUTPUT Provider。 将 &lt;Map&gt; 批量写入 Solr Collection。 |
| `SolrEngine` | SolrEngine (SPI: `solr`) |
| `SolrFields` | Solr 字段名常量。 |
| `SolrMeta` | Solr 元数据操作实现。 |
| `SolrMetaData` | Solr 元数据入口。 |
| `SolrSearchEngine` | Solr 搜索引擎实现。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-solr-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
├── utils-support-datasync-agent-starter
```