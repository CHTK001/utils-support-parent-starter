# utils-support-pdf-starter

PDF 文件操作模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-pdf-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `PdfDocumentRenderer` | PDF 文档导出器。 通过 渲染 HTML 后，调用 wkhtmltopdf 转 PDF。 (SPI: `pdf`) |
| `PdfTextExtractor` | PDF 文本提取器 SPI 实现，从 PDF 文档中提取纯文本内容。 基于 Apache PDFBox 实现，支持提取全部页面的文本。 (SPI: `pdf`) |
| `PdfFileSystem` | PDF 文件系统 SPI 实现。 (SPI: `pdf`) |
| `PdfReadBuilder` | PDF 文件读取构建器。 基于 PDFBox 实现 PDF 文档的文本提取。 |
| `PdfWriteBuilder` | PDF 文件写入构建器。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-pdf-starter
├── utils-support-common-starter
```
