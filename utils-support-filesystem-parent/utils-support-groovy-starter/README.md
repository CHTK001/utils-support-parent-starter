# utils-support-groovy-starter

Groovy 脚本引擎模块，提供动态脚本执行和 DSL 支持功能。
        主要功能：
        - 🔧 脚本执行：动态执行 Groovy 脚本代码
        - 📝 DSL 支持：创建领域特定语言和配置脚本
        - 🔄 热加载：运行时动态加载和执行脚本
        - 📊 变量绑定：Java 对象与 Groovy 脚本的双向绑定
        - 🎯 模板引擎：基于 Groovy 的模板生成功能
        - 🛠️ 工具集成：与 Java 生态系统无缝集成
        - 📈 性能优化：脚本编译缓存和执行优化
        - 🔍 调试支持：脚本调试和错误追踪
        适用场景：
        - 规则引擎开发
        - 配置脚本管理
        - 动态业务逻辑
        - 模板代码生成
        - 自动化测试脚本
        - 系统配置管理

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.apache.groovy</groupId>
    <artifactId>utils-support-groovy-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `Hello` | Groovy 动态编译器实现。 (SPI: `groovy`) |
| `Hello` | Groovy 脚本标记器实现。 (SPI: `groovy`) |
| `GroovyScriptProvider` | Groovy 脚本提供者实现。 (SPI: `groovy`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-groovy-starter
├── utils-support-common-starter
```
