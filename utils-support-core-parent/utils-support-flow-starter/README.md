# utils-support-flow-starter

> utils 体系的**流程编排实现层（L2）**：在 `utils-support-common-starter` 的 `task/flow` 契约之上，提供可执行的 Flow 引擎实现（`FlowEngine` / `DefaultFlow` / `DefaultFlowGraph` / `DefaultFlowInstance`）、内存存储（`FlowDefinitionStore` / `FlowSnapshotStore`）、Spring Boot 自动装配与 REST 接口（`FlowController`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 POM `maven-compiler-plugin` 配置 `<release>25</release>`）｜包名：`com.chua.flow.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 模块构成](#13-模块构成)
  - [1.4 常用能力速查](#14-常用能力速查)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 引擎入口 FlowEngine](#21-引擎入口-flowengine)
  - [2.2 实现类一览](#22-实现类一览)
  - [2.3 契约层关键方法速查](#23-契约层关键方法速查)
- [3. 快速开始](#3-快速开始)
  - [3.1 最短可运行示例](#31-最短可运行示例)
  - [3.2 条件分支示例](#32-条件分支示例)
  - [3.3 JSON 导入导出](#33-json-导入导出)
  - [3.4 REST 方式运行](#34-rest-方式运行)
- [4. 执行语义与实例状态](#4-执行语义与实例状态)
  - [4.1 状态机](#41-状态机)
  - [4.2 调度模型](#42-调度模型)
  - [4.3 挂起与恢复](#43-挂起与恢复)
  - [4.4 死循环防护](#44-死循环防护)
- [5. 统一图格式（FlowDefinition）](#5-统一图格式flowdefinition)
- [6. 内置节点类型与属性](#6-内置节点类型与属性)
- [7. REST 接口（Spring Boot 自动装配）](#7-rest-接口spring-boot-自动装配)
- [8. 配置项](#8-配置项)
- [9. 扩展点](#9-扩展点)
  - [9.1 自定义节点](#91-自定义节点)
  - [9.2 存储接口扩展](#92-存储接口扩展)
  - [9.3 Spring Bean 覆盖](#93-spring-bean-覆盖)
- [10. 构建与依赖](#10-构建与依赖)
- [11. 边界：这些能力不在本模块](#11-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-flow-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 层级 | L2 实现层：引擎实现 + 节点注册 + 持久化（内存默认）+ Spring Boot 自动装配 + REST | 不是流程契约定义方（契约在 `utils-support-common-starter` 的 `com.chua.common.support.task.flow`） |
| 引擎 | 待执行队列模型，顺序边 / 条件分支（true/false 标签边）逐节点调度；复用 `task/pipeline` 的 `Action`（`NEXT` / `EXIT` / `WAIT`） | 不是工作流 BPMN 引擎，不支持并行分叉、定时触发、分布式调度 |
| 节点 | 内置 6 类节点（`start` / `end` / `condition` / `transform` / `log` / `httpCall`），节点以**实例**形式 `addNode` 加入流程，无需 SPI 注册 | 不自带 `spider` 节点（爬虫节点 `SpiderFlowNode` 在 `utils-support-crawler-starter`，实现契约接口 `SpiderNode`；但 crawler-starter 不注册该类型，需依赖并按 9.1 手动注册，见第 11 节） |
| 存储 | 内存实现 `MemoryFlowDefinitionStore` / `MemoryFlowSnapshotStore` | 不含 MySQL / Redis / 文件持久化实现（接口在，实现由业务注入） |
| 依赖 | 仅依赖 common-starter 与 Spring（autoconfigure / web / context） | 不依赖任何数据库驱动、消息中间件、爬虫框架 |

**判断口径**：契约（接口/模型/节点注册表）在 `utils-support-core-parent` 的 common-starter L1；本模块 L2 只管「把图跑起来 + 开箱即用的 REST」；领域节点（如爬虫）在各自 L3 模块实现，只依赖 L1 契约，**不反向依赖本模块**。

### 1.3 模块构成

- Java 源文件 **19** 个（`src/main/java`），全部位于 `com.chua.flow.support` 及其子包 `store` / `node` / `config`，`@since 4.0.0.42`。
- 无 `src/test` 测试源码目录。
- 自动装配声明：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → `com.chua.flow.support.config.FlowAutoConfiguration`。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`（19 个类型全量反射）与 `resource-config.json`（包含 `META-INF/spring/.*`）。
- 无 `META-INF/extensions/` SPI 声明文件（本模块不注册 SPI 扩展；节点类型走程序式注册表 `FlowNodeRegistry`）。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建流程 | `FlowEngine.createFlow(String id)` | 构建入口；首次调用引擎方法时惰性注册 6 个内置节点类型 |
| 链式编排连线 | `flow.createGraph().start(...).next(...).when(...).end()` | `start` 定起点、`next` 顺序边、`when` 条件分支、`end` 收尾 |
| 运行流程 | `flow.createGraph().createInstance(params).run()` | 创建实例即持有唯一上下文，参数并入 `ctx.attributes` |
| JSON 导入导出 | `FlowEngine.parseJson(json)` / `flow.exportJson()` / `flow.importJson(json)` | 前后端 ReFlow 画布统一图格式 |
| 校验定义 | `FlowEngine.validate(json)` | 校验节点类型全部已注册，否则抛 `FlowException` |
| 一键执行 | `FlowEngine.runOnce(json)` | 脚本/测试场景：解析 → 创建 → 执行一次 |
| 节点类型清单 | `FlowEngine.listNodeTypes()` / `GET /flow/node/types` | 供前端属性面板渲染 |
| 保存 / 运行 REST | `POST /flow`、`POST /flow/{flowId}/run` | Spring Boot 自动装配的 `FlowController` |
| 挂起恢复 | `instance.resume()` 或 `POST /flow/instance/{instanceId}/resume` | 仅 `WAITED` 状态可恢复 |

