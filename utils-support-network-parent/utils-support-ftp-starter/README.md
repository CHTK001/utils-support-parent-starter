# utils-support-ftp-starter

FTP 协议集成，基于 Apache Commons Net

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ftp-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `FtpClient` | FTP 链式客户端，基于 Apache Commons Net。 |
| `FtpPolledDirectory` | FTP 目录轮询实现，基于 Apache Commons Net。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ftp-starter
├── utils-support-common-starter
```