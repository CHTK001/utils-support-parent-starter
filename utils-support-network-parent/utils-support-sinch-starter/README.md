# utils-support-sinch-starter

> Sinch 语音电话集成：把 Sinch 作为 `VoiceCall` SPI 的一个提供商接入 utils 体系。当前为 **SPI 装配骨架**——实现类已注册、可被 SPI 按 `sinch` 名字加载，但语音呼叫、状态查询、挂断三个能力尚未实现，调用即抛 `UnsupportedOperationException`。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `<java.version>25</java.version>`，`maven.compiler.release=25`）｜包名：`com.chua.sinch.support.voice`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sinch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 名称：`Utils Support Sinch Starter`；POM 描述：**Sinch 语音电话集成**；父模块：`utils-support-network-parent`（网络协议集成父模块）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `VoiceCall` SPI 的 `sinch` 提供商装配骨架 | 可直接拨打的语音能力 |
| 实现 | `SinchVoiceCall`：`getProvider()` 返回 `"sinch"`；`call` / `getCallStatus` / `hangup` 为占位实现，抛 `UnsupportedOperationException` | 未实现 Sinch SDK 的真实调用（源码未提供） |
| 形态 | 纯 SPI provider 模块：通过 `META-INF/extensions` 注册，另附 GraalVM native-image 反射/资源配置 | 不是 Spring Boot Starter：无自动装配、无 Controller、无配置属性 |
| 依赖 | 只依赖同层 `utils-*` 模块与 `com.sinch.sdk:sinch-sdk-java` | 不依赖任何 `spring-*` 上层业务模块 |

### 1.3 源码构成

| 文件 | 说明 |
| --- | --- |
| `src/main/java/com/chua/sinch/support/voice/SinchVoiceCall.java` | 唯一 Java 源文件（50 行），`@Spi("sinch")`，`@since 4.0.0.42` |
| `src/main/resources/META-INF/extensions/com.chua.common.support.network.voice.VoiceCall` | SPI 注册文件：`sinch=com.chua.sinch.support.voice.SinchVoiceCall` |
| `src/main/resources/META-INF/native-image/com.chua/utils-support-sinch-starter/reflect-config.json` | GraalVM 反射配置：注册 `SinchVoiceCall` 的全部构造器/方法/字段 |
| `src/main/resources/META-INF/native-image/com.chua/utils-support-sinch-starter/resource-config.json` | GraalVM 资源配置：包含 `META-INF/extensions/.*` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 获取 Sinch 语音实现 | `ServiceProvider.of(VoiceCall.class).getExtension("sinch")` | 返回 `SinchVoiceCall` 实例 |
| 直接实例化 | `new SinchVoiceCall()` | 公有无参构造，不走 SPI 也可用 |
| 查提供商标识 | `sinch.getProvider()` | 返回 `"sinch"` |
| 发起语音呼叫 | `sinch.call(CallRequest)` | 当前抛 `UnsupportedOperationException`（占位） |
| 查询呼叫状态 | `sinch.getCallStatus(callId)` | 当前抛 `UnsupportedOperationException`（占位） |
| 挂断呼叫 | `sinch.hangup(callId)` | 当前抛 `UnsupportedOperationException`（占位） |
| 构造呼叫请求 | `CallRequest.builder()...build()` | 字段：`to` / `from` / `message` / `locale` / `custom` / `timeout` |
| 构造呼叫响应 | `CallResponse.success(callId)` / `CallResponse.failure(message)` | 契约自带的快速工厂方法 |

### 2.2 VoiceCall 契约（定义在 utils-support-common-starter）

`com.chua.common.support.network.voice.VoiceCall`，通过 SPI 机制支持多提供商实现：

| 方法 | 说明 |
| --- | --- |
| `String getProvider()` | 提供商标识（如 `"sinch"`、`"twilio"`） |
| `CallResponse call(CallRequest request) throws Exception` | 发起语音呼叫 |
| `CallResponse getCallStatus(String callId) throws Exception` | 查询呼叫状态 |
| `CallResponse hangup(String callId) throws Exception` | 挂断呼叫 |

### 2.3 CallRequest 请求参数

`com.chua.common.support.network.voice.CallRequest`（Lombok `@Data` + `@Builder`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `to` | `String` | 被叫号码（E.164 格式，如 `+8613800138000`） |
| `from` | `String` | 主叫号码（可选） |
| `message` | `String` | 语音播报文本内容（TTS 场景） |
| `locale` | `String` | 语言区域（如 `zh-CN`、`en-US`） |
| `custom` | `String` | 自定义参数（提供商特定配置） |
| `timeout` | `Long` | 呼叫超时时间（毫秒，可选） |

### 2.4 CallResponse 响应结果

