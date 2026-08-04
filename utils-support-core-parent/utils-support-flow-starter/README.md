# utils-support-flow-starter

流程编排（Flow Orchestration）引擎，基于 `utils-support-common-starter` 的 `task/flow` 契约实现，复用 `task/pipeline` 执行内核，提供面向 Spring Boot 的编排能力（流程定义持久化、执行、导入导出、REST 接口）。

> 依赖契约（接口/模型/节点注册表）位于 `utils-support-common-starter` 的 `com.chua.common.support.task.flow` 包，本模块为 L2 实现层。

## 特性

- **链式 DSL**：`addNext` 显式声明节点连线，天然对应前端图编辑器模型
- **同一上下文**：`FlowInstance` 承载唯一执行上下文，每次 `run(params)` 传入新参数、上下文不重建，支持跨节点共享数据、多次运行累积状态
- **JSON 导入导出**：流程定义以统一图格式序列化（`nodes` + `edges`），与前端 ReFlow 画布双向互通
- **节点类型注册表**：`@FlowNode` + SPI 加载节点类型，领域模块（如 spider-starter）自注册节点，核心零改动
- **分支编排**：`condition` 节点支持 true/false 二分支（复用 `DecisionNode`）
- **断点续跑**：`FlowInstance` 状态可持久化，挂起实例可恢复执行
- **Spring Boot 自动装配**：`FlowEngine` Bean、流程 CRUD/执行 Controller 开箱即用

## 模块依赖

```
utils-support-flow-starter
├── utils-support-common-starter      # Flow 契约 + task/pipeline 执行内核 + JSON 工具
└── spring-boot-starter               # autoconfigure / web
```

可选：依赖 `utils-support-spider-starter` 时，自动注册 `spider` 节点类型。

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-flow-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 代码方式编排（DSL）

```java
// 从 FlowEngine 获取已注册的节点工厂，构建流程定义
Flow flow = FlowEngine.createFlow("demo")
    .addNode("start", "start")
    .addNext("start", "fetch")
    .addNode("fetch", "spider", FlowProps.of(
        "urls", List.of("https://example.com"),
        "threads", 2))
    .addNext("fetch", "check")
    .addNode("check", "condition", ctx -> !ctx.getCurrentData().isEmpty())
    .when(true, "persist")
    .when(false, "end")
    .addNext("check", "persist")
    .addNode("persist", "httpCall", FlowProps.of(
        "url", "https://api.example.com/save"))
    .addNext("persist", "end")
    .addNode("end", "end")
    .build();
```

### 3. 执行

```java
// 每次运行传入参数，运行必为同一上下文
FlowInstance instance = flow.createInstance();
instance.run(Map.of("bizId", "1"));     // 首次运行
instance.run(Map.of("bizId", "2"));     // 同一上下文续跑（从 WAIT 点或重新调度）
```

### 4. JSON 导入导出

```java
// 导出：流程定义 -> JSON
String json = flow.exportJson();

// 导入：JSON -> 可执行 Flow
Flow flow = FlowEngine.parseJson(json);
```

## 统一图格式（FlowDefinition）

前后端共用的流程定义 JSON，字段说明如下：

```json
{
  "id": "flow1",
  "name": "审批流程",
  "nodes": [
    { "id": "start",  "type": "start",     "props": {},                 "x": 100, "y": 100 },
    { "id": "fetch",  "type": "spider",    "props": { "urls": ["https://example.com"], "threads": 2 }, "x": 300, "y": 100 },
    { "id": "check",  "type": "condition", "props": {},                 "x": 500, "y": 100 },
    { "id": "end",    "type": "end",       "props": {},                 "x": 700, "y": 100 }
  ],
  "edges": [
    { "from": "start", "to": "fetch", "label": "" },
    { "from": "fetch", "to": "check", "label": "" },
    { "from": "check", "to": "end",   "label": "true" },
    { "from": "check", "to": "end",   "label": "false" }
  ]
}
```

- `nodes[].type` 对应节点类型注册表中的类型名，`props` 为节点配置参数
- `nodes[].x/y` 为前端画布坐标，后端执行忽略
- `edges[].label`：空表示顺序边；`true`/`false` 为 condition 分支标签
- 前端 ReFlow 导出与此格式完全一致，后端可直接导入执行

## 内置节点类型

| 类型 | 说明 | 关键 props |
|------|------|-----------|
| `start` | 起始节点 | - |
| `end` | 终止节点 | - |
| `condition` | 条件分支（true/false） | 判断逻辑由节点工厂实现注入 |
| `httpCall` | HTTP 调用 | `url`, `method`, `headers`, `body` |
| `log` | 日志输出 | `message` |
| `transform` | 数据转换 | `expression`（SPEL） |
| `spider` | 爬虫抓取（需依赖 spider-starter） | `urls`, `threads`, `maxDepth`, `maxPages` |

## 扩展：注册自定义节点类型

领域模块只需依赖 `utils-support-common-starter`，实现 `FlowNodeExecutor` 并用 `@FlowNode` 标注即可被编排，无需改动 flow-starter 核心：

```java
@FlowNode("spider")
public class SpiderFlowNode implements FlowNodeExecutor {

    @Override
    public void execute(FlowInstance instance) {
        FlowProps props = instance.currentNodeProps();
        List<SpiderResult> results = Spider.create()
            .addUrl(props.getString("url"))
            .pipeline(r -> instance.setAttribute("spider.result", r))
            .runSync();
        instance.setCurrentData(results);   // 写入上下文，供下游节点消费
    }
}
```

注册后，前端 ReFlow 的属性面板可通过节点类型元信息（`FlowNodeMetadata`）自动渲染 props 表单。

## REST 接口（Spring Boot 自动装配）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/flow` | 创建/更新流程定义 |
| GET | `/flow/{id}` | 加载流程定义 |
| DELETE | `/flow/{id}` | 删除流程定义 |
| POST | `/flow/{id}/import` | 导入 JSON |
| GET | `/flow/{id}/export` | 导出 JSON |
| POST | `/flow/{id}/run` | 创建实例并运行（body 传参数） |
| POST | `/flow/instance/{instanceId}/resume` | 恢复挂起实例 |
| GET | `/flow/node/types` | 节点类型清单（供前端渲染面板） |

## 与前端 ReFlow 联调

前端 `packages/reflow` 基于 `@vue-flow/core` 实现可视化画布：

1. 画布操作生成 `FlowDefinition` 图数据
2. 调 `/flow/{id}/import` 或 `/flow` 保存
3. 运行：`POST /flow/{id}/run` 携带运行参数
4. 实例状态/结果可通过实例 ID 查询（挂起时前端展示"待恢复"）

详细架构见 [架构图.md](架构图.md)。
