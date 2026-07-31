# utils-support-nacos-starter

Nacos 服务发现注册中心集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>utils-support-nacos-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `NacosClient` | Nacos 全功能链式客户端。 |
| `NacosConfigCenter` | Nacos 配置中心实现。 (SPI: `nacos`) |
| `NacosServiceDiscovery` | NacosServiceDiscovery (SPI: `nacos`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-nacos-starter
├── utils-support-common-starter
```