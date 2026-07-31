# utils-support-calcite-starter

utils-support-calcite-starter module

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-calcite-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `CalciteDataSourceConversion` | Calcite数据源转换器实现类。 (SPI: `CALCITE`) |
| `CalciteDataScheme` | Calcite 数据方案实现类。 用于封装 Calcite 的数据集结构，包含方案名称和多个数据表。 |
| `CalciteDataSourceCreator` | Calcite 数据源创建器，使用 Calcite 将多个数据源聚合为一个统一的 。 (SPI: `calcite`) |
| `CalciteDataTable` | Calcite 数据表实现类。 用于封装 Calcite 查询结果或构建内存中的虚拟表结构。 |
| `CalciteDataTableAdapter` | Calcite 数据表适配器，将 适配为 Calcite 的 。 |
| `EngineAwareDataSource` | 包装统一 Calcite ：拦截简单 UPDATE 并路由到 Engine。 |
| `EngineDataSchema` | 引擎数据方案实现类，将 实例适配为 。 每个注册的实体类自动映射为一张 ， 底层数据由 Engine 提供的 Lambda 查询能力驱动。 |
| `EngineDataSourceFactory` | Engine / FileEngine → Calcite 统一 工厂。 |
| `EngineUpdateSqlExecutor` | 将简单 SQL UPDATE 路由到 （Calcite ModifiableTable 不支持 UPDATE）。 |
| `SourceDataTable` | 将 实体表适配为可查询/可写的 。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-calcite-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```