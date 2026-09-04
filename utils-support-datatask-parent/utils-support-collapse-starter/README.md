# utils-support-collapse-starter

utils-support-collapse-starter module

基于 collapse-executor 思想的请求折叠（Request Collapse）默认实现，通过 SPI 自动发现接入。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-collapse-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用

通过 common 门面 `CollapseFlow` 直接使用（无需 Spring）：

```java
// 合并拆分模式：多调用者小集合 -> 并集执行一次 -> 按归属拆分子 Map 回填
CollapseFlow<List<Long>, Map<Long, User>> flow = CollapseFlow.ofMapped("user-batch", callers -> {
    Set<Long> union = new LinkedHashSet<>();
    callers.forEach(union::addAll);
    Map<Long, User> full = userMapper.selectByIds(union);      // 核心方法：一次批量查询
    Map<List<Long>, Map<Long, User>> split = new LinkedHashMap<>();
    for (List<Long> ids : callers) {
        split.put(ids, pick(full, ids));                        // 按归属拆分
    }
    return split;
});
flow.threshold(20).collectingWaitTime(2);
Map<Long, User> mine = flow.execute(myIds);                     // 并发调用自动折叠合并

// 同参折叠模式：相同入参的并发调用合并为一次执行，结果广播
CollapseFlow<String, String> dedup = CollapseFlow.of("token", keys -> tokenService.get(keys));
```

Spring 环境下优先使用 `@Collapsible` 注解（见 utils-support-spring-starter / utils-support-springboot-starter）。

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DefaultCollapseExecutor` | 请求折叠默认实现：合并拆分（resultMapper 模式，Map 返回按元素归属回填）/ 同参折叠（batchFunction 模式，equals 分组广播）；CAS 单收集者 + 无锁队列 + yield/sleep 补收窗口；收集调度与批量执行默认虚拟线程（JDK 21+），平台线程兜底 |
| `DefaultCollapseExecutorFactory` | `@Spi("collapse")` 工厂实现，经 `META-INF/services/com.chua.common.support.concurrent.collapse.CollapseExecutorFactory` 注册 |

---

## 配置说明

本模块为零配置模块：引入依赖后经 SPI 自动发现（`CollapseFlow` / `@Collapsible` / `CollapseHttpClient` 均自动接入折叠实现）；未引入本模块时上述入口自动降级为直接执行（语义不变、无折叠收益）。

---

## 依赖关系

```
utils-support-collapse-starter
├── utils-support-common-starter
```
