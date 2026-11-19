# utils-support-runtime-spy

> 运行时**字节码插桩引擎**：基于 ASM 在目标方法入口 / 出口 / 异常出口织入 `RuntimeSpy.onIntercept(...)` / `onException(...)` 调用，配合 `Instrumentation` 完成类转换，并把插桩事件按「类#方法#插桩点」路由给注册的 `Interceptor`，同时维护轻量 traceId/spanId 链路上下文与 SLF4J MDC 桥接。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.spy`｜自 `4.0.0.42` 引入

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 SpyBootstrap — 引擎启动与关闭](#22-spybootstrap--引擎启动与关闭)
  - [2.3 SpyTransformer — ASM 字节码转换器](#23-spytransformer--asm-字节码转换器)
  - [2.4 RuntimeSpy — 插桩运行时入口](#24-runtimespy--插桩运行时入口)
  - [2.5 InterceptContext — 插桩事件上下文](#25-interceptcontext--插桩事件上下文)
  - [2.6 InterceptPoint — 插桩点枚举](#26-interceptpoint--插桩点枚举)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 插桩 | ASM 字节码转换器：按 `className#methodName` 精确规则，在方法入口插 `pre` 系插桩点、出口插 `post` 系插桩点、异常出口插 `EXCEPTION` | 不是运行时代理 / AOP 代理库；不改方法业务语义 |
| 集成 | `Instrumentation` 集成：`addTransformer(transformer, true)` 注册可重变换转换器，支持对已加载类 `retransform` | 不做 attach / agentmain 入口（在 `utils-support-runtime-agent`） |
| 事件 | 插桩事件路由器：维护 `Interceptor` 注册表，按 `className#methodName#pointKey` 分发 `InterceptContext` | 不是事件总线 / MQ；事件只同步分发给注册的拦截器 |
| 追踪 | 轻量链路上下文：线程局部 traceId/spanId 栈、跨线程 `capture/restore`、SLF4J MDC 桥接 | 不是完整 APM（指标聚合与存储在 `utils-support-runtime-apm`） |
| 依赖 | 只依赖 `utils-support-runtime-plugin`、`utils-support-common-starter`、ASM、ASM Commons | 不依赖 Spring、数据库驱动、任何 APM SDK |

**判断口径**：所有与「把字节码改掉、把事件递出去」有关的机制在本模块；「拦什么、存什么、上报到哪」由插件与上层模块决定。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 启动插桩引擎 | `SpyBootstrap.init(agentArgs, instrumentation)` | 注册转换器 + 解析参数 + 加载插件，返回是否成功 |
| 注册拦截器 | `RuntimeSpy.registerInterceptor(className, methodName, descriptor, point, interceptor)` | 规则同步到转换器并对已加载类触发 retransform |
| 拦截器接口 | `RuntimeSpy.Interceptor#onIntercept(InterceptContext)` | 拿到类名/方法名/插桩点/traceId/spanId 等 |
| 声明插桩点 | `com.chua.runtime.plugin.InterceptPoint` | `ENTRY`/`EXIT`/`EXCEPTION`/`LOG_PRE`/`NET_CONNECT_PRE` 等 |
| 跨线程传播追踪 | `RuntimeSpy.wrap(task)` 或 `capture()` + `restore(snapshot)` | Runnable / Callable / Thread 三种包装 |
| 按 key 跨线程传递 | `RuntimeSpy.captureAsKey(key)` / `restoreByKey(key)` | 有界缓存：上限 10000 条、TTL 1 小时 |
| 读当前链路 | `RuntimeSpy.getCurrentTraceId()` / `getCurrentSpanId()` | 栈空返回 `null` |
| 业务日志带追踪 ID | `RuntimeSpy.putMdc(key, value)` / `clearMdc()` | 反射调用 SLF4J MDC，classpath 无 SLF4J 时静默跳过 |
| 清理状态 | `RuntimeSpy.clear()` / `clearThreadLocal()` | 全局清理 / 仅当前线程清理 |

### 2.2 SpyBootstrap — 引擎启动与关闭

```java
import com.chua.runtime.spy.SpyBootstrap;
import java.lang.instrument.Instrumentation;

// 初始化：解析 agent 参数 → 注册 SpyTransformer → 初始化 PluginManager 并加载插件
boolean ok = SpyBootstrap.init(agentArgs, instrumentation);

// 读取最近一次解析的 agent 参数（key 已小写，只读快照，永不为 null）
Map<String, String> options = SpyBootstrap.agentOptions();

// 状态与组件
SpyBootstrap.isInitialized();             // 是否已初始化
SpyBootstrap.getInstrumentation();        // Instrumentation 实例
SpyBootstrap.getTransformer();            // SpyTransformer 实例
SpyBootstrap.getPluginManager();          // 插件管理器
SpyBootstrap.getTransformedClassCount();  // 已插桩类数量

// 关闭：移除转换器 + 停止插件管理器
SpyBootstrap.destroy();
```

- **attach 模式**：`initTransformer` 只注册 retransformable transformer，**不做全量 retransform**（源码注释：对运行中 JVM 逐类 retransform 会触发 `InternalError: invalid class` 或长时间挂起）；对未来加载的类生效，已加载类靠 `registerInterceptor` 的同步逻辑触发单类 retransform。
- **类过滤（硬编码常量）**：
  - include：`com.example.*`、`org.*`、`java.net.*`、`java.io.*`
  - exclude：`java.lang.*`、`com.chua.*`
- `registerInterceptPoint(point, descriptor)` 为旧式入口，现仅打印日志，规则注册已迁移至精确规则（`registerMethod`）。

### 2.3 SpyTransformer — ASM 字节码转换器

```java
import com.chua.runtime.spy.SpyTransformer;
import com.chua.runtime.plugin.InterceptPoint;

// 精确插桩规则：className#methodName -> 插桩点集合
transformer.registerMethod("org/slf4j/Logger", "info", InterceptPoint.LOG_PRE);
transformer.unregisterMethod("org/slf4j/Logger", "info", InterceptPoint.LOG_PRE);
transformer.hasMethodRule("org/slf4j/Logger", "info");   // 是否已注册

// 对已加载类批量 retransform（init 默认不调用，需要时显式触发）
transformer.retransformLoadedClasses(instrumentation);

// 字节码调用目标（默认 com/chua/runtime/agent/Bootstrap，位于系统 classloader）
transformer.setSpyClass("com/chua/runtime/spy/RuntimeSpy");  // 直接调用 RuntimeSpy（测试用）
transformer.getSpyClass();

transformer.getTransformedClassCount();  // 已插桩类数量
transformer.getMethodRuleCount();        // 精确规则数量
transformer.reset();                     // 清空规则与已插桩集合
```

织入原理（对 `void foo(int x)`）：

```text
方法入口：插入所有 pre 系插桩点 →
    INVOKESTATIC spyClass.onIntercept(thisRef, className, methodName, descriptor, pointKey)
方法出口：暂存返回值到临时本地变量 → 插入所有 post 系插桩点 → 恢复返回值
异常出口：try/catch 包裹方法体，catch 中调用
    INVOKESTATIC spyClass.onException(className, methodName, throwable) 后重新抛出
```

- ASM API 版本 `ASM9`，基于 `AdviceAdapter` 自动管理本地变量与 frame 合并。
- `ResolvingClassWriter` 用**目标类自身的 ClassLoader** 解析 `getCommonSuperClass`，避免三方库（如 MySQL Connector）签名中引用未加载类型时抛 `TypeNotPresentException`。
- 跳过规则：`<init>`/`<clinit>`（除非有精确规则）、无精确规则时的非 public/protected 方法、以及前缀 `com/chua/runtime/`、`java/lang/`、`sun/`、`jdk/`、`org/objectweb/asm`、`org/apache/commons/`。
- `thisRef`：所有插桩点都压入受拦截实例（静态方法传 `null`），拦截器可通过 `ctx.getUserData()` 拿到 Socket / HttpURLConnection / 文件流等原始对象。

### 2.4 RuntimeSpy — 插桩运行时入口

被 ASM 织入的静态门面，同时是拦截器的注册中心。

```java
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import com.chua.runtime.plugin.InterceptPoint;

// 注册 / 注销拦截器（注册会同步规则到 SpyTransformer 并 retransform 已加载类）
RuntimeSpy.registerInterceptor("org/slf4j/Logger", "info",
        "(Ljava/lang/String;)V", InterceptPoint.ENTRY, interceptor);
RuntimeSpy.unregisterInterceptor("org/slf4j/Logger", "info", InterceptPoint.ENTRY);
RuntimeSpy.unregisterAll(interceptor);            // 按处理器实例批量注销

// 被织入字节码调用的入口（一般无需手动调用）
RuntimeSpy.onIntercept(className, methodName, descriptor, pointKey, thisRef);
RuntimeSpy.onEntry(...);   // 等价 onIntercept
RuntimeSpy.onExit(...);    // 等价 onIntercept
RuntimeSpy.onException(className, methodName, throwable);

// 链路上下文
RuntimeSpy.getCurrentTraceId();        // 栈顶 traceId，栈空为 null
RuntimeSpy.getCurrentSpanId();         // 栈顶 spanId
RuntimeSpy.getTraceStackSize();        // 追踪栈深度
RuntimeSpy.currentContext();           // SpyContext(className, methodName, startTime)
RuntimeSpy.clearContext();

// MDC 桥接（键：traceId / spanId / className / methodName）
RuntimeSpy.putMdc("traceId", traceId);
RuntimeSpy.clearMdc();

// 统计与清理
RuntimeSpy.getInterceptCount();        // 插桩调用计数（线程局部累计）
RuntimeSpy.getRegisteredCount();       // 已注册拦截器数量
RuntimeSpy.clear();                    // 清空注册表 + 全部线程局部状态
RuntimeSpy.clearThreadLocal();         // 仅清当前线程（线程池复用场景兜底）
```

链路追踪栈：ENTRY 压栈（traceId 沿用父帧或新生成 16 字符 ID，spanId 每次新生成），EXIT/EXCEPTION 弹栈，父 spanId 即栈中下一帧；**追踪栈深度上限 256**，超限强制清空以防 AOP 死循环导致 OOM。

跨线程传播（三种方式）：

```java
// 方式一：包装任务（自动 capture/restore）
executor.submit(RuntimeSpy.wrap(runnable));
executor.submit(RuntimeSpy.wrap(callable));
RuntimeSpy.wrap(thread).start();

// 方式二：手动 capture / restore
RuntimeSpy.TraceContextSnapshot snapshot = RuntimeSpy.capture();
new Thread(() -> {
    RuntimeSpy.restore(snapshot);
    try { /* 业务 */ } finally { RuntimeSpy.clearThreadLocal(); }
}).start();

// 方式三：按 key 传递（适合 submit(runnable, key) 模式；缓存上限 10000 条、TTL 1 小时）
RuntimeSpy.captureAsKey("order-1");
RuntimeSpy.restoreByKey("order-1");
```

测试辅助（仅用于 e2e 断言，生产代码不应调用）：`generateIdForTest()`、`pushTraceForTest(traceId)`。

### 2.5 InterceptContext — 插桩事件上下文

`@Data @Builder` 的上下文对象，由 `RuntimeSpy.onIntercept` 构建后传给拦截器。

| 字段 / 方法 | 说明 |
| --- | --- |
| `getClassName()` | 目标类名（内部名，如 `org/slf4j/Logger`） |
| `getMethodName()` / `getDescriptor()` | 方法名 / 方法描述符（如 `(Ljava/lang/String;)V`） |
| `getPoint()` | 插桩点（`InterceptPoint`） |
| `getTimestamp()` | 事件时间戳（毫秒） |
| `getThrowable()` | 异常对象（仅 `EXCEPTION` 插桩点有效） |
| `getUserData()` | 用户附加数据——织入时传入的 `thisRef`（受拦截实例） |
| `getTraceId()` / `getSpanId()` / `getParentSpanId()` | 链路三元组（根调用 parentSpanId 为 `null`） |
| `setTraceStack(TraceStack)` | 一次性写入三元组（`TraceStack` 为不可变 record） |
| `getReadableClassName()` | 内部名 `/` 转 `.` |
| `getSignature()` | `"类.方法(描述符)"` |
| `isEntry()` / `isExit()` / `isException()` / `isRootSpan()` | 语义判断辅助 |

### 2.6 InterceptPoint — 插桩点枚举

定义于 `utils-support-runtime-plugin`（`com.chua.runtime.plugin.InterceptPoint`），`getKey()` 取小写 key，`of(key)` 反查（未匹配返回 `null`）：

| 枚举 | key | 织入位置 |
| --- | --- | --- |
| `ENTRY` | `entry` | 方法入口（pre） |
| `EXIT` | `exit` | 方法出口（post） |
| `EXCEPTION` | `exception` | 异常出口（try/catch） |
| `LOG_PRE` / `LOG_POST` | `log_pre` / `log_post` | 入口 / 出口 |
| `NET_CONNECT_PRE` / `NET_CONNECT_POST` | `net_connect_pre` / `net_connect_post` | 入口 / 出口 |
| `NET_READ_PRE` / `NET_WRITE_PRE` | `net_read_pre` / `net_write_pre` | 入口 |
| `FILE_OPEN_PRE` / `FILE_OPEN_POST` | `file_open_pre` / `file_open_post` | 入口 / 出口 |
| `FILE_READ_PRE` | `file_read_pre` | 入口 |
| `HTTP_REQUEST_PRE` / `HTTP_RESPONSE_POST` | `http_request_pre` / `http_response_post` | 入口 / 出口 |
| `DB_SQL_PRE` / `DB_SQL_POST` | `db_sql_pre` / `db_sql_post` | 入口 / 出口 |
| `THREAD_CREATE_PRE` / `CLASS_LOAD_PRE` | `thread_create_pre` / `class_load_pre` | 入口 |
| `DURATION` | `duration` | 枚举中定义，SpyTransformer 的 pre/post 均未包含（当前无织入逻辑） |

---

## 3. 快速开始

最小示例（不依赖 agent 环境，直接演示拦截器注册与跨线程传播）：

```java
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;

public class Demo {
    public static void main(String[] args) {
        // 1. 定义拦截器：打印方法签名与插桩点
        RuntimeSpy.Interceptor printer = ctx ->
                System.out.println(ctx.getSignature() + " @ " + ctx.getPoint().getKey());

        // 2. 注册精确拦截规则
        //    SpyBootstrap.init(...) 之后注册，规则会同步到 SpyTransformer
        //    并对已加载的目标类自动触发 retransform
        RuntimeSpy.registerInterceptor("org/slf4j/Logger", "info",
                "(Ljava/lang/String;)V", InterceptPoint.ENTRY, printer);

        // 3. 跨线程传播追踪上下文：子线程 ENTRY 插桩会作为原链路的子 Span
        Runnable task = RuntimeSpy.wrap(() ->
                System.out.println("traceId=" + RuntimeSpy.getCurrentTraceId()));
        new Thread(task).start();

        // 4. 退出前清理
        RuntimeSpy.clear();
    }
}
```

完整插桩流程（类加载时真正织入字节码）：

```text
1. Agent 入口（RuntimeAgent.agentmain / premain，位于 utils-support-runtime-agent）
      → SpyBootstrap.init(agentArgs, instrumentation)
2. Handler.start() 中调用 RuntimeSpy.registerInterceptor(...)
3. RuntimeSpy 把精确规则同步给 SpyTransformer（registerMethod）
4. 目标类加载（或 retransform）时，SpyTransformer 织入 RuntimeSpy.onIntercept(...) 字节码
5. 目标方法执行到插桩点 → RuntimeSpy.onIntercept(...) → 查表路由 → interceptor.onIntercept(ctx)
```

> Agent 的打包、attach 与启动参数细节属于 `utils-support-runtime-agent` / `utils-support-runtime-starter`，见对应模块文档。

---

## 4. 配置项

本模块无 Spring 配置前缀、无 `application.yml` 键；配置通过**系统属性**与 **agent 参数**传入：

| 配置 | 传入方式 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `runtime.plugin.dir` | 系统属性 | `${user.dir}/plugins` | 插件目录；`SpyBootstrap.init` 初始化 `PluginManager` 时读取 |
| Agent 参数 | `SpyBootstrap.init(args, inst)` 的 `args` | 空 | 格式 `key=value` 逗号分隔；按**首个** `=` 切分（value 可再含 `=`），key 统一转小写；经 `SpyBootstrap.agentOptions()` 只读读取。源码注释举例 `storage.type` / `storage.retention.ms` / `storage.capacity` / `storage.path`，这些 key 的消费方是启动链上的 APM 模块，不在本模块解析 |
| 类过滤 include / exclude | 代码内硬编码 | 见 2.2 | 不可通过配置修改 |

写死的内部行为常量（不可配置）：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `ID_LENGTH` | 16 | traceId/spanId 长度（UUID 去横线取前 16 位） |
| `MAX_TRACE_DEPTH` | 256 | 追踪栈深度上限，超限强制清空 |
| `MAX_CROSS_THREAD_TRACES` | 10000 | 跨线程追踪缓存上限，超限淘汰最旧 10% |
| `CROSS_THREAD_TRACE_TTL_MS` | 3600000（1 小时） | 跨线程追踪条目 TTL；`BoundedLocalCache` 每 60 秒做一次过期清理 |

MDC 桥接写入的键固定为：`traceId`、`spanId`、`className`、`methodName`。

---

## 5. SPI 扩展点

| 扩展点 | 类型 | 注册方式 | 说明 |
| --- | --- | --- | --- |
| `com.chua.runtime.plugin.Plugin` | 接口（定义于 runtime-plugin） | `META-INF/services/com.chua.runtime.plugin.Plugin` | 运行时插件 SPI：`name()/version()/init(PluginContext)/start()/stop()/status()/isRunning()`；由 `PluginManager` 在 `SpyBootstrap.init` 时从插件目录加载。典型用法：在 `start()` 中调用 `RuntimeSpy.registerInterceptor(...)` 声明本插件的拦截规则 |
| `RuntimeSpy.Interceptor` | 内部接口 | 编程式：`RuntimeSpy.registerInterceptor(...)` | 拦截器接收 `InterceptContext`；抛出的 `Throwable` 会被 `RuntimeSpy` 捕获并记 SEVERE 日志，**不影响业务方法执行** |
| `InterceptPoint` | 枚举 | — | 插桩点集合；新增插桩点需扩展 `utils-support-runtime-plugin` 并在 `SpyTransformer` 的 pre/post 判定中接线 |
| `SpyTransformer#setSpyClass` | 方法注入 | 编程式 | 替换织入字节码的调用目标（默认 `com/chua/runtime/agent/Bootstrap`；测试可设为 `com/chua/runtime/spy/RuntimeSpy`） |

GraalVM native-image 元数据（`src/main/resources/META-INF/native-image/com.chua/utils-support-runtime-spy/`）已注册 `RuntimeSpy`、`RuntimeSpy$TraceContextSnapshot`、`RuntimeSpy$TraceStackFrame`、`SpyTransformer` 的全量反射。

---

## 6. 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-spy</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 依赖 | 范围 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-runtime-plugin` | compile | `InterceptPoint`、`Plugin`/`PluginContext`、`PluginManager` 等插件体系 |
| `com.chua:utils-support-common-starter` | compile | `Wraps`、`ReflectUtils`、`StringUtils`、`CollectionUtils` 等工具 |
| `org.ow2.asm:asm` | compile | 字节码读写（ASM9） |
| `org.ow2.asm:asm-commons` | compile | `AdviceAdapter`（方法入口/出口织入） |
| `org.projectlombok:lombok` | provided（父 POM 注入） | `InterceptContext` 的 `@Data`/`@Builder` |
| `org.slf4j:slf4j-api` | compile（父 POM 注入） | MDC 桥接走反射调用 `org.slf4j.MDC`，代码不直接 import，缺 SLF4J 时静默跳过 |

- 父工程：`com.chua:utils-support-runtime-parent:4.0.0.51`（其上为 `utils-support-parent-starter`）。
- JDK：`maven.compiler.release=25`；编码 `UTF-8`。
- 构建：在模块目录执行 `mvn compile -DskipTests`（或按仓库惯例 `mvn -o -q compile -DskipTests`）。
- 历史依赖树快照见模块根目录 `dtrees.txt`（记录 4.0.0.42 时期的完整传递依赖）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `InterceptPoint` 枚举、`Plugin` SPI、`PluginContext`、`PluginManager`、`PluginClassLoader` | `utils-support-runtime-plugin` |
| Agent 入口（`premain`/`agentmain`）与 `com.chua.runtime.agent.Bootstrap` 转发类 | `utils-support-runtime-agent` |
| Agent 主入口与依赖聚合（可执行 agent 包） | `utils-support-runtime-starter` |
| APM 指标聚合与存储（`storage.type` / `storage.retention.ms` / `storage.capacity` / `storage.path` 的消费方） | `utils-support-runtime-apm` |
| 运行时核心接口定义 | `utils-support-runtime-core` |
| 交互式 shell、协议支持 | `utils-support-runtime-shell`、`utils-support-runtime-protocol` |
| HTTP/JSON/反射等通用工具 | `utils-support-common-starter` |
| 「拦哪些类、拦哪些方法」的业务埋点规则 | 不内置——由插件或调用方通过 `registerInterceptor` / `registerMethod` 注册 |
| 对 `java.lang.*`、`sun/`、`jdk/` 等前缀类的插桩 | 明确跳过，不支持 |
