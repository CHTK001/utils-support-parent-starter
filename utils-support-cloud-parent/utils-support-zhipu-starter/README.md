# utils-support-zhipu-starter

> 智谱 AI 开放平台（GLM / CogView / CogVideoX）的 AI 客户端 SPI 实现模块：提供对话（`ZhipuChatClient`）、图片生成（`ZhipuImageClient`）、视频生成（`ZhipuVideoClient`）三个实现，注册到 utils 体系的 `ChatClient` / `ImageClient` / `VideoClient` SPI 之下，可与其余大模型 starter 按名称互换。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.zhipu.support`（含 `image`、`video` 两个子包）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：不在本模块与当前未支持的能力](#7-边界不在本模块与当前未支持的能力)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zhipu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块隶属 `utils-support-cloud-parent`（云服务集成父模块），随父 POM `4.0.0.51` 统一继承版本。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | 智谱 GLM 对话（官方 `oapi-java-sdk` 的 `ClientV3`）、CogView 图片生成（HTTP `POST /images/generations`，兼容 OpenAI 格式）、CogVideoX 视频生成（HTTP `POST /video/generations` + `GET /video/status/{taskId}`）的 SPI 实现 | 不含其它大模型/云厂商的实现 |
| 形态 | utils 体系下的 SPI 实现模块（三个 `@Spi` 实现类 + `META-INF/extensions` 注册文件 + GraalVM native-image 配置） | 不是 Spring Boot 自动装配 starter：模块内没有 Spring 配置绑定或 `application.yml` 配置键，全部配置通过构造参数（各 `*Setting` 对象）传入 |
| 契约 | 实现契约接口 `ChatClient` / `ImageClient` / `VideoClient`（定义于 `utils-support-common-starter`） | 不定义新的 SPI 扩展点；`src/main/java` 中只有上述三个实现类 |

---

## 2. 核心 API 与速查表

三个实现类均标注 `@Spi({"zhipu", "glm"})`、`@since 4.0.0.42`，均提供以 `ChatClientSetting` / `ImageClientSetting` / `VideoClientSetting` 为参数的构造器，并支持链式配置。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 获取实现（SPI 方式） | `ServiceProvider.of(ChatClient.class).getNewExtension("zhipu", setting)` | SPI 名称 `zhipu`；`ImageClient` / `VideoClient` 同理；也可直接 `new ZhipuChatClient(setting)` |
| 对话（同步，仅取文本） | `ZhipuChatClient#chatSync(String prompt)` | 返回 `String` |
| 对话（同步，含用量） | `ZhipuChatClient#chatSyncWithResponse(String prompt)` | 返回 `ChatSyncResponse`：`getText()` + `getUsage()`（`AiUsage`，含输入/输出/总 token 与耗时） |
| 对话（回调） | `ZhipuChatClient#chat(String prompt, Consumer<ChatResponse> consumer)` | 依次回调 `START` → `STOP`（成功）或 `ERROR`；另有带 `onComplete` / `onError` 的四参重载 |
| 深度思考 / 联网搜索 | `#thinking(boolean)` / `#smartSearch(boolean)` | 开启后请求 `ref` 中携带 `thinking` / `enable_search` |
| 技能注入 | `#skill(SkillManager)` | 通过 `SkillPrompt.inject(system, skillManager)` 合并进系统提示词 |
| 图片生成 | `ZhipuImageClient#generate(String prompt)` | 同步返回 `java.awt.image.BufferedImage`（内部下载响应中的图片 URL） |
| 视频生成（异步任务） | `ZhipuVideoClient#createTask(String)` → `#queryTask(String taskId)` | 提交任务拿 taskId，轮询 `VideoResponse`（状态 `PENDING/RUNNING/SUCCESS/FAILED`，成功取 `getVideoUrl()`） |
| 模型价格 / 指标 | `ZhipuChatClient#indicator()` | 优先使用配置的 `metricsUrl`（`HttpChatClientIndicator`）；未配置时回退免 Key 的 Models.dev 聚合端点（`ModelsDevChatClientIndicator`），按 `zhipuai` 厂商键过滤 |

对话侧常用链式方法：`model(String)`、`temperature(double)`、`maxTokens(int)`、`system(String)`、`addUserHistory(String)`、`addAssistantHistory(String)`、`history(List<ChatMessage>)`（外部完整历史，优先于内部累积历史）、`newChat()`（清空历史与图片）、`getSetting()`。

图片侧常用链式方法：`model(String)`、`size(int width, int height)`、`prompt(String)`、`quality(String)`、`style(String)`。

视频侧常用链式方法：`model(String)`、`size(int width, int height)`、`prompt(String)`、`duration(Integer)`、`style(String)`、`referenceImage(byte[] | BufferedImage)`（图生视频参考图）、`imageStrength(double)`。

