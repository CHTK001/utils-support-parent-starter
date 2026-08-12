# Task Distribution Framework

> 通用任务分发框架核心模块。位于 `com.chua.common.support.taskdistribution` 包，提供发布端-工作端双角色协同、优先级队列调度、MDC 链路追踪与多类型持久化 SPI 能力。

---

## 模块定位

| 角色 | 抽象 | 实现 |
|---|---|---|
| 任务模型 | `Task` / `TaskResult` / `TaskStatus` / `TaskPriority` | — |
| 派发通道 | `DispatcherProvider` | `InMemoryDispatcherProvider`（默认）/ 自定义 `TcpScatterGather*` / `UdpScatterGather*` |
| 任务管理 | `TaskManager` | 单实例，负责状态机、超时检测、回调与持久化桥接 |
| 结果缓冲 | `ResultBuffer` | 内存版（生产可对接 Redis/Kafka） |
| 节点注册 | `NodeTable` / `Node` / `NodeMeta` | 心跳保活与能力标签匹配 |
| 工作端 SPI | `TaskExecutor` / `TaskExecutorRegistry` | 用户实现 |
| 持久化 SPI | `TaskStore` | `InMemoryTaskStore` / `FileTaskStore` / `DbTaskStore`（MyBatis-Plus） |
| MDC 上下文 | `MdcDecorator` | 跨线程 traceId / taskId 传递 |

## 包结构

```
com.chua.common.support.taskdistribution
├── dispatcher/    # 派发接口 + 内存实现 + MDC 装饰器
├── manager/       # TaskManager 状态机 + ResultBuffer 结果缓冲 + 状态监听
├── node/          # NodeTable 节点注册表
├── scattergather/ # TCP/UDP 多节点散射-汇聚（集群模式）
├── spi/           # TaskExecutor / TaskExecutorRegistry SPI
├── store/         # 3 种 TaskStore 实现
├── strategy/      # DispatchStrategy 派发策略（轮询/负载均衡）
└── task/          # Task / TaskResult / TaskStatus / TaskPriority / TaskCallback / TaskDeduplicator
```

---

## 核心流程

### 单机模式

```
发布端                                 工作端
   │                                     │
   │  addTask(task, callback)            │
   ▼                                     │
TaskManager ──► TaskStore.saveTask       │
   │                                     │
   │  dispatcherProvider.receive(task)   │
   ▼                                     │
InMemoryDispatcherProvider               │
   │ (priority queue + thread pool)      │
   │                                     │
   │  listener.onTask(task)              ─►│ TaskExecutor.execute
   │                                     │     │
   │                                     │     ▼
   │  listener.onResult(result)        ◄─┤
   ▼                                     │
TaskManager.handleResult(result)         │
   │  store.saveResult + callback.onResult
   ▼
```

### 集群模式（Scatter-Gather）

发布端将任务广播到 N 个工作端节点，工作端本地执行后回传结果，发布端汇聚后调用回调。`TcpScatterGather*` 和 `UdpScatterGather*` 分别基于 TCP（可靠）和 UDP（低延迟）实现散射-汇聚协议。

---

## MDC 链路追踪

`MdcDecorator` 在跨线程派发和执行时自动注入 `taskId` / `traceId` 到 SLF4J MDC：

- `decorate(Runnable)`：快照当前线程 MDC 上下文，在目标线程还原
- `KEY_TASK_ID` / `KEY_TRACE_ID`：标准键名
- 与 `spring-support-common-starter` 的 `TraceMessageConverter` 配合，Logback pattern 自动输出

```java
// 在 TaskManager 中
timeoutScheduler.scheduleAtFixedRate(
    MdcDecorator.decorate(this::checkTimeouts),
    ...
);

// 在 InMemoryDispatcherProvider 中
try {
    MDC.put(MdcDecorator.KEY_TASK_ID, task.getTaskId());
    if (task.getTraceId() != null) {
        MDC.put(MdcDecorator.KEY_TRACE_ID, task.getTraceId());
    }
    listener.onTask(task);
} finally {
    MDC.remove(MdcDecorator.KEY_TASK_ID);
    MDC.remove(MdcDecorator.KEY_TRACE_ID);
}
```

