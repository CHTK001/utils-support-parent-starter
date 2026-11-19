# utils-support-datalake-webhook-starter

> 数据湖体系的 **Webhook 被动推送入站适配器**：暴露 HTTP 端点接收外部系统推送的 JSON 数据，自动解析为 `DataEnvelope` 并交给 `PipelineEngine` 处理。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 pom `java.version=25`）｜包名：`com.chua.datalake.support.webhook`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. HTTP 端点与应答口径](#3-http-端点与应答口径)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-webhook-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | Webhook 被动推送的**入站适配器**：HTTP POST → JSON → `DataEnvelope` → `PipelineEngine` | 不是管线引擎，也不是 Sink 实现 |
| 服务 | 基于 JDK 自带 `com.sun.net.httpserver.HttpServer` 的轻量 HTTP 端点 | 不是 Spring MVC / Spring Boot 自动装配模块 |
| 安全 | 支持请求头 `x-webhook-token` 令牌校验（`MessageDigest.isEqual` 恒时比较）、请求体大小上限、pipelineId 白名单正则 | 不是完整的网关 / 鉴权框架 |
| 应答 | HTTP 状态码反映管线真实结果（`SINK_OK` 才是 200），应答体携带受理 / 失败计数 | 不提供恒真 `ok=true` 的假成功 |
| 依赖 | 只依赖 `utils-support-common-starter`、`utils-support-datalake-sink-starter`、jackson-databind、lombok | 不依赖 Spring、不依赖数据库驱动、不依赖具体消息队列 |

**判断口径**：外部系统想用「HTTP POST 推 JSON」的方式把数据送进数据湖管线，用本模块；需要按 topic 路由、或由服务端主动订阅 / 拉取的，请使用管线体系内的其他采集与 Sink 模块。

### 1.3 规模

- Java 源文件 **2** 个：`WebhookInboundAdapter`（含内部类 `Builder`、`WebhookRejectException`）、`WebhookReply`。
- 无 `src/main/resources`，无 Spring 自动配置类，无配置文件。

---

## 2. 核心 API 与速查表

核心类只有两个：

| 类 | 说明 |
| --- | --- |
| `WebhookInboundAdapter` | 入站适配器主体：启动 / 停止 HTTP 服务、路由分发、令牌与请求体校验、逐条提交管线并按真实结果回写应答 |
| `WebhookReply` | 应答体：`ok`、`status`、`error`、`state`、`received`、`failed`、`receivedTotal`、`failedTotal`，`@JsonInclude(NON_NULL)` 序列化 |

常用入口速查：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建适配器 | `WebhookInboundAdapter.builder()` | 返回 `Builder`，链式配置后 `build()` |
| 注入管线引擎 | `Builder.pipelineEngine(PipelineEngine)` | **必填**，为空时 `build()` 抛 `IllegalArgumentException` |
| 绑定地址 / 端口 | `Builder.host(String)` / `Builder.port(int)` | 默认 `127.0.0.1:8700`；`port=0` 时由系统分配临时端口 |
| 令牌校验 | `Builder.token(String)` | 非空时推送必须携带 `x-webhook-token` 请求头 |
| 请求体上限 | `Builder.maxBodyBytes(int)` | 默认 `1048576` 字节（1MB），超限返回 413；必须为正数 |
| 启动 / 停止 | `start()` / `stop()` | `start()` 失败直接抛 `IllegalStateException`；`stop()` 释放端口与工作线程 |
| 运行观测 | `getServerPort()` / `getMessageCount()` / `getFailedCount()` / `isRunning()` | 实际端口（含 `port=0` 场景）、累计接收数、累计未消化数、运行状态 |
| 对应答体建模 | `WebhookReply.builder()` | 一般无需自建，适配器自动组装并序列化为 JSON |

---

## 3. HTTP 端点与应答口径

### 3.1 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/datalake/webhook/{pipelineId}` | 推送**单条** JSON 对象 |
| POST | `/api/datalake/webhook/{pipelineId}/batch` | 推送**批量** JSON 数组 |
| GET | `/api/datalake/webhook/health` | 健康检查，只读、**不校验令牌**，返回累计计数 |

- `pipelineId` 合法字符：首字符为字母或数字，总长 1–64（支持字母、数字、`_`、`.`、`-`）。
- 批量请求体必须是 JSON 数组，且每个元素必须是 JSON 对象；先整体校验形态再投递，避免「前几条已投递、后面才报格式错误」的半提交。

### 3.2 状态码与结果分类

HTTP 状态码反映管线真实结果，**不能靠恒真的 `ok=true` 判断成功**：

| HTTP | `status` 分类 | 触发条件 |
| --- | --- | --- |
| 200 | `accepted` | 全部数据被管线消化（终态 `SINK_OK`） |
| 400 | `invalid_pipeline_id` / `empty_body` / `invalid_json` / `invalid_payload` | 入参不合法 |
| 401 | `unauthorized` | 配置了 token 但请求头缺失或不匹配 |
| 404 | `pipeline_not_found` | 管线未注册或无 default 阶段 |
| 405 | `method_not_allowed` | 推送用了非 POST、健康检查用了非 GET |
| 413 | `payload_too_large` | 请求体超过 `maxBodyBytes` |
| 500 | `sink_failed` / `internal_error` | 管线执行未消化（非 `SINK_OK`）或服务端异常 |

推送方应据此决定重试策略。

### 3.3 应答体字段（`WebhookReply`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ok` | boolean | 本次请求是否全部受理成功 |
| `status` | String | 结果分类，见上表；`WebhookReply` 注释还列出 `partial_failed`、`rejected`、`degraded` 等预留分类 |
| `error` | String | 拒绝或失败原因，成功时为 `null`（不序列化） |
| `state` | String | 最后一条数据的管线状态（`PipelineState` 名称），未进入管线时为 `null` |
| `received` / `failed` | int | 本次接收 / 未消化条数 |
| `receivedTotal` / `failedTotal` | Long | 累计接收 / 未消化条数 |

---

## 4. 快速开始

最短可运行示例（管线引擎以最简 lambda 演示，真实项目请使用 `utils-support-datalake-starter` 提供的引擎或自行实现 `PipelineEngine` SPI）：

```java
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.webhook.WebhookInboundAdapter;

public class WebhookDemo {

    public static void main(String[] args) {
        // 1. 准备管线引擎（此处仅演示接口签名）
        PipelineEngine engine = (pipelineId, envelope) ->
                System.out.println("pipeline=" + pipelineId + ", envelope=" + envelope);

        // 2. 构建并启动 Webhook 入站适配器
        WebhookInboundAdapter adapter = WebhookInboundAdapter.builder()
                .host("127.0.0.1")           // 默认只绑定本机回环
                .port(8700)                  // 默认 8700；0 = 系统分配
                .token("my-secret")          // 推送必须携带 x-webhook-token: my-secret
                .maxBodyBytes(1024 * 1024)   // 请求体上限，默认 1MB
                .pipelineEngine(engine)      // 必填
                .build();

        adapter.start();
        System.out.println("监听端口: " + adapter.getServerPort());

        // 3. 停止（释放监听端口与工作线程）
        adapter.stop();
    }
}
```

推送方调用示例（curl）：

```bash
# 单条推送
curl -X POST http://127.0.0.1:8700/api/datalake/webhook/my-pipeline \
     -H "Content-Type: application/json" \
     -H "x-webhook-token: my-secret" \
     -d '{"device":"gw-01","value":42}'

# 批量推送
curl -X POST http://127.0.0.1:8700/api/datalake/webhook/my-pipeline/batch \
     -H "Content-Type: application/json" \
     -H "x-webhook-token: my-secret" \
     -d '[{"device":"gw-01","value":42},{"device":"gw-02","value":7}]'

# 健康检查（免令牌）
curl http://127.0.0.1:8700/api/datalake/webhook/health
```

> 安全提示：默认只绑定 `127.0.0.1`。改用 `0.0.0.0` 对外提供服务时，**必须**同时配置 `token(...)`，否则任何能连到端口的进程都能向管线注入数据。

---

## 5. 配置项

**无**外部配置文件与配置键（如 `application.yml` 属性、系统属性）。依据：本模块 `src/main` 下只有 Java 源码、无 `resources` 目录，pom 中也没有 Spring Boot 自动装配依赖。

全部配置均通过 `WebhookInboundAdapter.Builder` **编程式**给出：

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host(String)` | `127.0.0.1` | 绑定地址；对外暴露时须同时设置 token |
| `port(int)` | `8700` | 监听端口；`0` 表示系统分配临时端口 |
| `token(String)` | `null`（不校验） | 非空时推送必须携带 `x-webhook-token` 请求头 |
| `maxBodyBytes(int)` | `1048576`（1MB） | 请求体字节上限，必须为正数，否则 `build()` 抛 `IllegalArgumentException` |
| `pipelineEngine(PipelineEngine)` | `null` | **必填**，为空时 `build()` 抛 `IllegalArgumentException` |

---

## 6. SPI 扩展点

本模块**未定义**新的 SPI 接口，也未在 `META-INF/extensions` 登记任何扩展（无 `resources` 目录）。

它消费的是 `utils-support-datalake-sink-starter` 中的 **`PipelineEngine` SPI**（`com.chua.datalake.support.spi.pipeline.PipelineEngine`，单方法 `execute(String pipelineId, DataEnvelope envelope)`）。扩展方式：实现该接口并通过 `Builder.pipelineEngine(...)` 注入；适配器会把每条推送包装为 `DataEnvelope`（含 `parsed` 数据、`pipelineId`、`traceId=webhook-<自增序号>`、`timestamp`）后调用 `execute`，并以 `envelope.getState()` 判定是否 `SINK_OK`。

---

## 7. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

- 父模块：`com.chua:utils-support-datalake-parent:4.0.0.51`。
- 编译目标：JDK 25（继承根 pom `java.version=25`）。

| 依赖 | 作用 |
| --- | --- |
| `utils-support-common-starter` | 通用工具（`Wraps`、`StringUtils` 等） |
| `utils-support-datalake-sink-starter` | `DataEnvelope`、`PipelineState`、`PipelineEngine` 等管线契约 |
| `com.fasterxml.jackson.core:jackson-databind` | JSON 解析与应答序列化 |
| `org.projectlombok:lombok`（provided） | `@Slf4j`、`@Data`、`@Builder` 等 |

运行时线程模型：JDK `HttpServer` + 固定 4 个工作线程（`WORKER_THREADS=4`）的有界队列线程池，拒绝策略为 `CallerRunsPolicy`；`stop()` 时先 `shutdown()`、最多等 5 秒，再 `shutdownNow()`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| `DataEnvelope` / `PipelineState` / `PipelineEngine` / `PipelineManager` / `PipelineConfig` 的定义 | `utils-support-datalake-sink-starter` |
| 管线 DSL、Filter / Parser / Cleaner / Standardizer 阶段与 Log / Jdbc / Stats / Redis 等 Sink 实现 | `utils-support-datalake-starter` 及其 Sink 模块 |
| 主动采集（客户端推 `Collector`、订阅 MQ 的 `ActiveCollector`、定时拉取的 `ScheduledCollector`） | datalake 体系对应模块 |
| topic 路由 | 不在本模块：请求体即业务数据，没有 topic 通道，声明了 topics 的订阅器不会命中 Webhook 推送的数据；需要按 topic 路由时应改用管线内的其他 Sink |
| Spring Boot 自动装配 / 配置绑定 | 本模块不是 Spring starter，无自动配置类与配置键 |
| 完整网关能力（限流、熔断、认证体系等） | 不在本模块；本模块只提供令牌头校验、请求体上限与 pipelineId 白名单 |