**默认值**（未通过链式或 Setting 指定时）：对话模型 `glm-4`、温度 `0.3`、`topP` 固定 `0.7`、每次请求生成随机 `requestId`；图片模型 `cogview-3`、尺寸 `1024x1024`、`n=1`；视频模型 `cogvideo`。

---

## 3. 快速开始

### 3.1 对话（GLM）

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.spi.ServiceProvider;

ChatClientSetting setting = ChatClientSetting.builder()
        .appKey("你的智谱 API Key")
        .model("glm-4")
        .build();

// SPI 方式获取（也可直接 new ZhipuChatClient(setting)）
ChatClient client = ServiceProvider.of(ChatClient.class)
        .getNewExtension("zhipu", setting);

String answer = client.chatSync("用一句话介绍智谱 GLM");
System.out.println(answer);
```

带用量与回调的写法：

```java
client.system("你是一个简洁的助手")
      .maxTokens(1024)
      .chat("写一个 Java 单例", resp -> {
          if (resp.getState() == ChatResponse.State.STOP) {
              System.out.println(resp.getContent());
              System.out.println(resp.getUsage().getTotalTokens());
          }
      });
```

### 3.2 图片生成（CogView）

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import java.awt.image.BufferedImage;

ImageClient image = ServiceProvider.of(ImageClient.class)
        .getNewExtension("zhipu", ImageClientSetting.builder()
                .appKey("你的智谱 API Key")
                .build());

BufferedImage img = image.size(1024, 1024).generate("水墨画风格的山水");
```

### 3.3 视频生成（CogVideoX，异步任务）

```java
import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.video.VideoClientSetting;
import com.chua.common.support.ai.video.VideoResponse;

VideoClient video = ServiceProvider.of(VideoClient.class)
        .getNewExtension("zhipu", VideoClientSetting.builder()
                .appKey("你的智谱 API Key")
                .build());

String taskId = video.prompt("一只猫在草地上奔跑").duration(5).createTask(null);

VideoResponse resp = video.queryTask(taskId);
if (resp.getStatus() == VideoResponse.Status.SUCCESS) {
    System.out.println(resp.getVideoUrl());
}
```

> 任务提交后不会立即出结果，需按服务商节奏轮询 `queryTask`；`VideoResponse` 还提供 `getProgress()`（0-100）、`getDuration()`、`getErrorMessage()`。

---

## 4. 配置项

### 4.1 配置入口

本模块自身**不提供** Spring 配置绑定或 `application.yml` 配置键：模块 `pom.xml` 的直接依赖中没有任何 Spring 组件，`src/main/java` 下只有三个实现类。所有配置通过构造参数传入：

| 实现类 | 配置对象 |
| --- | --- |
| `ZhipuChatClient` | `com.chua.common.support.ai.chat.ChatClientSetting` |
| `ZhipuImageClient` | `com.chua.common.support.ai.image.ImageClientSetting` |
| `ZhipuVideoClient` | `com.chua.common.support.ai.video.VideoClientSetting` |

### 4.2 本模块实际读取的配置字段

**ChatClientSetting（对话）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `appKey` | `String` | 智谱 API Key，必填；用于构建官方 SDK 的 `ClientV3` |
| `model` | `String` | 默认模型；未指定时请求使用 `glm-4` |
| `temperature` | `Double` | 温度；未指定时使用 `0.3`（`topP` 固定 `0.7`，不可配） |
| `maxTokens` | `Integer` | 最大输出 token；未设置则不向请求传递该参数 |
| `system` | `String` | 系统提示词；配合 `skill(...)` 时会被 `SkillPrompt.inject` 合并 |
| `proxy` | `String` | 代理地址，支持 `http://host:port`、`https://host:port`、`socks5://host:port`（或 `socks://`）、裸 `host:port` |
| `metricsUrl` | `String` | 模型指标 / 价格端点；`indicator()` 的默认实现优先使用它，未配置时回退 Models.dev 聚合端点（按 `zhipuai` 过滤） |

> `ChatClientSetting` 中其余字段（如 `baseUrl`、`topP`、`appSecret` 等）在本实现中未被读取：对话请求直接走官方 SDK `ClientV3`。

**ImageClientSetting（图片）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `appKey` | `String` | API Key，请求头 `Authorization: Bearer <appKey>` |
| `baseUrl` | `String` | 基础地址，默认 `https://open.bigmodel.cn/api/paas/v4`（末尾多余 `/` 会被去掉） |
| `model` | `String` | 默认 `cogview-3` |
| `width` / `height` | `Integer` | 默认 `1024x1024`，拼装为 `size` 参数（如 `1024x1024`） |

> `quality` / `style` 通过链式方法 `quality(String)` / `style(String)` 设置后随请求体提交。

**VideoClientSetting（视频）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `appKey` | `String` | API Key，请求头 `Authorization: Bearer <appKey>` |
| `baseUrl` | `String` | 基础地址，默认 `https://open.bigmodel.cn/api/paas/v4` |
| `model` | `String` | 默认 `cogvideo` |
| `width` / `height` | `Integer` | 拼装为 `size` 参数 |
| `duration` | `Integer` | 视频时长（秒） |
| `style` | `String` | 风格 |

