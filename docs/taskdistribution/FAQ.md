# 故障排查与 FAQ

> 收集 taskdistribution 子系统高频踩坑场景。每条采用「症状 → 根因 → 解决」三段式结构。

---

## 1. 启动日志警告 `storeType=db 但未引入 mybatis 依赖或未配置数据源，回退到内存存储`

**症状**：
```
WARN  TaskDistributionAutoConfiguration : storeType=db 但未引入 mybatis 依赖或未配置数据源，回退到内存存储
```

**根因**：
- 缺少 `mybatis-plus-spring` 依赖（Spring Boot 4.1.0 不再传递）
- 或者数据源 `DataSource` 未配置
- 或者 `TaskStoreMapper` 未被 `@MapperScan` 扫描

**解决**：
1. 确认 `pom.xml`：
   ```xml
   <dependency>
       <groupId>com.baomidou</groupId>
       <artifactId>mybatis-plus-spring</artifactId>
       <version>3.5.16</version>
   </dependency>
   ```
2. 主类添加 `@MapperScan("com.chua.starter.taskdistribution.support.mapper")`
3. 确认 `application.yml` 配置了 `spring.datasource.*`
4. 执行建表 SQL（见 [README](../../../../../spring-support-taskdistribution-starter/README.md#数据库模式)）

---

## 2. 任务一直卡在 RUNNING 状态不结束

**症状**：
- 管理接口 `GET /v2/taskdistribution/task/list` 返回 `status=RUNNING`
- 工作端无对应日志
- `taskdistribution.task.timeout-ms` 设置很大但不生效

**根因**：
- `TaskManager.checkTimeouts()` 检测的是 `now - createdAt > timeoutMs`，**绝对时间**而非执行时间
- 如果 `TaskExecutor.execute()` 阻塞或死锁，任务不会自然到 SUCCESS/FAILED
- 调度器在 `TaskManager(true)` 构造时启动（默认 1s 检测间隔）

**解决**：
1. **首先**确认 `TaskExecutor` 没有阻塞或死锁：
   ```java
   // 错误：阻塞 I/O
   String result = httpClient.blockingGet(url);

   // 正确：异步或带超时
   String result = httpClient.blockingGet(url, 5, TimeUnit.SECONDS);
   ```
2. **临时方案**手动取消：
   ```bash
   curl -X POST http://localhost:8080/v2/taskdistribution/task/{taskId}/cancel
   ```
3. **长期方案**：
   - 接入线程转储（`jstack` / `arthas thread`）分析阻塞点
   - 添加 `slf4j-mdc` 或 OpenTelemetry 追踪执行路径
   - 监控 `pendingCount` 并设置告警

---

## 3. MDC 上下文（traceId）跨线程后丢失

**症状**：
- HTTP 请求日志有 `traceId=abc-123`
- 工作端 `TaskExecutor.execute()` 内日志无 `traceId`
- 数据库持久化日志（`DbTaskStore`）有时有 `taskId` 有时无

**根因**：
- `TaskManager` 启动 `ScheduledExecutorService` 时未装饰，新线程不继承 MDC
- 用户自定义线程池（如 `Executors.newFixedThreadPool`）未设置 `TaskDecorator`
- 工作端未启用 `spring-support-common-starter` 的 `TraceMessageConverter`

**解决**：
1. **框架内已修复**：`MdcDecorator.decorate()` 包裹 `timeoutScheduler`（见 `TaskManager`）
2. **自定义线程池**必须用 `MdcTaskDecorator`（在 `spring-support-common-starter`）：
   ```java
   @Bean
   public ThreadPoolTaskExecutor taskExecutor() {
       ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
       exec.setTaskDecorator(new MdcTaskDecorator());
       return exec;
   }
   ```
3. **Logback pattern** 添加 MDC 输出：
   ```xml
   <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %X{traceId:-} %X{taskId:-} %logger{36} - %msg%n</pattern>
   ```

---

## 4. 文件存储 `FileTaskStore` 启动报 `加载数据目录失败` / `加载任务文件失败`

**症状**：
```
WARN  FileTaskStore : 创建数据目录失败: ...
WARN  FileTaskStore : 加载任务文件失败: task-{id}.json, ...
```

**根因**：
- `taskdistribution.store-path` 指定的目录无写权限
- 之前保存的 JSON 文件损坏（手工编辑、应用崩溃时未 flush）

**解决**：
1. **权限问题**：
   ```bash
   # Linux/Mac
   chown -R app:app ./data/taskstore/
   chmod 755 ./data/taskstore/

   # Windows
   # 给运行用户授予目录读写权限
   ```
2. **文件损坏**：
   - 备份 `data/taskstore/`
   - 删除所有 `task-*.json` 与 `result-*.json`
   - 重启应用（`loadFromDb` 在构造时调用，会跳过损坏文件并打 warn）
3. **生产环境**不要用文件存储（无并发锁、无原子重命名），改用 DB 存储。

---

## 5. 自定义 `TaskExecutor` 没有被自动注册

**症状**：
- `@Component` 注解了 `TaskExecutor` 实现
- 应用启动日志无 `注册任务执行器: ...`
- 任务派发后无响应，`status` 一直 `PENDING`

**根因**：
- 包路径不在 Spring 扫描范围内
- `taskExecutorRegistry` Bean 被用户 `@ConditionalOnMissingBean` 覆盖

**解决**：
1. **包路径问题**：
   ```java
   @SpringBootApplication(scanBasePackages = {
       "com.your.app",
       "com.chua.starter.taskdistribution.support"  // 加上 starter 包
   })
   ```
2. **手动注册**（如果不想用 `@Component`）：
   ```java
   @Bean
   public ApplicationRunner registerExecutors(TaskExecutorRegistry registry) {
       return args -> {
           registry.register(new EmailSendExecutor());
           registry.register(new ReportGenExecutor());
       };
   }
   ```

---

## 6. `taskdistribution.strategy` 配置不生效

**症状**：
- `application.yml` 设置 `taskdistribution.strategy: WEIGHT`
- 工作端轮询访问（`ROUND` 行为）

**根因**：
- `DispatchStrategy` 是**散射-汇聚（scatter-gather）模式**的中间件选择策略
- **不控制** `InMemoryDispatcherProvider` 的本地消费
- 仅在多工作端（`taskdistribution.server.enabled=true`）的散射广播时生效

**解决**：
- 如果只是单机模式，`strategy` 配置**没有作用**
- 集群模式需要启动 `taskdistribution.server.*`：
   ```yaml
   taskdistribution:
     server:
       enabled: true
       port: 19390
       protocol: tcp
     strategy: WEIGHT
   ```

---

## 7. 任务执行成功但回调 `onResult` 没触发

**症状**：
- `TaskExecutor.execute()` 返回 `TaskResult.success(...)`
- 业务侧 `TaskCallback.onResult` 未调用
- 数据库/内存 Store 中结果已保存

**根因**：
- `TaskManager.handleResult()` 内部 `TaskCallback.onResult/onError/onTimeout` 触发逻辑分三支：
   ```java
   if (result.isSuccess()) {
       updateStatus(SUCCESS);
       if (holder.callback != null) holder.callback.onResult(result);
   } else {
       updateStatus(FAILED);
       if (holder.callback != null) holder.callback.onError(...);
   }
   ```
- 如果 `addTask(task, null)` 时未传 `callback`，则不会触发
- `addTask(task, callback)` 之后 `taskManager.recover()` 重启后会清空 `callback`（仅恢复状态）

**解决**：
- 调用 `addTask` 时**必须传 callback**（即使是 `noop` 形式）
- 重启场景：自定义持久化的 callback 引用，建议实现 `TaskCallbackProvider` SPI（目前无），或改用 `ResultBuffer.getResult(taskId)` 主动轮询

---

## 8. 任务重复派发（同一 taskId 被处理多次）

**症状**：
- 业务方说"我只发了一次任务"
- 工作端日志显示该 taskId 多次执行

**根因**：
- 重试机制：失败任务 `getRetryableTasks()` 返回但**无调度器**触发重试（当前未实现）
- 客户端多次调用 `addTask` 传了同一 `taskId`：`TaskManager.addTask` **不检查重复**（会覆盖 `tasks` map 的 `TaskHolder`）
- 网络抖动：发布端多次重试，发布端无幂等

**解决**：
1. **客户端去重**：使用 `TaskDeduplicator`（`InMemoryDispatcherProvider.enableDeduplication(...)`）
2. **服务端去重**：`TaskManager.addTask` 已有逻辑（覆盖前会丢失原 callback），自定义实现建议：
   ```java
   if (tasks.containsKey(task.getTaskId())) {
       log.warn("任务已存在, 忽略重复提交: {}", task.getTaskId());
       return;
   }
   ```
3. **业务幂等**：`TaskExecutor` 内部用 `taskId` 做幂等键（Redis SETNX / DB 唯一索引）

---

## 9. 启动时 `TaskManager` 报错 `recover() 加载未完成任务失败`

**症状**：
```
ERROR TaskDistributionAutoConfiguration : 加载任务失败: ...
```

**根因**：
- 数据库表 `sys_task_store` 不存在或字段不匹配
- `taskJson` / `resultJson` 字段被截断（默认 MySQL `TEXT` 限制 64KB）
- 反序列化失败（`Task.payload` 类型缺失或版本不兼容）

**解决**：
1. **表结构**：见 README 建表 SQL
2. **大任务**：把 `task_json` 改为 `LONGTEXT`（最大 4GB）
3. **反序列化失败**：
   - 删除 `sys_task_store` 中 `task_status='PENDING'` 的行
   - 或扩展 JSON 格式兼容性

---

## 10. `taskdistribution.batch-size` 调整后不生效

**症状**：
- `application.yml` 设置 `batch-size: 10`
- 启动日志显示 `批量大小: 1`（默认值）

**根因**：
- `InMemoryDispatcherProvider` 在 `start()` 时构造，配置变更需要重启
- `@RefreshScope` 未启用
- 配置类实例化早于 `@ConfigurationProperties` 绑定

**解决**：
- 重启应用
- 未来改进方向：使用 `@RefreshScope + @EventListener(ApplicationReadyEvent)` 动态重设

---

## 11. `@Permission` 注解导致 `TaskDistributionController` 401 / 403

**症状**：
- 调用管理接口返回 401 Unauthorized
- 即使登录也返回 403 Forbidden

**根因**：
- `@Permission(value = "taskdistribution:task:list")` 需要 `spring-support-common-starter` 的权限拦截器
- 用户权限表中**没有** `taskdistribution:task:list` 这一行

**解决**：
1. 启动 SQL 插入权限数据：
   ```sql
   INSERT INTO sys_permission (perm_code, perm_name, module) VALUES
   ('taskdistribution:task:list',     '任务清单',     'taskdistribution'),
   ('taskdistribution:task:get',      '任务详情',     'taskdistribution'),
   ('taskdistribution:task:cancel',   '取消任务',     'taskdistribution'),
   ('taskdistribution:task:pause',    '暂停任务',     'taskdistribution'),
   ('taskdistribution:task:resume',   '恢复任务',     'taskdistribution'),
   ('taskdistribution:task:delete',   '删除任务',     'taskdistribution'),
   ('taskdistribution:node:list',     '节点清单',     'taskdistribution'),
   ('taskdistribution:node:delete',   '注销节点',     'taskdistribution');
   ```
2. 给目标角色分配这些权限
3. 或临时禁用：自定义配置 `chua.permission.enabled=false`（见 `spring-support-common-starter` 文档）

---

## 12. 散射-汇聚模式 UDP 数据包丢失

**症状**：
- UDP 集群模式部分工作端收不到任务
- 网络抓包显示 broadcast 正常发出

**根因**：
- UDP 无连接、无重传
- 大 payload（>MTU 1472）会被 IP 层分片，丢一片整包失败
- 防火墙/组播未配置

**解决**：
1. 任务 payload < 1KB：直接用 UDP
2. 任务 payload > 1KB：
   - 改用 TCP（`taskdistribution.server.protocol=tcp`）
   - 或压缩 payload：`gzip + base64`
3. 跨网段：禁用组播（`setBroadcast(true)` 改成单播列表），明确指定节点 IP

---

## 13. `MdcDecorator` 在 `TaskManager.checkTimeouts` 中不工作

**症状**：
- 超时日志无 `taskId`
- 但 `DbTaskStore.updateStatus` 日志有 `taskId`

**根因**：
- `MdcDecorator.decorate` 用 `MDC.getCopyOfContextMap()` 快照
- 启动时（HTTP 请求未到达）`MDC` 是空的，快照为空
- 超时检测启动时无任何上下文

**解决**：
- 这是**预期行为**：启动期没有外部 traceId
- 在 `MdcDecorator.decorate(this::checkTimeouts)` 之外，每个 task 内部显式 `MDC.put`：
   ```java
   // 已在 TaskManager.checkTimeouts 中实现
   try {
       if (taskId != null) MDC.put(MdcDecorator.KEY_TASK_ID, taskId);
       // ...
   } finally {
       MDC.remove(MdcDecorator.KEY_TASK_ID);
   }
   ```
- 升级 Logback 1.5+ 支持 `MDCAdapter` 全局注入

---

## 14. 集群模式下 `NodeTable` 中节点都是 `offline`

**症状**：
- 启动后 `GET /v2/taskdistribution/node/list` 返回 `online: false`
- 节点之间 TCP/UDP 通信正常

**根因**：
- `NodeTable` 心跳保活超时（默认 30s 无心跳判 offline）
- 启动时节点刚注册，心跳调度器未到第一个 tick
- 时间不同步（NTP 漂移 > 30s）

**解决**：
1. 等待 30s（默认心跳周期）
2. 检查 NTP：
   ```bash
   ntpdate -q pool.ntp.org
   ```
3. 自定义心跳：实现 `NodeHeartbeat` SPI（目前未公开）