# utils-support-symmetric-starter

SymmetricDS 数据库同步集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-symmetric-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SymmetricPolledDirectory` | SymmetricDS CDC 实现 - 基于触发器的实时数据变更捕获。 |
| `SymmetricEnvironment` | SymmetricDS 环境配置构造器。 提供类型安全的 Builder API，快速创建各数据库的 SymmetricDS 同步环境配置。 使用示例： |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-symmetric-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```