---

## 2. 核心 API 与速查表

### 2.1 引擎入口 FlowEngine

`com.chua.flow.support.FlowEngine`：`final` 工具类，私有构造，全部静态方法。

| 方法 | 说明 |
| --- | --- |
| `static Flow createFlow(String id)` | 创建空流程（`new DefaultFlow(id)`） |
| `static Flow createFlow(FlowDefinition definition)` | 从流程定义图模型创建流程；未注册的节点类型直接抛 `FlowException("未注册的节点类型: " + type)` |
| `static Flow parseJson(String json)` | 解析前端 ReFlow 导出的图数据，校验节点类型后重建可执行流程 |
| `static FlowInstance runOnce(String json)` | 快捷执行入口：`parseJson(json)` 后 `createGraph().createInstance().run()`，用于脚本与测试场景 |
| `static List<FlowNodeMetadata> listNodeTypes()` | 全部节点类型元信息，供前端属性面板渲染节点类型列表 |
| `static boolean validate(String json)` | 校验 JSON 流程定义是否合法：节点类型必须全部已注册，否则抛 `FlowException` |

首次调用 `createFlow` / `parseJson` / `runOnce` / `listNodeTypes` 任一方法时，`synchronized` 惰性执行 `ensureBuiltInRegistered()`（`validate` 仅查询注册表、不触发注册）向 `FlowNodeRegistry` 注册内置节点：

| 类型标识 | 原型 | 描述 |
| --- | --- | --- |
| `start` | `StartFlowNode` | 起始节点 |
| `end` | `EndFlowNode` | 终止节点 |
| `condition` | `ConditionFlowNode` | 条件分支 |
| `transform` | `TransformFlowNode` | 数据转换 |
| `log` | `LogFlowNode` | 日志输出 |
| `httpCall` | `HttpCallFlowNode` | HTTP 调用 |

### 2.2 实现类一览

