# 扩展指南

> 三个 SPI 扩展点：自定义 `TaskExecutor`、自定义 `TaskStore`、自定义 `DispatchStrategy`。
> 每节采用「目标 → 步骤 → 完整代码 → 验证」四段式。

---

## 1. 自定义 TaskExecutor

### 目标

注册一个处理 `"image.generate"` 任务类型的执行器，调用外部 AI 服务生成图片。

### 步骤

1. 实现 `TaskExecutor<T>` 接口
2. `@Component` 注解（推荐）或手动 `TaskExecutorRegistry.register(...)`
3. 返回 `TaskResult.success/failure(...)`

### 完整代码

```java
package com.example.executor;

import com.chua.common.support.taskdistribution.spi.TaskExecutor;
import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenExecutor implements TaskExecutor<ImageGenRequest> {

    private final ImageGenClient client;

    @Override
    public String taskType() {
        return "image.generate";
    }

    @Override
    public TaskResult<ImageGenResponse> execute(Task<ImageGenRequest> task) {
        ImageGenRequest req = task.getPayload();
        try {
            log.info("生成图片: prompt={}", req.prompt());
            ImageGenResponse resp = client.generate(req, Duration.ofSeconds(30));
            return TaskResult.success(task.getTaskId(), resp);
        } catch (Exception e) {
            log.error("图片生成失败", e);
            return TaskResult.failure(task.getTaskId(), e.getMessage());
        }
    }

    public record ImageGenRequest(String prompt, int width, int height) {}
    public record ImageGenResponse(String url, long costMs) {}
}
```

### 验证

```java
// 提交任务
Task<ImageGenRequest> task = Task.<ImageGenRequest>builder()
    .taskType("image.generate")
    .payload(new ImageGenRequest("一只猫", 512, 512))
    .build();
taskManager.addTask(task, callback);
```

启动日志应包含 `注册任务执行器: image.generate`。

---

## 2. 自定义 TaskStore（Redis 实现）

### 目标

把任务持久化到 Redis，自动支持重启恢复、过期清理。

### 步骤

1. 实现 `TaskStore` 接口
2. 用 `RedisTemplate<String, String>` 存 JSON
3. 用 `Set` 维护可恢复任务索引
4. 注册为 Spring Bean（覆盖默认 `InMemoryTaskStore`）

### 完整代码

```java
package com.example.store;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.taskdistribution.store.TaskStore;
import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "taskdistribution", name = "store-type", havingValue = "redis")
public class RedisTaskStore implements TaskStore {

    private static final String TASK_KEY = "task:";
    private static final String RESULT_KEY = "result:";
    private static final String RECOVERABLE_SET = "task:recoverable";
    private static final long TTL_DAYS = 7;

    private final StringRedisTemplate redis;

    @Override
    public void saveTask(Task<?> task, TaskStatus status) {
        String json = Json.toJson(task);
        redis.opsForValue().set(TASK_KEY + task.getTaskId(), json, TTL_DAYS, TimeUnit.DAYS);
        if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
            redis.opsForSet().add(RECOVERABLE_SET, task.getTaskId());
        } else {
            redis.opsForSet().remove(RECOVERABLE_SET, task.getTaskId());
        }
    }

    @Override
    public void updateStatus(String taskId, TaskStatus status) {
        String taskJson = redis.opsForValue().get(TASK_KEY + taskId);
        if (taskJson == null) return;
        Task<?> task = Json.fromJson(taskJson, Task.class);
        saveTask(task, status);
    }

    @Override
    public void saveResult(TaskResult<?> result) {
        redis.opsForValue().set(RESULT_KEY + result.getTaskId(),
            Json.toJson(result), TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public Task<?> getTask(String taskId) {
        String json = redis.opsForValue().get(TASK_KEY + taskId);
        return json == null ? null : Json.fromJson(json, Task.class);
    }

    @Override
    public TaskStatus getStatus(String taskId) {
        Task<?> task = getTask(taskId);
        return task == null ? null : TaskStatus.valueOf(redis.opsForValue()
            .get(TASK_KEY + taskId + ":status"));
    }

    @Override
    public TaskResult<?> getResult(String taskId) {
        String json = redis.opsForValue().get(RESULT_KEY + taskId);
        return json == null ? null : Json.fromJson(json, TaskResult.class);
    }

    @Override
    public List<Task<?>> getRecoverableTasks() {
        Set<String> ids = redis.opsForSet().members(RECOVERABLE_SET);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Task<?>> result = new ArrayList<>();
        for (String id : ids) {
            Task<?> t = getTask(id);
            if (t != null) result.add(t);
        }
        return result;
    }

    @Override
    public void removeTask(String taskId) {
        redis.delete(TASK_KEY + taskId);
        redis.delete(RESULT_KEY + taskId);
        redis.opsForSet().remove(RECOVERABLE_SET, taskId);
    }

    @Override
    public void clear() {
        Set<String> keys = redis.keys(TASK_KEY + "*");
        if (keys != null) redis.delete(keys);
        Set<String> rkeys = redis.keys(RESULT_KEY + "*");
        if (rkeys != null) redis.delete(rkeys);
        redis.delete(RECOVERABLE_SET);
    }

    @Override
    public void close() {
        // Redis 连接由 Spring 容器管理，无需显式关闭
    }
}
```

