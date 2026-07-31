# Utils Support Core Parent

核心基础父模块，提供 Java 项目最基础的工具能力和框架支持。

---

## 模块列表

| 模块 | 说明 | 必选 | 配置前缀 |
|------|------|------|----------|
| `utils-support-common-starter` | 核心基础库：类型转换、JSON、日期、加解密、HTTP 客户端、统一文件存储 (FileStorage SPI)、SPI 框架、文件工具、任务编排、事件分发、数据同步、IoC 容器等 | ★ | - |
| `utils-support-spring-starter` | Spring Framework 集成（不含 Boot） | | - |
| `utils-support-springboot-starter` | Spring Boot 自动装配 | | - |
| `utils-support-datasource-starter` | 数据源引擎（FileEngine/JdbcEngine） | | - |
| `utils-support-extension-starter` | 扩展功能模块 | | |
| `utils-support-spider-starter` | 爬虫框架 | | |
| `utils-support-example-starter` | 使用示例与最佳实践 | | |
| `utils-support-account-starter` | 账户管理 | | |
| `utils-support-appimage-starter` | 应用镜像 | | |
| `utils-support-ast-processor-starter` | AST 注解处理器 | | |
| `utils-support-doc-server-starter` | 文档服务器 | | |
| `utils-support-jsr-starter` | JSR 规范支持 | | |
| `utils-support-loki-starter` | Loki 日志 | | |
| `utils-support-maven-starter` | Maven 工具 | | |
| `utils-support-webview-starter` | WebView 支持 | | |

---

## 快速开始

所有 Java 项目必须引入 `common-starter`：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

如需 Spring Boot 自动装配：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-springboot-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 依赖关系

```
utils-support-core-parent/
├── common-starter              # ★ 核心基础（所有模块依赖）
├── spring-starter              # Spring Framework 集成
│   └── 依赖 common-starter
├── springboot-starter          # Spring Boot 自动装配
│   └── 依赖 spring-starter
├── datasource-starter          # 数据源引擎
│   └── 依赖 common-starter
├── extension-starter           # 扩展功能
├── spider-starter              # 爬虫框架
└── example-starter             # 使用示例
```

---

## 文档

| 文档 | 说明 |
|------|------|
| [common-starter 详细文档](utils-support-common-starter/README.md) | 工具类、JSON、HTTP、SPI、文件系统等完整 API |
| [统一文件存储 FileStorage SPI](utils-support-common-starter/src/main/java/com/chua/common/support/storage/README.md) | 文件存储抽象层 |
| [spring-starter 文档](utils-support-spring-starter/README.md) | Spring 集成配置 |
| [springboot-starter 文档](utils-support-springboot-starter/README.md) | Boot 自动装配配置 |
| [使用示例](utils-support-example-starter/README.md) | 最佳实践与示例代码 |