| 类 | 职责 |
| --- | --- |
| `DefaultFlow` | 节点定义的管理容器：`addNode` 直接添加节点实例（无需 SPI 注册）、`createGraph()` 进入可执行状态、`exportJson()` / `importJson(json)`；额外提供 `getDefinition()`、`nodeProps(String nodeId)`（节点不存在返回 `FlowProps.EMPTY`）、`resolveStartNodeId()`（取第一个无入边的节点，全部有入边时回退第一个节点） |
| `DefaultFlowGraph` | 链式 DSL：`start` / `next` / `when` / `end` / `createInstance` / `createInstance(Map)` / `exportJson`；连线写入底层定义的 edges，导出格式与前端 ReFlow 画布一致 |
| `DefaultFlowInstance` | 一次流程运行的生命周期载体，内部**唯一持有** `DefaultFlowContext`；待执行队列调度、状态流转、挂起/恢复/终止、死循环防护 |
| `DefaultFlowContext` | 节点间共享的数据载体：当前数据、属性映射、执行轨迹、流转动作（`getAction()` / `clearAction()`）、执行计数（`recordExecution` / `isLoopLimitReached`）、挂起快照（`snapshotPending`） |
| `store.FlowDefinitionStore`（接口） | 流程定义存储：`save` / `get` / `remove` / `list` |
| `store.MemoryFlowDefinitionStore` | 基于 `ConcurrentHashMap` 的内存实现，进程重启后数据丢失，适用于原型演示与轻量场景 |
| `store.FlowSnapshotStore`（接口） | 执行快照存储：`save` / `load(executionNo)` / `list(flowId)` |
| `store.MemoryFlowSnapshotStore` | 内存快照实现，线程安全，单机轻量；进程重启后数据丢失 |
| `store.FlowSnapshot` | 一次流程执行的完整快照 record：`executionNo` / `flowId` / `traces` / `status` / `startAt` / `endAt`，附带 `duration()`（毫秒）与 `durationText()`（可读耗时） |
| `store.FlowInstanceRegistry` | 运行中实例注册中心：`register` / `get` / `remove`，按实例 ID 查询，用于外部触发挂起实例的恢复 |
| `config.FlowAutoConfiguration` | 自动配置：`@ConditionalOnClass(RestController.class)`，注册 `FlowDefinitionStore`、`FlowInstanceRegistry`、`FlowController` 三个 Bean（均 `@ConditionalOnMissingBean`） |
| `config.FlowController` | 流程编排 REST 控制器，`@RequestMapping("/flow")`，详见第 7 节 |

内置节点（`com.chua.flow.support.node`）实现契约层二级接口：

| 实现 | 契约接口 | type() |
| --- | --- | --- |
| `StartFlowNode` | `StartNode` | `start`（execute 空操作） |
| `EndFlowNode` | `EndNode` | `end`（execute → `context.exit()`） |
| `ConditionFlowNode` | `ConditionNode` | `condition`（`test(FlowContext)` 求值） |
| `TransformFlowNode` | `TransformNode` | `transform`（取值写入当前数据） |
| `LogFlowNode` | `LogNode` | `log`（SLF4J 输出，`@Slf4j`） |
| `HttpCallFlowNode` | `HttpCallNode` | `httpCall`（JDK `HttpClient`） |

### 2.3 契约层关键方法速查

契约（接口/模型/注册表）位于 `com.chua.common.support.task.flow`（common-starter），本模块实现并依赖。最常查询的签名：

| 类型 | 方法 | 说明 |
| --- | --- | --- |
| `Flow` | `addNode(String id, FlowNode node)` / `addNode(String id, FlowNode node, Map<String, Object> props)` | 节点直接以实例加入，无需 SPI 注册，可链式调用 |
| `Flow` | `createGraph()` / `exportJson()` / `importJson(String json)` | 进入可执行状态 / 导出 / 导入 |
| `FlowGraph` | `start(String)` → `next(String...)` → `when(String nodeId, boolean result, String... targets)` → `end(String...)` | 链式 DSL；`next` 一个源连多目标串行执行；`when` 写 `true`/`false` 标签边；`end` 不传参默认标记游标节点 |
| `FlowGraph` | `createInstance()` / `createInstance(Map<String, Object> params)` | 携带初始参数创建实例，参数首次运行时合并写入上下文 |
| `FlowInstance` | `run()` / `run(Map<String, Object> params)` / `resume()` / `terminate()` / `isCompleted()` / `maxLoopCount(int)` | 运行 / 续跑 / 恢复 / 终止 / 是否完成 / 单节点最大执行次数上限 |
| `FlowInstance` | `getInstanceId()` / `getFlowId()` / `getStatus()` / `getContext()` | 标识与状态读取 |
| `FlowContext` | `getData()` / `setData(Object)` | 当前处理数据读写（节点间传递） |
| `FlowContext` | `getAttribute(String)` / `setAttribute(String, Object)` / `getAttributes()` | 上下文属性读写（跨节点共享） |
| `FlowContext` | `currentNodeProps()` | 当前节点配置属性（`FlowProps`） |
| `FlowContext` | `setNextNodeId(String)` / `waitForResume()` / `exit()` | 显式指定下一节点 / 挂起 / 退出流程 |
| `FlowContext` | `getExecutionTrace()` / `getTraces()` / `getExecuteCount(String)` / `getMaxLoopCount()` / `setMaxLoopCount(int)` / `getPendingNodeIds()` | 执行轨迹、回放快照、防死循环、挂起点快照 |
| `FlowNode` | `type()` / `execute(FlowContext)` / `cloneNode()` / `configSchema()` | 节点契约；`cloneNode` 默认返回自身，`configSchema` 默认空列表 |
| `FlowNodeRegistry` | `register(String type, FlowNode prototype, String describe)` / `unregister` / `exists` / `createNode(String)` / `listMetadata()` / `listTypes()` / `describeMap()` | 程序式节点类型注册表 |
| `FlowProps` | `has` / `get` / `getString` / `getString(key, default)` / `getInt` / `getInt(key, default)` / `getBoolean` / `getDouble` / `getList(key, Class)` / `getStringList` / `getMap` / `toMap` / `size` / `EMPTY` | 节点属性类型安全读取 |
| `FlowJson` | `toJson(FlowDefinition)` / `fromJson(String)` | JSON 序列化/反序列化 |
| `FlowStatus` | `NEW` / `RUNNING` / `WAITED` / `COMPLETED` / `FAILED` / `TERMINATED` | 实例状态枚举 |
| `FlowTrace` | record `(nodeId, input, output, timestamp)`，`of(nodeId, input, output)` | 单节点执行痕迹（输入/输出快照） |
| `FlowNodeMetadata` | record `(type, name, describe, schema)` | 节点类型元信息，供前端属性面板渲染 |