### 验证

```yaml
taskdistribution:
  store-type: redis
```

启动日志应包含 `taskStore` Bean 创建为 `RedisTaskStore`（可用 `--debug` 模式确认）。

---

## 3. 自定义 DispatchStrategy（最少使用算法）

### 目标

实现 LFU（最不常用）选择工作端。

### 步骤

1. 实现 `DispatchStrategy` 枚举的**扩展**（枚举不可继承，需用包装）
2. 实际上 `DispatchStrategy` 是 enum，扩展策略需在 `NodeTable.selectNode(strategy, ...)` 中分支
3. 临时方案：扩展 `DispatchStrategy` 添加新枚举值 + 在 `NodeTable` 中加分支

### 完整代码（修改源码）

```java
// 1) 在 DispatchStrategy.java 中加新值
public enum DispatchStrategy {
    FIRST, LAST, RANDOM, ROUND, WEIGHT, LFU
}

// 2) 在 NodeTable.java 的 selectNode 方法中加分支
public Node selectNode(DispatchStrategy strategy, Map<String, String> tags) {
    List<Node> candidates = filterByTags(tags);
    if (candidates.isEmpty()) return null;
    switch (strategy) {
        case LFU:
            return candidates.stream()
                .min(Comparator.comparingLong(Node::getCallCount))
                .orElse(candidates.get(0));
        // ... 其他 case
    }
}
```

### 验证

```yaml
taskdistribution:
  strategy: LFU
```

启动后观察各工作端 `callCount` 增长曲线，应保持相对均衡。

---

## 4. 自定义 DispatcherProvider（Kafka 跨节点派发）

### 目标

把任务发布到 Kafka topic，跨节点消费。实现 `DispatcherProvider` 接口。

### 完整代码

```java
package com.example.dispatcher;

import com.chua.common.support.taskdistribution.dispatcher.DispatcherListener;
import com.chua.common.support.taskdistribution.dispatcher.DispatcherProvider;
import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "taskdistribution", name = "dispatcher", havingValue = "kafka")
public class KafkaDispatcherProvider implements DispatcherProvider {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private DispatcherListener listener;
    private volatile boolean running = true;

    @Override
    public void receive(Task<?> task) {
        try {
            String json = mapper.writeValueAsString(task);
            kafka.send("taskdistribution-task", task.getTaskId(), json);
        } catch (Exception e) {
            log.error("Kafka 发送任务失败", e);
        }
    }

    @Override
    public void receive(TaskResult<?> result) {
        try {
            String json = mapper.writeValueAsString(result);
            kafka.send("taskdistribution-result", result.getTaskId(), json);
        } catch (Exception e) {
            log.error("Kafka 发送结果失败", e);
        }
    }

    @Override
    public DispatcherProvider listener(DispatcherListener listener) {
        this.listener = listener;
        return this;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public int pendingCount() {
        return 0; // Kafka 队列深度需另查
    }

    @Override
    public void close() {
        running = false;
    }

    // 消费任务
    @KafkaListener(topics = "taskdistribution-task", groupId = "${spring.application.name}")
    public void onTaskMessage(String json) {
        if (listener == null) return;
        try {
            Task<?> task = mapper.readValue(json, Task.class);
            listener.onTask(task);
        } catch (Exception e) {
            log.error("Kafka 任务消费失败", e);
        }
    }

    // 消费结果
    @KafkaListener(topics = "taskdistribution-result", groupId = "${spring.application.name}-result")
    public void onResultMessage(String json) {
        if (listener == null) return;
        try {
            TaskResult<?> result = mapper.readValue(json, TaskResult.class);
            listener.onResult(result);
        } catch (Exception e) {
            log.error("Kafka 结果消费失败", e);
        }
    }
}
```

### 验证

```yaml
taskdistribution:
  dispatcher: kafka
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

---

## 5. 自定义 MdcDecorator 行为

### 目标

除 `taskId` / `traceId` 外注入业务字段（如 `userId`）到 MDC。

### 完整代码

```java
public class UserIdMdcDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            try {
                if (snapshot != null) MDC.setContextMap(snapshot);
                MDC.put("userId", Optional.ofNullable(snapshot)
                    .map(m -> m.get("userId")).orElse("anonymous"));
                runnable.run();
            } finally {
                if (prev != null) MDC.setContextMap(prev); else MDC.clear();
            }
        };
    }
}

// 注入
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setTaskDecorator(new UserIdMdcDecorator());
    return exec;
}
```

---

## 提交贡献

- 提交到 `spring-support-parent-starter/spring-support-taskdistribution-starter/src/main/java/com/chua/starter/taskdistribution/support/example/`
- 包含完整单元测试（`src/test/java/.../example/`）
- 在 PR 中描述业务场景与权衡