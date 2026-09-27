# utils-support-datarecovery-starter

Data recovery native FFI (Rust JNI)

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datarecovery-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DataRecovery` | DataRecovery |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-datarecovery-starter
├── utils-support-common-starter
├── utils-support-native-datarecovery
```

---

## 原生库与构建

Rust 源码与 JNI 绑定 `DataRecovery` 均由 `utils-support-native-datarecovery` 提供：

- 源码 / 构建脚本：`utils-support-native-datarecovery/src/main/rust/build.sh`
- 四平台产物：`utils-support-native-datarecovery/src/main/resources/native/`

本模块不再包含任何原生源码。