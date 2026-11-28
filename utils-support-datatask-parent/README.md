# utils-support-datatask-parent

任务执行、数据同步、搜索聚合与行为分析的 Maven 聚合模块，版本 `4.0.0.51`，使用 JDK 25。应用安装具体 jar；父 POM 自身不启动调度器或采集服务。

## 模块目录

| 模块 README | 主要能力与入口 |
|---|---|
| [retry](utils-support-retry-starter/README.md) | Guava Retryer 实现的重试 SPI |
| [collapse](utils-support-collapse-starter/README.md) | 虚拟线程请求折叠与批量执行 |
| [quartz](utils-support-quartz-starter/README.md) | Quartz 调度 SPI |
| [datasync-agent](utils-support-datasync-agent-starter/README.md) | Agent Source/Sink、同步传输及接入端 |
| [datasync](utils-support-datasync-starter/README.md) | 服务端 Mapping、执行器、调度和 Agent 管理 |
| [datasearch](utils-support-datasearch-starter/README.md) | 音乐/视频/Web 搜索、目录提供器、MCP/技能、用量和模型目录 |
| [datarecovery](utils-support-datarecovery-starter/README.md) | Rust JNI 数据恢复装配，需平台原生库 |
| [ueba](utils-support-ueba-starter/README.md) | IP 异常、行为序列与语义解释管线 |

## 安装与选择

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-collapse-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

单进程批处理参见 collapse 的分组/批量执行示例；跨节点同步按 datasync 服务端与 agent 两侧配置，并选择相同传输协议。数据湖管线、Sink 与订阅属于 [datalake](../utils-support-datalake-parent/README.md)，不由 datasync 代替。

datasearch 的外部网站/API 各有真实可用性限制；UEBA 推理需要其 README 指定的模型，datarecovery 需要原生运行时，安装依赖不能代替准备这些资源。

仓库根构建：`mvn -pl utils-support-datatask-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
