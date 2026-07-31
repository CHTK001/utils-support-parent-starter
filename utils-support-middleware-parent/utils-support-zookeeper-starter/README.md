# utils-support-zookeeper-starter

ZooKeeper 服务发现注册中心集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.apache.curator</groupId>
    <artifactId>utils-support-zookeeper-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ZookeeperClient` | ZooKeeper 链式客户端，全功能封装 Curator。 |
| `ZookeeperConfigCenter` | ZooKeeper 配置中心实现。 (SPI: `zookeeper`) |
| `ZookeeperServiceDiscovery` | ZookeeperServiceDiscovery (SPI: `zookeeper`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-zookeeper-starter
├── utils-support-common-starter
```