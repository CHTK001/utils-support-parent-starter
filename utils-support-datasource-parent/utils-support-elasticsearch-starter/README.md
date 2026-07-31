# utils-support-elasticsearch-starter

Elasticsearch搜索引擎ORM

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-elasticsearch-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ElasticsearchEngine` | Elasticsearch 搜索引擎实现。 (SPI: `elasticsearch`) |
| `EsDataSyncSource` | Elasticsearch 引擎的 DataSync OUTPUT Provider。 |
| `EsMeta` | Elasticsearch 元数据操作实现。 |
| `EsMetaData` | Elasticsearch 元数据入口。 |
| `EsSearchEngineImpl` | Elasticsearch 搜索引擎实现。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-elasticsearch-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
├── utils-support-datasync-agent-starter
```