---

## Task 任务模型

```java
Task.<String>builder()
    .taskId(TaskIdGenerator.next())      // 自动生成: {nodeId}-{seq}
    .taskType("image.generate")
    .payload("base64-image-data")
    .priority(TaskPriority.HIGH)
    .timeoutMs(60_000)
    .maxRetries(3)
    .tag("region", "cn-east")
    .shardCount(4)                       // 分片执行
    .build();
```

字段语义：

| 字段 | 必填 | 含义 |
|---|---|---|
| `taskId` | 是 | 全局唯一，由 `TaskIdGenerator` 生成 |
| `traceId` | 否 | 链路追踪 ID，子任务继承父任务 |
| `parentTaskId` | 否 | 父任务 ID（分片/链式） |
| `taskType` | 是 | 匹配 `TaskExecutor.taskType()` |
| `payload` | 是 | 任务数据（任意 `T`） |
| `tags` | 否 | 能力匹配 / 路由标签 |
| `priority` | 否 | 默认 `MEDIUM` |
| `timeoutMs` | 否 | 默认 30s |
| `maxRetries` | 否 | 默认 3 |

---

## TaskStore 持久化

实现 `TaskStore` 接口即可扩展（DB / Redis / Kafka 等）：

```java
public interface TaskStore extends AutoCloseable {
    void saveTask(Task<?> task, TaskStatus status);
    void updateStatus(String taskId, TaskStatus status);
    void saveResult(TaskResult<?> result);
    Task<?> getTask(String taskId);
    TaskStatus getStatus(String taskId);
    TaskResult<?> getResult(String taskId);
    List<Task<?>> getRecoverableTasks();
    void removeTask(String taskId);
    void clear();
}
```

应用重启时 `TaskManager.recover()` 自动从 `store.getRecoverableTasks()` 加载 `PENDING` / `RUNNING` 任务并重置为 `PENDING`。

---

## TaskExecutor SPI

```java
@Component
public class ImageGenExecutor implements TaskExecutor<ImageGenRequest> {
    @Override
    public String taskType() { return "image.generate"; }

    @Override
    public TaskResult<ImageGenResponse> execute(Task<ImageGenRequest> task) {
        try {
            ImageGenResponse resp = callExternalApi(task.getPayload());
            return TaskResult.success(task.getTaskId(), resp);
        } catch (Exception e) {
            return TaskResult.failure(task.getTaskId(), e.getMessage());
        }
    }
}
```

注册：`@Component` 自动扫描即可（`AutoConfiguration` 通过 `List<TaskExecutor<?>>` 注入并注册）。

---

## TaskManager 状态机

```
            ┌──────────┐
  addTask() │ PENDING  │ recover()
   ────────►│          │◄────────
            └────┬─────┘
                 │ dispatch()
                 ▼
            ┌──────────┐
            │ RUNNING  │ │ timeoutMs
            │          │─► TIMEOUT
            └────┬─────┘
                 │ execute()
        ┌────────┴────────┐
        ▼                 ▼
   ┌────────┐       ┌────────┐
   │SUCCESS │       │ FAILED │
   └────────┘       └────────┘
                         │ retryCount < maxRetries
                         ▼
                   (reschedule)
```

## MdcDecorator

```java
public final class MdcDecorator {
    public static final String KEY_TASK_ID = "taskId";
    public static final String KEY_TRACE_ID = "traceId";

    public static Runnable decorate(Runnable runnable);
    public static Runnable decorate(Runnable runnable, String key, String value);
}
```

- `decorate(Runnable)`: 快照当前线程 MDC，目标线程运行后还原（finally 清理）
- `decorate(Runnable, key, value)`: 注入单键值
- 使用 `MDC.putCloseable` 模式自动 try-with-resources

---

## 已知限制

| 项 | 现状 | 备注 |
|---|---|---|
| 指标 | 无 Micrometer | 计划接入 Spring Boot Actuator |
| 重试退避 | `getRetryableTasks()` 仅返回，调度器未触发 | 需手动 reschedule |
| 死信队列 | 无 | FAILED 后直接清理 |
| 熔断器 | 无 | 计划接入 |
| 单元测试 | 0 个 | 需补 |