### 4.3 网络行为与副作用

| 项 | 值 |
| --- | --- |
| 图片生成超时 | 连接超时 `30000ms`，读取超时 `120000ms` |
| 视频任务提交/查询超时 | 连接超时 `60000ms` |
| 对话请求 | 经官方 `oapi-java-sdk`（`ClientV3#invokeModelApi`）发起 |
| 代理副作用 | 配置 `proxy` 后会写入 **JVM 全局系统属性**：`http.proxyHost` / `http.proxyPort` / `https.proxyHost` / `https.proxyPort`；SOCKS 代理另设 `socksProxyHost` / `socksProxyPort`。影响整个进程，请注意隔离 |
| 对话失败行为 | `chatSync` 链路上请求异常会以 `RuntimeException` 抛出；回调形态下通过 `ChatResponse.State.ERROR` + `errorMessage` 交付 |

---

## 5. SPI 扩展点

### 5.1 本模块注册的实现

接口契约全部来自 `utils-support-common-starter`，本模块通过 `src/main/resources/META-INF/extensions/` 登记实现（登记名均为 `zhipu`）：

| SPI 接口 | 注册文件内容 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `zhipu=com.chua.zhipu.support.ZhipuChatClient` | `ZhipuChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | `zhipu=com.chua.zhipu.support.image.ZhipuImageClient` | `ZhipuImageClient` |
| `com.chua.common.support.ai.video.VideoClient` | `zhipu=com.chua.zhipu.support.video.ZhipuVideoClient` | `ZhipuVideoClient` |

三个实现类上的 `@Spi({"zhipu", "glm"})` 注解额外声明了别名 `glm`；`META-INF/extensions` 登记名为 `zhipu`，按名获取时请使用 `zhipu`。

### 5.2 native-image 支持

`src/main/resources/META-INF/native-image/com.chua/utils-support-zhipu-starter/` 内置 GraalVM 配置：

- `reflect-config.json`：对三个实现类开放全部构造器 / 方法 / 字段的反射访问；
- `resource-config.json`：打包 `META-INF/extensions/.*` 资源，保证 SPI 登记文件在原生镜像中可用。

### 5.3 本模块不提供的扩展点

本模块只是 SPI 的**实现方**，自身没有定义可供二次扩展的接口或抽象类；若要接入新的服务商，请实现 `utils-support-common-starter` 中的对应 SPI 接口并在你自己的模块登记。

---

## 6. 构建与依赖

依赖（`pom.xml`）：

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（`4.0.0.51`） | SPI 契约（`ChatClient` / `ImageClient` / `VideoClient` 及其 Setting、响应模型）与 HTTP / JSON / 集合等基础工具 |
| `cn.bigmodel.openapi:oapi-java-sdk` | `1.0.6`（由根 POM `dependencyManagement` 统一管理） | 智谱官方 SDK，对话走其中的 `ClientV3` |
| `org.slf4j:slf4j-api` | 由父 POM 管理 | 日志门面 |
| `org.projectlombok:lombok` | `provided` | 编译期代码生成 |

构建命令（与仓库口径一致，离线 + 跳过测试）：

```bash
mvn -o -q compile -DskipTests
```

本模块由 `utils-support-cloud-parent` 聚合，随 `utils-support-parent-starter` 一起构建；JDK 要求 `25`（`maven.compiler.release=25`）。

---

## 7. 边界：不在本模块与当前未支持的能力

以下能力在源码中明确不存在、抛出异常或未生效，调用前请规避：

| 能力 | 现状 |
| --- | --- |
| 对话文件附件 | `addAttachment(...)` / `addAttachmentUrl(...)` 抛 `UnsupportedOperationException`（“该服务商不支持文件附件” / “该服务商不支持远程文件附件”） |
| 对话图片输入 | `addImage(String)` 方法存在，但当前实现未将图片送入实际请求（仅记录并在 `newChat()` 时清理） |
| 对话 `thinkingEffort` / `session` | 链式方法存在，但当前请求构造未使用这些值 |
| 图片参考图 / 图生图 | `referenceImage(byte[] \| BufferedImage)`、`imageStrength(double)`、`controlType(String)` 均抛 `UnsupportedOperationException` |
| 图片异步任务 | `createTask` / `queryTask` 抛 `UnsupportedOperationException`（CogView 仅支持 `generate()` 同步生成） |
| 视频 `seed` | `seed(Long)` 为空实现（直接返回自身），不参与请求 |
| 其它 AI 能力 | Embedding、RAG、语音、翻译、决策等契约与实现不在本模块（契约定义于 `utils-support-common-starter`，实现见对应 starter） |
| 其它云厂商 | 阿里云、腾讯云、OpenAI、豆包等由 `utils-support-cloud-parent` 下的兄弟模块提供 |
