# utils-support-dbf-starter

DBF (dBASE) 文件操作模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-dbf-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DbfDataTable` | DBF 数据表实现，支持 dBASE 格式文件的读写和 CRUD 操作。 基于 javadbf 库实现。数据在内存中维护， 通过 写出到文件。 |
| `DbfFileSystem` | DBF（dBASE）文件系统 SPI 实现。 (SPI: `dbf`) |
| `DbfReadBuilder` | DbfReadBuilder |
| `DbfWriteBuilder` | DbfWriteBuilder |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-dbf-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```