---

## 3. 快速开始

### 3.1 最短可运行示例

纯 Java（不需要 Spring），依赖 `utils-support-common-starter` 契约与内置节点即可：

```java
import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.flow.support.FlowEngine;
import com.chua.flow.support.node.StartFlowNode;
import com.chua.flow.support.node.LogFlowNode;
import com.chua.flow.support.node.EndFlowNode;
import java.util.Map;

// 1. 构建流程：节点以实例形式直接加入，无需 SPI 注册
Flow flow = FlowEngine.createFlow("demo")
        .addNode("start", new StartFlowNode())
        .addNode("log", new LogFlowNode(), Map.of("message", "hello flow"))
        .addNode("end", new EndFlowNode());

// 2. 定义连线并创建实例（createInstance(params) 携带初始参数）
FlowInstance instance = flow.createGraph()
        .start("start").next("log").next("end").end()
        .createInstance(Map.of("bizId", "1"));

// 3. 运行：参数按键展开写入上下文属性，同时整体存入 "params" 键
instance.run();

System.out.println(instance.getStatus());          // COMPLETED
System.out.println(instance.getContext().getAttribute("bizId"));  // "1"

// 注意：实例已 COMPLETED 后再次 run 会抛 IllegalStateException（见 4.1 状态机约束）
// 未结束实例多次 run 参数合并、上下文不重建；WAITED 挂起实例请用 resume() 恢复
```

### 3.2 条件分支示例

```java
import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.flow.support.FlowEngine;
import com.chua.flow.support.node.*;

// condition 节点属性：key 为 "current" 时取当前数据，否则取上下文属性；
// equals 做等于比较（可选）；notEmpty 要求非空（可选，默认 true）
Flow flow = FlowEngine.createFlow("check-demo")
        .addNode("start", new StartFlowNode())
        .addNode("check", new ConditionFlowNode(), Map.of("key", "current"))
        .addNode("yes", new LogFlowNode(), Map.of("message", "有数据", "level", "info"))
        .addNode("no", new LogFlowNode(), Map.of("message", "无数据", "level", "warn"))
        .addNode("end", new EndFlowNode());

// when(nodeId, result, targets...) 配置 true/false 分支的标签边
FlowInstance instance = flow.createGraph()
        .start("start").next("check")          // start → check（顺序边）
        .when("check", true, "yes")            // check → yes（label=true）
        .next("yes").next("end")               // yes → end（顺序边）
        .when("check", false, "no")            // check → no（label=false）
        .next("no").next("end")                // no → end（顺序边）
        .end()
        .createInstance(Map.of("bizId", "1"));

instance.run();

// 注意：execute() 失败时实例进入 FAILED 并向上抛出异常；
// 条件节点测试表达式亦可：key="current" + equals="期望值"
```

### 3.3 JSON 导入导出

```java
// 导出：流程定义 → JSON（与前端 ReFlow 画布数据一致）
String json = flow.exportJson();

// 导入：JSON → 可执行 Flow（节点类型须已注册，否则抛 FlowException）
Flow restored = FlowEngine.parseJson(json);

// 校验：节点类型全部已注册返回 true
boolean ok = FlowEngine.validate(json);

// 脚本/测试：解析并执行一次，返回已完成实例
FlowInstance done = FlowEngine.runOnce(json);
```

