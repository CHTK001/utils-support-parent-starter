# utils-support-apollo-starter

Apollo 配置中心服务发现集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.ctrip.framework.apollo</groupId>
    <artifactId>utils-support-apollo-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ApolloClient` | Apollo 全功能链式客户端。 |
| `ApolloConfigCenter` | Apollo 配置中心实现。 (SPI: `apollo`) |
| `ApolloServiceDiscovery` | Apollo 服务发现实现类。 (SPI: `apollo`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-apollo-starter
├── utils-support-common-starter
```