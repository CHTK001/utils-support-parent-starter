# utils-support-datalake-mqtt-starter

> 数据湖（datalake）体系的 **MQTT 被动推送入站适配器**：订阅 MQTT Broker，把消息包装成 `DataEnvelope` 交给 `PipelineEngine` 执行管线处理。
>
> 版本：`4.0.0.51`（继承父 pom `utils-support-datalake-parent`）｜JDK：`25`｜包名：`com.chua.datalake.support.mqtt`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 工作机制](#4-工作机制)
- [5. Builder 配置项](#5-builder-配置项)
- [6. 运行时行为](#6-运行时行为)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-mqtt-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | MQTT → 数据湖管线的**入站适配器**（被动接收推送） | 不是 MQTT 客户端工具库，也不是嵌入式 Broker |
| 协议 | 基于 Eclipse Paho **MQTT v5** 客户端（`org.eclipse.paho.mqttv5.client`，1.2.5），支持 MQTT 5.0 | 不支持 MQTT 3.1.1 专属特性的封装 |
| 数据流 | 订阅主题 → JSON 解析 → 注入 MQTT 元数据 → `DataEnvelope` → `PipelineEngine.execute(pipelineId, envelope)` | 不实现 Filter / Parser / Cleaner / Sink 等管线环节 |
| 配置方式 | 纯 Builder 链式配置，`start()` / `stop()` 手动管理生命周期 | 无 Spring Boot 自动装配、无 `application.yml` 配置键（源码未提供） |
| 规模 | 单一主类 `MqttInboundAdapter`（`@since 4.0.0.42`）+ 内部 `Builder` | 不含其它公共 API |

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建适配器 | `MqttInboundAdapter.builder()` | 返回 `Builder`，链式配置后 `build()` |
| 指定 Broker 地址 | `Builder.brokerUrl(String)` | 默认 `tcp://localhost:1883` |
| 指定订阅主题 | `Builder.topic(String)` | 支持 `+` / `#` 通配符，默认 `#`；`start()` 时校验非空 |
| 绑定管线 | `Builder.pipelineId(String)` + `Builder.pipelineEngine(PipelineEngine)` | 两者必填，缺一 `build()` 抛 `IllegalArgumentException` |
| 设置 QoS / 鉴权 | `Builder.qos(int)`（默认 1）、`Builder.username(String)` / `Builder.password(String)` | 用户名密码可选 |
| 启动订阅 | `adapter.start()` | 连接 Broker 并订阅，失败抛 `IllegalStateException` |
| 停止并释放资源 | `adapter.stop()` | 断开连接、关闭客户端，打印累计消息数 |
| 查看运行状态 / 消息数 | `adapter.isRunning()` / `adapter.getMessageCount()` | 消息计数为累计接收条数 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-mqtt-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- PipelineEngine / DataEnvelope 来自 datalake-sink 模块，运行期由宿主提供（pom 中为 provided） -->
```

### 3.2 最短可运行示例

```java
import com.chua.datalake.support.mqtt.MqttInboundAdapter;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;

// engine：你已构建好的 PipelineEngine 实例（来自 utils-support-datalake-sink-starter）
PipelineEngine engine = ...;

MqttInboundAdapter adapter = MqttInboundAdapter.builder()
        .brokerUrl("tcp://localhost:1883")
        .clientId("datalake-sub-001")
        .topic("sensor/+/data")
        .pipelineId("sensor-pipeline")
        .pipelineEngine(engine)
        .qos(1)
        .build();

adapter.start();     // 连接 Broker 并订阅 sensor/+/data
// ... 运行期间消息自动进入 sensor-pipeline 管线 ...
adapter.stop();      // 停止订阅并释放 Paho 客户端资源
```

---

## 4. 工作机制

```
MQTT Broker ──推送──▶ MqttInboundAdapter（Paho MQTT v5 回调 messageArrived）
                          │ 1. UTF-8 解码消息体
                          │ 2. JSON 对象 → Map；数组/标量/非 JSON → {"payload": 原文}
                          │ 3. 注入元数据：_mqtt_topic / _mqtt_qos / _mqtt_retained
                          │ 4. 构建 DataEnvelope（pipelineId、traceId="mqtt-<计数>"、timestamp）
                          ▼
                 PipelineEngine.execute(pipelineId, envelope)
```

要点（均与源码一致）：

- **消息体自适应**：以 `{` 开头的文本按 JSON 对象解析为 `Map<String, Object>`；数组、标量或非 JSON 文本统一包装为 `{"payload": 原文}`，避免纯文本主题的整批消息被静默丢弃。
- **元数据注入**：每条消息的 Map 中额外写入 `_mqtt_topic`（来源主题）、`_mqtt_qos`（QoS 级别）、`_mqtt_retained`（是否保留消息）。
- **链路追踪**：`DataEnvelope` 携带 `traceId = "mqtt-" + 递增计数`、`timestamp = 当前毫秒`，并追加一条 `"[MQTT] topic=<主题> qos=<级别>"` 轨迹。
- **单条失败不中断**：某条消息解析或执行异常时仅记录错误日志，不影响后续消息消费。

---

## 5. Builder 配置项

本模块**没有** Spring 配置文件键（源码未提供 `application.properties` / `application.yml` 支持），全部配置通过 `MqttInboundAdapter.Builder` 完成：

| Builder 方法 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `brokerUrl(String)` | 否 | `tcp://localhost:1883` | Broker 连接地址 |
| `clientId(String)` | 否 | `datalake-mqtt-<当前毫秒时间戳>` | 客户端标识 |
| `topic(String)` | 否（有默认值，但 `start()` 强制非空） | `#` | 订阅主题，支持 `+` / `#` 通配符 |
| `pipelineId(String)` | **是** | 无 | 目标管线 ID |
| `pipelineEngine(PipelineEngine)` | **是** | 无 | 管线引擎实例 |
| `qos(int)` | 否 | `1` | 订阅 QoS 级别（0/1/2） |
| `username(String)` | 否 | 无（不设置） | Broker 用户名 |
| `password(String)` | 否 | 无（不设置） | Broker 密码（按 UTF-8 字节传输） |

约束与异常：

- `build()` 时 `pipelineId` 或 `pipelineEngine` 为空 → 抛 `IllegalArgumentException("pipelineId 和 pipelineEngine 不能为空")`。
- `start()` 时 `topic` 为空 → 抛 `IllegalStateException("datalake-mqtt 订阅主题不能为空")`。
- `start()` 连接/订阅失败 → 释放客户端后抛 `IllegalStateException`（携带 broker、topic 与底层原因）。

---

## 6. 运行时行为

| 行为 | 说明 |
| --- | --- |
| 连接参数 | `cleanStart(true)`、`keepAliveInterval(30)` 秒、`automaticReconnect(true)`；会话持久化使用 `MemoryPersistence`（内存态，不落盘） |
| 自动重连 | 断线后由 Paho 自动重连，重连成功打印日志（`connectComplete` 回调） |
| 消息计数 | `getMessageCount()` 返回自创建以来接收并处理的消息总数（`AtomicLong`） |
| 运行状态 | `isRunning()` 在 `start()` 成功后为 `true`，`stop()` 后为 `false`；重复 `start()` 直接返回 |
| 资源释放 | `stop()` 与启动失败路径均会断开连接并 `close()` 客户端，关闭阶段异常仅记录警告日志 |

---

## 7. SPI 扩展点

**无。** 本模块源码目录只有 `MqttInboundAdapter` 一个类，且没有 `src/main/resources`（无 `META-INF/extensions` 声明），因此不定义、不注册任何 SPI 扩展点。

它本身是数据湖 SPI 体系的**消费方**：使用的 `com.chua.datalake.support.spi.pipeline.PipelineEngine` 与 `com.chua.datalake.support.model.DataEnvelope` 均来自 `utils-support-datalake-sink-starter`（编译期依赖、运行期 provided）。扩展管线能力请前往 datalake 体系 corresponding 模块，而非本模块。

---

## 8. 构建与依赖

```
utils-support-datalake-mqtt-starter
├── utils-support-common-starter            （Wraps、StringUtils 等基础工具）
├── utils-support-datalake-sink-starter     （provided：DataEnvelope、PipelineEngine）
├── org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5
├── com.fasterxml.jackson.core:jackson-databind
└── org.projectlombok:lombok                （provided）
```

- 归属：`utils-support-parent-starter/utils-support-datalake-parent`，同目录还有 datalake-starter、datalake-sink-starter、datalake-subscribe-starter、datalake-query-starter、datalake-webhook-starter、datalake-cdc-starter。
- 构建：`mvn -o -q compile -DskipTests`，JDK 25（`maven.compiler.release=25`）。

---

## 9. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| MQTT 主动客户端、嵌入式 Broker、`MqttSubscriber` SPI | `utils-support-middleware-parent/utils-support-mqtt-starter` |
| 管线引擎实现、Filter/Parser/Cleaner/Sink、死信队列 | `utils-support-datalake-starter` / `utils-support-datalake-sink-starter` |
| `PipelineEngine`、`DataEnvelope` 等 SPI 与模型定义 | `utils-support-datalake-sink-starter` |
| 定时拉取、HTTP/TCP 采集等其它入站方式 | datalake 体系对应 collector 模块 |
| Spring Boot 自动装配 / 配置文件绑定 | 源码未提供 |