### 3.4 REST 方式运行

Spring Boot 工程引入本模块后（类路径存在 Spring Web 即自动装配）：

```bash
# 保存流程定义（body 为 FlowDefinition JSON）
curl -X POST http://localhost:8080/flow -H "Content-Type: application/json" -d @flow.json

# 运行流程（body 为运行参数 Map）
curl -X POST http://localhost:8080/flow/flow1/run -H "Content-Type: application/json" -d '{"bizId":"1"}'

# 响应摘要（buildResult）：{"instanceId": "...", "flowId": "flow1", "status": "COMPLETED"}
```

---

## 4. 执行语义与实例状态

### 4.1 状态机

与源码注释一致：`NEW` → `RUNNING` → `WAITED`（节点挂起时）→ `COMPLETED`；节点异常进入 `FAILED`，外部终止进入 `TERMINATED`。

| 状态 | 含义 | 触发 |
| --- | --- | --- |
| `NEW` | 实例已创建，尚未开始执行 | `createInstance()` |
| `RUNNING` | 正在执行节点链路 | 首次 `run()` / `run(params)` / `resume()` |
| `WAITED` | 已挂起，等待外部触发恢复 | 节点调用 `context.waitForResume()`（设置 `Action.WAIT`） |
| `COMPLETED` | 正常结束 | 队列消费完，或节点调用 `context.exit()`（设置 `Action.EXIT`） |
| `FAILED` | 执行失败 | 节点抛出异常，实例标记 `FAILED` 并向上抛出 |
| `TERMINATED` | 外部主动终止 | `instance.terminate()` |

约束：已结束（`COMPLETED` / `FAILED` / `TERMINATED`）的实例不允许再次运行，`run` 抛 `IllegalStateException("实例已结束，无法继续运行: " + status)`；`resume()` 仅 `WAITED` 状态有效，否则抛 `IllegalStateException("实例未挂起，无法恢复: " + status)`。

### 4.2 调度模型

- **待执行队列**：`DefaultFlowInstance` 持有 `Deque<String> pendingNodes`，从起始节点开始按图连线调度，节点产出后续节点全部入队依次消费。
- **同一上下文**：首次 `run` 创建唯一 `DefaultFlowContext`，之后每次 `run(params)` 参数按键展开合并写入上下文属性（同时整体存入 `"params"` 键），上下文不重建，支持跨节点共享数据、多次运行累积状态。
- **边标签路由**：条件节点按 `ConditionNode.test(context)` 结果走 `"true"` / `"false"` 标签边（`branchTargets`）；普通节点走空标签顺序边（`orderTargets`）。一个源节点连多条相同标签边 = 多目标串行执行。
- **显式跳转**：节点调用 `context.setNextNodeId(id)` 后优先于顺序边入队（`clearNextNodeId` 每次执行前清空）。
- **实例 ID**：`UUID.randomUUID().toString().replace("-", "")`。

### 4.3 挂起与恢复

节点执行中调用 `context.waitForResume()`：实例冻结待执行队列（`context.snapshotPending(...)` 保存挂起点快照供查看）、进入 `WAITED`；外部触发 `instance.resume()` 后从队列继续消费。挂起实例的上下文整体保持在内存实例中（`FlowInstanceRegistry` 登记），恢复后仍是同一上下文。

### 4.4 死循环防护

`DefaultFlowContext` 记录每个节点累计执行次数（`executeCounts`），单节点达上限（默认 `DEFAULT_MAX_LOOP_COUNT = 100`，可用 `instance.maxLoopCount(int)` 在首次 `run` 前调整，`<= 0` 恢复默认）即判定死循环，抛 `FlowException("检测到死循环，节点执行次数达到上限 " + getMaxLoopCount() + ": " + currentNodeId)`。

---

## 5. 统一图格式（FlowDefinition）

前后端共用的流程定义 JSON，由 `FlowDefinition`（`@Data`：`id` / `name` / `nodes` / `edges`）承载，`FlowJson` 负责序列化：

```json
{
  "id": "flow1",
  "name": "审批流程",
  "nodes": [
    { "id": "start",  "type": "start",     "props": {},                 "x": 100, "y": 100 },
    { "id": "check",  "type": "condition", "props": { "key": "current" }, "x": 300, "y": 100 },
    { "id": "end",    "type": "end",       "props": {},                 "x": 500, "y": 100 }
  ],
  "edges": [
    { "from": "start", "to": "check", "label": "" },
    { "from": "check", "to": "end",   "label": "true" },
    { "from": "check", "to": "end",   "label": "false" }
  ]
}
```

