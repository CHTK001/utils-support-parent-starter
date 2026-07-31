# utils-support-tablesaw-starter

Tablesaw 数据引擎模块，基于 Tablesaw 数据处理引擎提供文件数据源的 ORM 查询能力。
        支持 CSV 文件加载，通过 LambdaQueryWrapper / LambdaUpdateWrapper 提供标准的 Engine ORM 接口。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tablesaw-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `TablesawEngine` | Tablesaw 数据引擎实现，基于 Tablesaw 数据处理引擎提供文件数据源的 ORM 查询能力。 (SPI: `tablesaw`) |
| `BuiltinEndpointFilter` | 内置端点过滤器。 拦截 /health 和 /metrics 请求路径，返回 JSON 格式的服务器运行时信息。 仅支持 HTTP 协议，非 HTTP 协议自动 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-tablesaw-starter
├── utils-support-common-starter
```