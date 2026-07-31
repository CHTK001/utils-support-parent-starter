# utils-support-osgi-starter

Utils Support OSGI Starter - Apache Felix Framework

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-osgi-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ExampleBundleApplication` | OSGI Bundle 应用示例，演示如何在 Bundle 中注册服务。 |
| `FelixBundleContext` | Felix BundleContext 实现，包装 Felix 框架的 BundleContext。 |
| `FelixOsgiBundle` | Felix Bundle 实现，包装 OSGI 的 Bundle 对象。 |
| `FelixOsgiLauncher` | Felix OSGI 启动器实现。 启动后将自身注册到 作为全局唯一实例， 并发现所有 SPI 实现，回调传入 Bundle 上下文。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-osgi-starter
├── utils-support-common-starter
```