- `nodes[].type` 对应节点类型注册表中的类型标识，`props` 为节点配置参数，`x` / `y` 为前端画布坐标（后端执行忽略）。
- `edges[].label`：空字符串表示顺序边；`"true"` / `"false"` 为条件分支标签；来源 `FlowDefinition.FlowEdgeDef` record `(from, to, label)`，`FlowNodeDef` record `(id, type, props, x, y)`。
- JSON 导入时引擎通过 `FlowNodeRegistry.createNode(type)` 按类型克隆原型创建节点副本，保证流程间节点状态隔离；未注册类型报错，避免运行期才发现配置错误。

---

## 6. 内置节点类型与属性

节点执行时通过 `context.currentNodeProps()` 读取 `FlowProps`（`getString` / `getInt` / `getBoolean` / `getDouble` / `getStringList` / `getMap` / `has` / `get`）。

| 类型 | 说明 | 关键 props | 执行行为 |
| --- | --- | --- | --- |
| `start` | 起始节点 | 无 | 空操作，仅标记入口 |
| `end` | 终止节点 | 无 | `context.exit()` 立即终止流程，实例进入完成状态 |
| `condition` | 条件分支 | `key`（取值键，"current" 取当前数据，否则取上下文属性）；`equals`（等于比较期望值，可选）；`notEmpty`（是否要求非空，可选，默认 true） | 引擎按 `test(context)` 结果走 `true` / `false` 分支。判断模式：`key+equals` 比较相等；`key+notEmpty` 判断非空；仅 `key` 默认判断存在且非空 |
| `transform` | 数据转换 | `source`（`current` 取当前数据；`attribute:key` 取上下文属性 key；其它视为上下文属性键） | 取值结果 `context.setData(...)` 写入当前数据供下游消费 |
| `log` | 日志输出 | `message`（日志内容）；`level`（`info` / `warn` / `error`，默认 `info`） | 经 SLF4J 输出；message 为空直接返回 |
| `httpCall` | HTTP 调用 | `url`（必填，缺失抛 `IllegalArgumentException("httpCall 节点缺少 url 属性")`）；`method`（默认 GET，支持 GET / POST / PUT / DELETE）；`headers`（请求头映射，可选）；`body`（请求体，可选，用于 POST/PUT） | 使用 JDK `HttpClient`（connectTimeout 30 秒），响应写入上下文：`http.result`（响应体字符串）、`http.status`（响应状态码）；失败抛 `IllegalStateException("HTTP 请求失败: " + url, e)` |

`spider` 类型**不在本模块**：爬虫节点 `SpiderFlowNode`（`utils-support-crawler-starter`，`com.chua.crawler.support.flow`）实现契约接口 `SpiderNode`，props 为 `urls`（必填）、`threads`（默认 1）、`maxDepth`（默认 -1 不限制）、`maxPages`（默认 0 不限制）、`interval`（默认 1000，请求间隔毫秒）、`retryTimes`（默认 3）；执行后写入 `spider.result` 并将当前数据设置为结果列表。需依赖 crawler-starter 并注册该类型（`FlowNodeRegistry.register("spider", new SpiderFlowNode(), ...)`）后 JSON 导入才能按类型创建副本。

---

## 7. REST 接口（Spring Boot 自动装配）