`com.chua.common.support.network.voice.CallResponse`（Lombok `@Data` + `@Builder`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `callId` | `String` | 呼叫 ID |
| `status` | `String` | 呼叫状态（如 `"queued"`、`"ringing"`、`"in-progress"`、`"completed"`、`"failed"`） |
| `success` | `boolean` | 是否成功 |
| `message` | `String` | 附加消息（错误描述等） |
| `errorMessage` | `String` | 错误消息（与 `message` 同义，部分调用方使用此命名） |
| `duration` | `long` | 调用耗时（毫秒） |
| `data` | `Map<String, Object>` | 附加数据（提供商返回的原始负载） |

静态工厂：`CallResponse.success(String callId)`（`status="queued"`、`success=true`）；`CallResponse.failure(String message)`（`status="failed"`、`success=false`）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sinch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短示例

```java
import com.chua.common.support.network.voice.CallRequest;
import com.chua.common.support.network.voice.CallResponse;
import com.chua.common.support.network.voice.VoiceCall;
import com.chua.common.support.spi.ServiceProvider;

// 1. 按 SPI 名称取 Sinch 实现
VoiceCall sinch = ServiceProvider.of(VoiceCall.class).getExtension("sinch");
System.out.println(sinch.getProvider());   // sinch

// 2. 构造呼叫请求
CallRequest request = CallRequest.builder()
        .to("+8613800138000")          // 被叫号码，E.164
        .from("+8613800138001")        // 主叫号码（可选）
        .message("您的验证码是 1234")    // TTS 文本
        .locale("zh-CN")               // 语言区域
        .timeout(30_000L)              // 超时毫秒（可选）
        .build();

// 3. 发起呼叫
CallResponse response = sinch.call(request);
```

> **注意**：第 3 步当前会抛出 `java.lang.UnsupportedOperationException`，异常消息为
> 「Sinch 语音呼叫尚未实现，请引入 sinch-sdk 依赖后实现」；`getCallStatus` / `hangup` 同理。
> 本模块目前提供的是 **SPI 装配骨架**，不是可拨打的语音能力。

---

## 4. 配置项

**无。**

依据：本模块源码与资源中不存在任何配置键、配置类或配置文件——没有 `application.yml` / `application.properties`，没有 `@ConfigurationProperties`，没有系统属性读取；仅有的资源是 SPI 注册文件与两份 GraalVM native-image 配置。POM 亦未定义模块级配置属性。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**，它是 common-starter 中 `VoiceCall` SPI 的**提供方**：

- 声明：实现类标注 `@Spi("sinch")`（`com.chua.common.support.spi.annotations.Spi`）。
- 登记：`src/main/resources/META-INF/extensions/com.chua.common.support.network.voice.VoiceCall` 写入 `sinch=com.chua.sinch.support.voice.SinchVoiceCall`。
- 加载：`ServiceProvider.of(VoiceCall.class).getExtension("sinch")`。

若要新增语音提供商（同仓已有一例：`utils-support-alibaba-starter` 的 `com.chua.alibaba.support.voice.AlibabaVoiceCall`，`@Spi("alibaba-voice")`）：

1. 实现 `VoiceCall` 的四个方法：`getProvider` / `call` / `getCallStatus` / `hangup`；
2. 实现类标注 `@Spi("你的提供商名")`；
3. 在自己模块的 `META-INF/extensions/com.chua.common.support.network.voice.VoiceCall` 中登记 `你的提供商名=全限定类名`。

---

## 6. 构建与依赖

### 6.1 构建验证

```bash
mvn -o -q compile -DskipTests
```

（仓库交付口径：`EXIT=0` 即编译通过。）

### 6.2 依赖清单（来自 pom.xml）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | `VoiceCall` / `CallRequest` / `CallResponse` 契约与 SPI 设施 |
| `com.chua:utils-support-network-starter` | `${project.version}` | 网络模块基础 |
| `com.sinch.sdk:sinch-sdk-java` | `${sinch.version}` = `2.2.0`（由 `utils-support-network-parent` 的 `dependencyManagement` 统一管理） | Sinch 官方 SDK；**当前源码尚未 import 使用** |

依赖方向：本模块只依赖同层 `utils-*` 模块，不依赖任何 `spring-*` 上层业务模块（符合仓库铁律：最底层工具库不反向依赖上层）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 状态 |
| --- | --- |
| 真实语音呼叫 / 呼叫状态查询 / 挂断 | **不可用**：`call` / `getCallStatus` / `hangup` 三个方法均直接抛 `UnsupportedOperationException` |
| Sinch 客户端构建器与 calling API 的具体调用代码 | 源码未提供；仅源码注释指引「引入 com.sinch:sinch-sdk 后可通过 sinch客户端构建器 创建客户端调用 callingapi」 |
| 其它语音提供商实现 | 不在本模块：`twilio` 仅是 `VoiceCall` 契约 Javadoc 中的示例名，仓库当前无 Twilio 实现；阿里云实现位于 `utils-support-alibaba-starter` |
| HTTP 接口 / Controller / Spring 自动装配 | 无：源码中没有任何 Controller、`spring.factories` 或 `AutoConfiguration.imports` |
| 配置项 | 无（见第 4 节） |
