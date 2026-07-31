# utils-support-ofd-starter

OFD 版式文件系统模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ofd-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `OfdFileSystem` | OFD 版式文件系统 SPI 实现。 OFD (Open Format Document) 是国家版式文档标准， 支持 .ofd 文件的文本提取与基本读取。 (SPI: `ofd`) |
| `OfdReadBuilder` | OFD 文件读取构建器。 OFD 文件本质上是 ZIP 包，内含 XML 描述的版式内容。 本实现提取其中文档内容 XML 的纯文本部分。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ofd-starter
├── utils-support-common-starter
```