`FlowAutoConfiguration` 在类路径存在 `org.springframework.web.bind.annotation.RestController` 时生效（`@ConditionalOnClass`），自动注册三个 Bean（均可被用户 Bean 覆盖，`@ConditionalOnMissingBean`）：`FlowDefinitionStore`（默认 `MemoryFlowDefinitionStore`）、`FlowInstanceRegistry`、`FlowController`。`FlowController` 映射前缀 `/flow`：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/flow` | 保存流程定义（`@RequestBody FlowDefinition`，以 id 为键覆盖更新） |
| GET | `/flow/list` | 查询全部流程定义 |
| GET | `/flow/{flowId}` | 查询流程定义，不存在返回 404 |
| DELETE | `/flow/{flowId}` | 删除流程定义，返回 `{"deleted": boolean}` |
| POST | `/flow/import` | 导入流程定义 JSON（`@RequestBody String`），解析后保存 |
| GET | `/flow/{flowId}/export` | 导出流程定义 JSON 字符串，不存在返回 404 |
| POST | `/flow/{flowId}/run` | 运行流程：按 ID 加载定义 → `FlowEngine.createFlow(definition)` → `createGraph().createInstance(params)` → 注册实例 → `run()`；body 为运行参数 Map（可缺省）；返回 `{instanceId, flowId, status}` 摘要 |
| POST | `/flow/instance/{instanceId}/resume` | 恢复挂起实例，不存在返回 404，返回结果摘要 |
| GET | `/flow/node/types` | 节点类型清单（`FlowEngine.listNodeTypes()`），供前端渲染属性面板 |

挂起实例的恢复依赖 `FlowInstanceRegistry`（内存）中的实例引用；服务重启后内存实例与注册中心数据均丢失（见第 8、11 节边界）。

---

## 8. 配置项

**无。**

依据（源码事实）：

- 全模块无 `@ConfigurationProperties` 绑定类、无 `application.properties` / `application.yml`、无任何 `*.properties` 配置文件（`src/main/resources` 下仅 `META-INF/spring/...imports` 与 `META-INF/native-image/...`）。
- 自动装配的唯一开关是类路径条件 `@ConditionalOnClass(RestController.class)`；全部 Bean 标注 `@ConditionalOnMissingBean`，即「存在 Spring Web 自动生效，用户 Bean 可覆盖」，不存在前缀配置键。
- 运行期可调参数均以代码方式传入：实例单节点上限 `instance.maxLoopCount(int)`（默认 100）、节点行为（如 httpCall 的 url/method/headers/body、log 的 level）由节点 `props` 配置。

---

## 9. 扩展点

### 9.1 自定义节点

与旧版「注解 + SPI 注册」不同（契约层 `FlowNode` 注释原文），新架构中节点是普通接口，直接以实例形式通过 `Flow#addNode` 加入流程，**无需任何 SPI 注册**：

```java
import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.flow.support.FlowEngine;
import java.util.Map;

public class MyNode implements FlowNode {
    @Override
    public String type() {
        return "myNode";
    }

    @Override
    public void execute(FlowContext context) {
        FlowProps props = context.currentNodeProps();       // 读取节点配置
        Object data = context.getData();                    // 读取当前数据
        context.setAttribute("my.result", data);            // 写入共享属性
        context.setData("processed");                       // 更新当前数据
        // context.setNextNodeId("other");                  // 显式跳转
        // context.waitForResume();                         // 挂起等待外部恢复
        // context.exit();                                  // 终止流程
    }
}

Flow flow = FlowEngine.createFlow("demo")
        .addNode("n1", new MyNode(), Map.of("key", "value"))
        // ...
        ;
```

若需要节点类型在 **JSON 导入**（`createFlow(FlowDefinition)` 按类型克隆副本）与**类型清单**（`FlowEngine.listNodeTypes()` / `GET /flow/node/types`）中可见，需向程序式注册表登记原型（本模块内置 6 类即由此注册）：

```java
import com.chua.common.support.task.flow.FlowNodeRegistry;

FlowNodeRegistry.register("myNode", new MyNode(), "我的节点");
boolean exists = FlowNodeRegistry.exists("myNode");   // true
```

领域模块（如 `utils-support-crawler-starter` 的 `SpiderFlowNode`）实现契约接口（`SpiderNode` 等）即被编排，只依赖 common-starter 契约，**不反向依赖本模块**，因此不破坏核心。

### 9.2 存储接口扩展

| 接口 | 默认实现 | 说明 |
| --- | --- | --- |
| `FlowDefinitionStore` | `MemoryFlowDefinitionStore` | 流程定义持久化；接口注释：由具体实现决定持久化介质（内存、文件、数据库等） |
| `FlowSnapshotStore` | `MemoryFlowSnapshotStore` | 执行快照存储（供全链路追踪、审计回放、前端"按执行号查看整条链路"）；接口注释：业务可注入基于 MySQL/Redis 的持久化实现 |

`FlowSnapshotStore` 未纳入自动装配默认 Bean（自动装配仅注册 `FlowDefinitionStore` / `FlowInstanceRegistry` / `FlowController`），需要持久化时由业务自行实现并注入。

### 9.3 Spring Bean 覆盖

`FlowAutoConfiguration` 三个 Bean 均 `@ConditionalOnMissingBean`：自定义 `FlowDefinitionStore` / `FlowInstanceRegistry` Bean 即可替换内存默认实现。

---

## 10. 构建与依赖

