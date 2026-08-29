# Task Distribution Architecture

PlantUML 源文件。可通过 [PlantUML 在线渲染](https://www.plantuml.com/plantuml/uml/) 或 VS Code `plantuml` 扩展查看。

---

## 1. 模块依赖架构

```plantuml
@startuml
!define RECTANGLE class

skinparam componentStyle rectangle
skinparam backgroundColor #FEFEFE

package "utils-support-common-starter\n(com.chua.common.support.taskdistribution)" {
    rectangle "Task\n(task/Task.java)" as Task
    rectangle "TaskResult\n(task/TaskResult.java)" as TaskResult
    rectangle "TaskStatus\n(task/TaskStatus.java)" as TaskStatus
    rectangle "TaskPriority\n(task/TaskPriority.java)" as TaskPriority

    rectangle "TaskManager\n(manager/)" as TM
    rectangle "ResultBuffer\n(manager/)" as RB
    rectangle "TaskStateListener\n(manager/)" as TSL

    rectangle "DispatcherProvider\n(dispatcher/)" as DP
    rectangle "InMemoryDispatcherProvider\n(dispatcher/)" as IMDP
    rectangle "MdcDecorator\n(dispatcher/)" as MDC

    rectangle "TaskExecutor (SPI)\n(spi/)" as TE
    rectangle "TaskExecutorRegistry\n(spi/)" as TER

    rectangle "TaskStore (SPI)\n(store/)" as TS
    rectangle "InMemoryTaskStore\n(store/)" as IMTS
    rectangle "FileTaskStore\n(store/)" as FTS

    rectangle "NodeTable\n(node/)" as NT
    rectangle "NodeMeta\n(node/)" as NM

    rectangle "TcpScatterGatherNodeServer\n(scattergather/)" as TSGS
    rectangle "UdpScatterGatherNodeServer\n(scattergather/)" as USGS

    rectangle "DispatchStrategy\n(strategy/)" as DST
}

package "spring-support-taskdistribution-starter" {
    rectangle "TaskDistributionAutoConfiguration" as AC
    rectangle "TaskDistributionProperties\n(@ConfigurationProperties)" as PROP
    rectangle "TaskDistributionController\n(/v2/taskdistribution)" as CTRL
    rectangle "DbTaskStore\n(MyBatis-Plus)" as DTS
    rectangle "SysTaskStore\n(@TableName)" as SYS
    rectangle "TaskStoreMapper\n(BaseMapper)" as MAPPER
}

Task --> TaskStatus : has
Task --> TaskPriority : has
Task --> TaskResult : produces
TaskResult --> TaskStatus : has

TM --> Task : manages
TM --> TaskResult : handles
TM --> TS : persists
TM --> TSL : notifies
TM --> MDC : uses
TM --> RB : forwards

DP --> Task : receives
DP --> TaskResult : receives
IMDP .|> DP : implements
IMDP --> MDC : decorates listener.onTask

TS --> Task : saves/loads
TS --> TaskResult : saves
IMTS .|> TS
FTS .|> TS

TE --> Task : executes
TE --> TaskResult : produces
TER --> TE : registers

NT --> NM : tracks

TSGS .>> DP : : extends
USGS .>> DP : : extends

AC --> PROP : binds
AC --> TS : selects
AC --> TM : instantiates
AC --> DP : instantiates
AC --> TER : registers
AC --> DTS : instantiates (store-type=db)
AC --> CTRL : registers

DTS .|> TS : implements
DTS --> MAPPER : uses
MAPPER --> SYS : persists

CTRL --> TM : invokes
CTRL --> DP : invokes
CTRL --> RB : invokes
CTRL --> NT : invokes

@enduml
```

---

## 2. 时序图：任务提交-派发-执行-结果

```plantuml
@startuml

skinparam backgroundColor #FEFEFE
skinparam sequenceMessageAlign center

actor "用户/调用方" as User
participant "TaskManager" as TM
participant "TaskStore" as TS
participant "DispatcherProvider\n(InMemory)" as DP
participant "MdcDecorator" as MDC
participant "TaskExecutor\n(用户实现)" as TE
participant "ResultBuffer" as RB
participant "TaskStateListener" as TSL

User -> TM: addTask(task, callback)
activate TM
TM -> TS: saveTask(task, PENDING)
TM -> DP: dispatch(task) [间接]
TM --> User: TaskHolder
deactivate TM

== 异步消费 ==

DP -> DP: priority queue.poll()
activate DP
DP -> MDC: decorate(listener.onTask)
MDC -> MDC: snapshot MDC
DP -> MDC: MDC.put(taskId/traceId)
note right of MDC
  跨线程上下文传递
  原始 MDC 在 finally 中还原
end note
DP -> TE: onTask(task)
activate TE
TE -> TE: execute(task) (业务逻辑)
TE --> DP: TaskResult<T>
deactivate TE
DP -> MDC: MDC.remove(taskId/traceId)

DP -> DP: queue.offer(result)
DP -> TM: handleResult(result)
activate TM
TM -> TM: updateStatus(SUCCESS/FAILED)
TM -> TS: saveResult(result)
TM -> RB: put(result)
TM -> User: callback.onResult/onError
TM -> TSL: onCompleted(result)
TM -> TSL: onStateChanged(...)
deactivate TM

== 持久化 MDC ==

note over TM, TS
  DbTaskStore.saveTask/updateStatus/saveResult
  内部 MDC.put(taskId/traceId) → upsert → MDC.remove
  (操作日志带 taskId 上下文)
end note

@enduml
```

---

## 3. 时序图：超时检测

```plantuml
@startuml

skinparam backgroundColor #FEFEFE

participant "timeoutScheduler\n(ScheduledExecutorService)" as SCHED
participant "MdcDecorator" as MDC
participant "TaskManager" as TM
participant "TaskStore" as TS
participant "TaskCallback" as CB

loop 每 1s
    SCHED -> MDC: decorate(checkTimeouts)
    activate MDC
    MDC -> TM: checkTimeouts()
    activate TM
    TM -> TM: 遍历 tasks.values()
    loop 每个 RUNNING/PENDING 任务
        TM -> TM: elapsed > timeoutMs ?
        alt 超时
            TM -> MDC: MDC.put(taskId)
            TM -> TM: updateStatus(TIMEOUT)
            TM -> TS: updateStatus(TIMEOUT)
            TM -> CB: onTimeout(taskId)
            TM -> MDC: MDC.remove(taskId)
        end
    end
    deactivate TM
    deactivate MDC
end

note over TM
  使用 MdcDecorator 包裹确保
  超时日志与派发日志链路连续
end note

@enduml
```

---

## 4. 类图：核心实体

```plantuml
@startuml

skinparam classAttributeIconSize 0
skinparam backgroundColor #FEFEFE

class Task<T> {
    +taskId: String
    +traceId: String
    +parentTaskId: String
    +taskType: String
    +payload: T
    +tags: Map<String,String>
    +priority: TaskPriority
    +timeoutMs: long
    +maxRetries: int
    +shardCount: int
    +shardKey: String
    +sourceNodeId: String
    +builder(): TaskBuilder
}

class TaskResult<T> {
    +taskId: String
    +success: boolean
    +data: T
    +errorMessage: String
    +startedAt: long
    +finishedAt: long
    +success(taskId, data): TaskResult
    +failure(taskId, error): TaskResult
}

enum TaskStatus {
    PENDING
    RUNNING
    SUCCESS
    FAILED
    CANCELLED
    PAUSED
    TIMEOUT
}

enum TaskPriority {
    LOW
    MEDIUM
    HIGH
    REALTIME
}

class TaskCallback {
    +onResult(result)
    +onError(taskId, message)
    +onTimeout(taskId)
}

class TaskHolder {
    -task: Task<?>
    -callback: TaskCallback
    -status: TaskStatus
    -retryCount: int
    -createdAt: long
    -completedAt: long
    -result: TaskResult<?>
}

class TaskView {
    +taskId: String
    +taskType: String
    +status: TaskStatus
    +retryCount: int
    +createdAt: long
    +completedAt: long
}

Task --> TaskPriority
Task --> TaskStatus : initial=PENDING
TaskHolder "1" *--> "1" Task : wraps
TaskHolder "1" *--> "0..1" TaskCallback : wraps
TaskHolder --> TaskStatus
TaskResult --> TaskStatus
TaskResult ..> Task : produced by

@enduml
```

---

## 5. 包结构

```plantuml
@startuml

skinparam backgroundColor #FEFEFE

package "com.chua.common.support.taskdistribution" {
    package "dispatcher" {
        class DispatcherProvider
        class InMemoryDispatcherProvider
        class DispatcherListener
        class MdcDecorator
    }
    package "manager" {
        class TaskManager
        class TaskStateListener
        class ResultBuffer
    }
    package "node" {
        class NodeTable
        class Node
        class NodeMeta
    }
    package "scattergather" {
        class TcpScatterGatherNodeServer
        class TcpScatterGatherRemoteClient
        class UdpScatterGatherNodeServer
        class UdpScatterGatherRemoteClient
        class SyncMessageListenerAdapter
        class ScatterGatherResultWithRequestId
    }
    package "spi" {
        interface TaskExecutor
        class TaskExecutorRegistry
    }
    package "store" {
        interface TaskStore
        class InMemoryTaskStore
        class FileTaskStore
    }
    package "strategy" {
        enum DispatchStrategy
    }
    package "task" {
        class Task
        class TaskResult
        enum TaskStatus
        enum TaskPriority
        class TaskCallback
        class TaskDeduplicator
        class TaskIdGenerator
    }
}

@enduml
```

---

## 6. 集群模式：散射-汇聚

```plantuml
@startuml

skinparam backgroundColor #FEFEFE

node "发布端" {
    [Publisher] --> [TaskManager]
}

node "工作端 1" {
    [TaskExecutor-1]
}

node "工作端 2" {
    [TaskExecutor-2]
}

node "工作端 N" {
    [TaskExecutor-N]
}

[Publisher] --> [TcpScatterGatherNodeServer\n或 UdpScatterGatherNodeServer]
[TcpScatterGatherNodeServer] --> [TaskExecutor-1] : TCP
[TcpScatterGatherNodeServer] --> [TaskExecutor-2] : TCP
[TcpScatterGatherNodeServer] --> [TaskExecutor-N] : TCP

[TaskExecutor-1] --> [Publisher] : 回调
[TaskExecutor-2] --> [Publisher] : 回调
[TaskExecutor-N] --> [Publisher] : 回调

note right of [Publisher]
  节点间基于 tags 路由
  散射 N → 汇聚 1
end note

@enduml
```

---

## 7. 数据流：DB 持久化模式

```plantuml
@startuml

skinparam backgroundColor #FEFEFE

database "MySQL / H2 / PostgreSQL" as DB

package "spring-support-taskdistribution-starter" {
    [DbTaskStore] --> [TaskStoreMapper]
    [TaskStoreMapper] --> [SysTaskStore]
}

package "MyBatis-Plus" {
    [TaskStoreMapper] --> DB : SQL
}

DB : sys_task_store\n- task_id PK\n- task_type\n- task_status\n- task_json\n- result_json\n- create_time\n- update_time

note bottom of DB
  task_json / result_json 使用
  Json.toJson(Task/TaskResult) 序列化
  反序列化用 Json.fromJson
end note

@enduml
```