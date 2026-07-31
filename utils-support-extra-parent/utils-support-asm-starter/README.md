# utils-support-asm-starter

ASM 和 Javassist 代理实现模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-asm-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AsmBeanCopier` | ASM 字节码实现的 Bean 属性拷贝器。 (SPI: `asm`) |
| `AsmCompiler` | ASM 动态编译器实现 该实现通过 JDK 编译器 API 将 Java 源码编译为字节码，再使用 ASM 对字节码进行二次处理， 确保生成的字节码包含完整的  (SPI: `asm`) |
| `AsmProxyFactory` | ASM 代理工厂，基于 ASM 字节码框架直接生成代理类。 (SPI: `asm`) |
| `JavassistProxyFactory` | Javassist 代理工厂，基于 Javassist 字节码增强技术创建类代理。 (SPI: `javassist`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-asm-starter
├── utils-support-common-starter
```