# utils-support-rpc-dubbo-starter

Dubbo RPC 实现模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rpc-dubbo-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DubboRpcClient` | Dubbo RPC 客户端实现。 (SPI: `dubbo`) |
| `DubboRpcServer` | Dubbo RPC 服务端实现。 (SPI: `dubbo`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-rpc-dubbo-starter
├── utils-support-common-starter
```