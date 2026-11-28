# utils-support-runtime-parent

JVM 运行时工具、插桩 Agent、APM 与交互命令的 Maven 聚合模块。版本 `4.0.0.51`，使用 JDK 25；父 POM 自身没有 Agent 入口。

## 模块目录

| 模块 README | 职责 |
|---|---|
| [runtime-core](utils-support-runtime-core/README.md) | 运行时接口、管理器、软件/服务模型 |
| [runtime-plugin](utils-support-runtime-plugin/README.md) | 插件 SPI、扫描/加载及插桩点 |
| [runtime-spy](utils-support-runtime-spy/README.md) | ASM 插桩与 Instrumentation 引擎 |
| [runtime-protocol](utils-support-runtime-protocol/README.md) | Endpoint、Span、Trace、传输记录与协议枚举 |
| [runtime-apm](utils-support-runtime-apm/README.md) | HTTP/数据库/文件/日志等处理器、本地存储和 hotspot 查询 |
| [runtime-agent](utils-support-runtime-agent/README.md) | premain/agentmain、Agent 打包与引导 |
| [runtime-shell](utils-support-runtime-shell/README.md) | Telnet Shell、命令系统与终端输出 |
| [runtime-starter](utils-support-runtime-starter/README.md) | 软件下载/启停、服务管理与 Agent 注入入口 |

## 安装与选择

嵌入式管理代码使用 starter；JVM 启动参数或动态 attach 使用 agent 产物，两者安装方式不同：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

Agent 参数、输出位置和 attach 权限见 [agent README](utils-support-runtime-agent/README.md)；APM 查询/存储见 [APM README](utils-support-runtime-apm/README.md)。只将 agent jar 放在 classpath 不会自动向当前 JVM 注入。Shell 和远程管理端点的监听/认证按对应文档配置。

仓库根构建：`mvn -pl utils-support-runtime-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
