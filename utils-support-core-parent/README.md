# utils-support-core-parent

核心契约与实现的 Maven 聚合模块。版本 `4.0.0.51`，编译使用 JDK 25；父模块的 `packaging=pom`，应用按下表选择实际 jar，不把父 POM 当作运行库安装。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [common](utils-support-common-starter/README.md) | SPI、HTTP、JSON、文件存储、缓存、任务、AI 客户端与网络等共享契约及基础实现 |
| [datasource](utils-support-datasource-starter/README.md) | JDBC/内存引擎、查询包装器、分页、方言与元数据 |
| [network](utils-support-network-starter/README.md) | Vert.x HTTP/WebSocket 实现与网络过滤器 |
| [filesystem](utils-support-filesystem-starter/README.md) | CSV、JSON、XML、文本、YAML、ZIP 读写和文件搜索 |
| [deeplearning](utils-support-deeplearning-starter/README.md) | 模型注册/下载/加载、推理能力接口、OCR 等管线 |
| [crawler](utils-support-crawler-starter/README.md) | 可插拔 Fetcher/Parser、请求/响应与抓取数据模型 |
| [payment](utils-support-payment-starter/README.md) | 支付渠道 SPI、统一支付请求与响应 |
| [flow](utils-support-flow-starter/README.md) | 流程引擎、节点注册和 Spring Boot 装配 |
| [flyway](utils-support-flyway-starter/README.md) | 基于 DataSource 的版本化脚本迁移、checksum 与历史记录 |
| [flyway Maven plugin](utils-support-flyway-maven-plugin/README.md) | 构建期合并初始化 SQL；这是 Maven 插件，不是迁移运行库 |
| [maven](utils-support-maven-starter/README.md) | Maven 构建客户端、同步/异步构建、目标/Profile/JDK 配置 |
| [spring](utils-support-spring-starter/README.md) | Spring 上下文、配置注入、日期转换与 Web 工具 |
| [springboot](utils-support-springboot-starter/README.md) | Spring Boot 自动装配、对象上下文和 API 配置 |
| [osgi](utils-support-osgi-starter/README.md) | Felix 框架、bundle 管理、动态服务与 SPI 桥 |
| [osgi-bundle](utils-support-osgi-bundle-starter/README.md) | 仅依赖 JDK 的插件生命周期及宿主契约 |
| [otp](utils-support-otp-starter/README.md) | 一次性密码消息推送及记录；目录存在，未登记到本父 POM，默认 reactor 不构建 |

## 安装与使用

基础工具可直接依赖 common；需要数据库、网络或推理时，再选择对应实现。实现模块的传递依赖已包含的 common 无需重复声明。

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

从 [common README](utils-support-common-starter/README.md) 查找 SPI/HTTP/JSON 等实际用法；数据库接入见 [datasource](utils-support-datasource-starter/README.md) 与 [数据库实现目录](../utils-support-datasource-parent/README.md)。Spring 集成只在使用 Spring 的宿主中添加，普通 Java 程序不需要添加桥接模块。

## 构建

在仓库根执行 `mvn -pl utils-support-core-parent -am compile -DskipTests`。离线仓库已备齐时可加 `-o`。`otp` 未参与此 reactor，按其 README 独立构建；本目录不包含独立业务服务。

返回 [仓库完整目录](../README.md)。