### 10.1 依赖（`pom.xml`，均 compile 作用域）

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter`（`${project.version}`） | 核心依赖：Flow 契约（`com.chua.common.support.task.flow`）+ `task/pipeline` 执行内核（含 `Action` 枚举） |
| `org.springframework.boot:spring-boot-autoconfigure` | Spring Boot 自动配置（运行时反射 `@Configuration` / `@Bean`） |
| `org.springframework:spring-web` | Spring Web 注解（运行时 MVC 反射 `@RestController` 等） |
| `org.springframework:spring-context` | Spring Context（运行时反射 `@Configuration` / `@Bean`） |

无测试依赖、无 Lombok 依赖声明（`node.LogFlowNode` 使用 `lombok.extern.slf4j.Slf4j`，注解处理器由父 POM `maven-compiler-plugin` 的 `annotationProcessorPaths` 提供）。

### 10.2 构建

- 父 POM：`com.chua:utils-support-core-parent:4.0.0.51`（`utils-support-core-parent` 模块列表含本模块）。
- JDK：`<release>25</release>`；`maven-compiler-plugin` 3.16.0 对本模块的**最终生效配置**为 `<fork>false</fork>`、`compilerArgs` 仅 `-Xlint:all`（顶层 parent 的 `--enable-preview` / `-parameters` / `--add-modules=jdk.incubator.vector` 被 core-parent 的 `pluginManagement` 覆盖，对本模块不生效），Lombok 经 `annotationProcessorPaths` 注入；本模块另设 `maven.compiler.parameters=true`、`project.build.sourceEncoding=UTF-8`。
- 构建命令（在 `utils-support-core-parent` 目录，`-am` 连带构建 common-starter）：

```text
mvn -pl utils-support-flow-starter -am clean compile
```

- 模块自带 GraalVM `native-image` 配置（`reflect-config.json` / `resource-config.json`），`utils-support-core-parent` 父 POM 提供 `native` profile（native-maven-plugin 1.1.14，`buildArg` 含 `--no-fallback`、`--enable-url-protocols=http,https`、`-H:+ReportExceptionStackTraces`、`--enable-native-access=ALL-UNNAMED` 等）。

---

## 11. 边界：这些能力不在本模块

| 能力 | 所在位置 | 说明 |
| --- | --- | --- |
| 流程契约（`Flow` / `FlowInstance` / `FlowGraph` / `FlowContext` / `FlowNode` / `FlowNodeRegistry` / `FlowJson` / `FlowProps` / `FlowDefinition` / `FlowStatus` / `FlowTrace` / `FlowNodeMetadata` / `FlowException` 等全部接口与模型） | `utils-support-common-starter` 的 `com.chua.common.support.task.flow` | 本模块仅实现与使用，不定义 |
| 执行内核 `task/pipeline`（`Pipeline` / `DecisionNode` / `Action` 等） | `utils-support-common-starter` | 本模块复用其 `Action`（`NEXT` / `EXIT` / `WAIT`）语义，未自带 |
| `spider` 爬虫节点（`SpiderFlowNode`，props `urls` / `threads` / `maxDepth` / `maxPages` / `interval` / `retryTimes`） | `utils-support-crawler-starter` 的 `com.chua.crawler.support.flow` | 本模块未注册该类型；需依赖 crawler-starter 并按 9.1 注册后才可在 JSON 导入中使用 |
| 持久化存储（数据库 / 文件） | 无内置实现 | 默认全部内存实现，进程重启数据丢失；生产需自行实现 `FlowDefinitionStore` / `FlowSnapshotStore`（接口在本模块） |
| 挂起实例跨进程恢复 | 无内置实现 | `FlowInstanceRegistry` 与 `FlowInstance` 均为进程内对象，重启后实例与注册中心数据不恢复 |
| 分布式 / 集群编排、定时触发、并行分叉、子流程 | 无内置实现 | 引擎为单实例顺序/分支队列模型（条件分支多目标串行），`Action` 无并行语义 |
| 前端画布（ReFlow） | `vue-support-parent-starter` 的 `packages/ui/src/components/ReFlowGraph/`（基于 `@antv/x6` 的可视化流程设计器，主题化加载），编排页见 `pages/tenant/src/views/ReFlowOrchestration.vue` | 本模块只提供 `/flow` REST 与其对接，不含任何前端代码 |
| 流程 ID 生成策略 / 鉴权 / 审计日志落库 | 无内置实现 | 实例 ID 为随机 UUID（去连字符）；REST 接口无鉴权注解 |

---

> 详细架构（分层、对象模型、执行时序图、节点注册机制、端到端流程）见同目录 [架构图.md](架构图.md)。