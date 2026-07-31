# utils-support-json-starter

JSON 操作模块，提供 JSONPath 表达式解析与数据提取功能

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-json-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `JsonPathImpl` | JsonPath SPI 实现，基于 jayway JsonPath。 (SPI: `json`) |
| `JsonPathUtils` | JSONPath 工具类，基于 jayway JsonPath 提供 JSON 数据提取与操作功能。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-json-starter
├── utils-support-common-starter
```
