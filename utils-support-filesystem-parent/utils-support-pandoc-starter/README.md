# utils-support-pandoc-starter

基于 Pandoc 的通用文档格式转换模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-pandoc-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `PandocEnvironment` | Pandoc 环境检测与自动安装工具 检测当前系统中是否安装了 Pandoc，若未安装则根据操作系统自动下载并安装。 |
| `PandocFileConvertSystem` | Pandoc 通用文档格式转换器。 (SPI: `pandoc`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-pandoc-starter
├── utils-support-common-starter
```
