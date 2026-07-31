# utils-support-ip2region-starter

IP2Region IP 数据库查询模块（自解析 xdb 格式，无外部依赖）

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ip2region-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `Ip2regionIpPosition` | Ip2region IP 地理位置查询 SPI 实现。 基于 ip2region xdb 数据库文件实现毫秒级 IP 定位。 (SPI: `ip2region`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ip2region-starter
├── utils-support-common-starter
├── utils-support-resource-ip2region
```
