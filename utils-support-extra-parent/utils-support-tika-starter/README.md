# utils-support-tika-starter

基于 Apache Tika 的通用文档文本提取模块，支持 PDF、Word、Excel、HTML 等多种格式

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tika-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `TikaTextExtractor` | 基于 Apache Tika 的通用文本提取器 SPI 实现，支持从多种文档格式中提取纯文本内容。 (SPI: `tika`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-tika-starter
├── utils-support-common-starter
```