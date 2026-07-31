# utils-support-word-starter

Word 文件操作模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-word-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `WordDocumentRenderer` | Word 文档导出器。 先通过 渲染 Markdown，再写入 Word 段落/表格结构， 保证与 DEFAULT / SWAGGER 模板内容一致。 (SPI: `word`) |
| `WordTextExtractor` | Word 文本提取器 SPI 实现，从 Word 文档中提取纯文本内容。 基于 Apache POI 实现，支持 .docx 格式的段落文本提取。 |
| `WordFileSystem` | Word（.docx）文件系统 SPI 实现。 基于 Apache POI 库实现 Word 文档的文本提取与文本写入。 使用示例 |
| `WordReadBuilder` | Word 文件读取构建器。 基于 Apache POI 实现 .docx 文档的文本内容提取。 |
| `WordWriteBuilder` | Word 文件写入构建器。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-word-starter
├── utils-support-common-starter
```
