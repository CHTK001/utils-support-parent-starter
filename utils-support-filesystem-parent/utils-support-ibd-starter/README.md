# utils-support-ibd-starter

智能业务数据模块：Python 脚本执行、数据处理、自动解压资源

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ibd-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `IbdToSqlFileConvertSystem` | IBD 到 SQL 转换器。 调用 Python ibd2sql 工具解析 MySQL InnoDB 数据文件 (.ibd) 并生成 SQL 脚本。 (SPI: `ibd2sql`) |
| `IbdManager` | IBD（智能业务数据）管理器 统一管理 IBD 资源的解压、脚本执行和数据处理。 启动时自动从 JAR 中解压内置资源到工作目录。 使用示例 |
| `PythonScriptExecutor` | Python 脚本执行器 通过系统 Python 解释器执行 Python 脚本。 支持传入上下文参数，脚本通过命令行参数或环境变量获取。 |
| `ScriptExecutor` | 脚本执行器接口 定义脚本执行的能力，支持多种脚本语言（Python/Groovy/JS 等）。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ibd-starter
├── utils-support-common-starter
```
