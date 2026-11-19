# utils-support-huawei-starter

> 华为云集成模块：**华为云 OBS 对象存储** + **盘古大模型对话**。以 SPI 实现挂接到底层 `utils-support-common-starter` 的 `FileStorage` 与 `ChatClient` 契约。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库 `java.version=25`，`maven.compiler.source/target=25`）｜包名：`com.chua.huawei.support`（含 `storage` 子包）

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
    <artifactId>utils-support-huawei-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 对话 | 盘古大模型 `ChatClient` SPI 实现（`HuaweiChatClient`），走 HTTP 调用盘古 API，IAM 密码认证 | 不是 SDK 封装层之外的新协议，也不是流式 SSE 客户端 |
| 存储 | 华为云 OBS `FileStorage` SPI 实现（`HuaweiObsFileStorage`），基于 `com.huaweicloud:esdk-obs-java` SDK | 不含其它华为云服务（ECS、RDS、IAM 管理 API 等） |
| 定位 | 底层工具库 `utils-support-parent-starter` 下的云服务 starter，只提供厂商适配 | 不含业务表、持久层、Spring 自动装配、配置文件前缀 |
| 规模 | 2 个实现类 + 2 份 SPI 登记文件 + GraalVM native-image 配置 | — |

模块内源码共 2 个 Java 文件：

| 类 | SPI 名称 | 实现契约 | 引入版本 |
| --- | --- | --- | --- |
| `com.chua.huawei.support.HuaweiChatClient` | `huawei`、`pangu` | `com.chua.common.support.ai.chat.ChatClient` | 4.0.0.42 |
| `com.chua.huawei.support.storage.HuaweiObsFileStorage` | `obs`、`huawei` | `com.chua.common.support.storage.FileStorage`（经 `AbstractFileStorage`） | 4.0.0.42 |

---

## 2. 核心 API 与速查表

别人最可能来查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建盘古对话客户端 | `ChatClient.create(setting)` | `setting.provider = "huawei"` 或 `"pangu"`；也可直接 `new HuaweiChatClient(setting)` |
| 一次性对话（回调式） | `client.chat(prompt, consumer)` | 回调依次收到 `START` → 成功 `STOP` / 失败 `ERROR`；`STOP` 事件携带完整响应体与 `AiUsage` 用量 |
| 一次性对话（同步取结果） | `client.chatSync(prompt)` | 内部调用 `chat`，拼接回调中 `STREAMING` 状态的内容返回 |
| 深度思考 / 联网搜索 | `client.thinking(true)` / `client.smartSearch(true)` | 分别向请求体追加 `"thinking":true` / `"enable_search":true` |
| 多轮会话 | `addUserHistory` / `addAssistantHistory` / `history(List<ChatMessage>)` / `newChat()` | 追加历史、外部整段历史、清空重开 |
| 技能注入 | `client.skill(skillManager)` | 非空时经 `SkillPrompt.inject` 注入 system 提示词 |
| 创建 OBS 存储 | `FileStorage.createStorage("obs", bucketSetting)` | SPI 名 `obs` 或 `huawei`；也可直接 `new HuaweiObsFileStorage(bucketSetting)` |
| 对象增删查 | `putObject` / `getObject` / `existObject` / `deleteObject` / `listObject` | 统一请求/结果模型，失败走 `ResultCode.FAILURE` 不抛异常 |

`HuaweiChatClient` 链式方法一览（均返回 `ChatClient`）：

| 方法 | 生效情况 |
| --- | --- |
| `model(String)` | 生效；未设置时默认 `pangu-ultra` |
| `temperature(double)` | 生效；未设置时默认 `0.3` |
| `maxTokens(int)` | 生效；未设置时默认 `2048` |
| `system(String)` | 生效；作为 `system` 角色消息 |
| `thinking(boolean)` / `smartSearch(boolean)` | 生效；见速查表 |
| `skill(SkillManager)` | 生效；注入 system 提示词 |
| `addUserHistory(String)` / `addAssistantHistory(String)` / `history(...)` / `newChat()` | 生效 |
| `addImage(String imageUrl)` | 可链式调用，但当前实现的请求体未使用图片附件 |
| `thinkingEffort(String)` | 可链式调用，但当前实现的请求体未使用该参数 |
| `session(String sessionId)` | 可链式调用，但当前实现的请求体未使用会话标识 |
| `addAttachment(...)` / `addAttachmentUrl(...)` | 不支持：抛 `UnsupportedOperationException`（「该服务商不支持文件附件 / 远程文件附件」） |
| `getSetting()` | 返回构造时传入的 `ChatClientSetting` |

---

## 3. 快速开始

### 3.1 盘古大模型对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClientSetting setting = ChatClientSetting.builder()
        .provider("huawei")              // 或 "pangu"
        .appKey("IAM用户名")              // 华为云 IAM 用户名（同时作为 domain 名）
        .appSecret("IAM密码")             // 华为云 IAM 密码
        // .baseUrl("https://pangu.cn-north-4.myhuaweicloud.com/v1")  // 缺省即此地址
        .build();

try (ChatClient client = ChatClient.create(setting)) {
    String answer = client
            .model("pangu-ultra")
            .temperature(0.3)
            .maxTokens(2048)
            .chatSync("用一句话介绍盘古大模型");
    System.out.println(answer);
}
```

> ⚠️ 注意：当前 `HuaweiChatClient` 的 `chat` 只回调 `START`/`STOP`/`ERROR`，不产生 `STREAMING` 中间事件，而 `chatSync` 只拼接 `STREAMING` 状态的内容，因此上面 `chatSync` 实际返回空串。要拿到模型回复，请使用下方回调式写法，从 `STOP` 事件的 `getContent()` / `getFullContent()` 读取完整响应体。

回调式（可拿到用量统计）：

```java
client.chat("你好", response -> {
    switch (response.getState()) {
        case STOP -> System.out.println("完整响应: " + response.getContent());
        case ERROR -> System.err.println("失败: " + response.getErrorMessage());
        default -> { }
    }
});
```

### 3.2 华为云 OBS 对象存储

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.setting.BucketSetting;
import java.io.File;

BucketSetting bucketSetting = BucketSetting.builder()
        .bucket("my-bucket")
        .accessKeyId("华为云AK")
        .accessKeySecret("华为云SK")
        .endpoint("obs.cn-north-4.myhuaweicloud.com")
        .build();

try (FileStorage storage = FileStorage.createStorage("obs", bucketSetting)) {
    storage.putObject(PutObjectRequest.of(new File("local.png"), "dir/a.png"));   // 上传
    storage.getObject("dir/a.png");                                               // 下载（便捷重载）
    storage.deleteObject("dir/a.png");                                            // 删除
}
```

---

## 4. 配置项

本模块**没有** Spring Boot 自动装配、`application.yml` 前缀或 properties 文件：依赖中不含 Spring，配置全部通过 Java 配置对象在代码中传入。

### 4.1 对话配置 `ChatClientSetting`

`HuaweiChatClient` 实际读取的字段：

| 字段 | 必填 | 缺省行为（源码） | 说明 |
| --- | --- | --- | --- |
| `appKey` | 是 | 无 | IAM 用户名，同时作为 `domain.name` |
| `appSecret` | 是 | 无 | IAM 密码；走 IAM `password` 认证换取 Token |
| `baseUrl` | 否 | `https://pangu.cn-north-4.myhuaweicloud.com/v1` | 末尾多余的 `/` 会被去掉 |
| `model` | 否 | `pangu-ultra` | 也可链式 `model(...)` 覆盖 |
| `temperature` | 否 | `0.3` | |
| `maxTokens` | 否 | `2048` | |
| `system` | 否 | 不发送 system 消息 | |
| `proxy` | 否 | 直连 | 格式 `host:port` 或 `scheme://host:port`（scheme 会被剥掉） |

固定值（源码常量，不可配）：

| 项 | 值 |
| --- | --- |
| IAM 端点 | `https://iam.cn-north-4.myhuaweicloud.com/v3/auth/tokens` |
| IAM scope | 项目 `cn-north-4` |
| 对话路径 | `POST {baseUrl}/chat/completions` |
| 对话超时 | 连接 30s / 读 90s |
| IAM 请求超时 | 连接 30s / 读 30s |
| 认证头 | `Authorization: Bearer <token>` 与 `X-Auth-Token: <token>` |

IAM Token 首次对话时获取，随后缓存在客户端实例内复用。

### 4.2 存储配置 `BucketSetting`

`HuaweiObsFileStorage` 构造时经 `AbstractFileStorage` 读取的字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `bucket` | 是 | 存储桶名 |
| `accessKeyId` | 是 | 华为云 Access Key |
| `accessKeySecret` | 是 | 华为云 Secret Key |
| `endpoint` | 是 | OBS 端点，如 `obs.cn-north-4.myhuaweicloud.com` |

`BucketSetting` 上还有 `region`、`connectionTimeoutMills`（默认 10s）、`sessionTimeoutMills`（默认 10s）、`extraProperties` 等字段，但本模块构造 `ObsClient` 时只使用 AK/SK/endpoint 三项。

---

## 5. SPI 扩展点

本模块**不定义**新的 SPI 接口；它是底层两个契约的实现方，登记文件随 jar 发布：

| 契约接口 | 登记文件（`src/main/resources/`） | 内容 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` | `huawei=com.chua.huawei.support.HuaweiChatClient` |
| `com.chua.common.support.storage.FileStorage` | `META-INF/services/com.chua.common.support.storage.FileStorage` | `com.chua.huawei.support.storage.HuaweiObsFileStorage` |

按名获取：

```java
// 对话：SPI 名 "huawei" / "pangu"
ChatClient client = ChatClient.create(setting);                       // 按 setting.provider 装配
ChatClient c2 = ServiceProvider.of(ChatClient.class).getExtension("huawei");

// 存储：SPI 名 "obs" / "huawei"
FileStorage storage = FileStorage.createStorage("obs", bucketSetting);
FileStorage s2 = ServiceProvider.of(FileStorage.class).getExtension("huawei");
```

若要扩展新的厂商实现，参照同款机制：实现类标 `@Spi("名字")`，并在自己的 `META-INF/extensions/<契约全限定名>`（或 `META-INF/services/<契约全限定名>`）中登记 `名字=实现类全限定名`。

GraalVM 原生镜像配置位于 `META-INF/native-image/com.chua/utils-support-huawei-starter/`（`reflect-config.json`、`proxy-config.json`、`resource-config.json`）。

---

## 6. 构建与依赖

模块位于聚合模块 `utils-support-cloud-parent` 之下（同层还有 alibaba、tencent、openai 等 starter），构建入口：

```bash
mvn -o -q compile -DskipTests        # 编译验证（仓库交付闸门命令）
mvn clean install -DskipTests        # 安装到本地仓库
```

依赖树（`pom.xml`）：

```
utils-support-huawei-starter (4.0.0.51)
├── com.chua:utils-support-common-starter   # 契约层：ChatClient / FileStorage / HttpClientFactory / Json / Spi 等（同版本）
├── com.huaweicloud:esdk-obs-java 3.26.6    # 华为云 OBS SDK
├── org.slf4j:slf4j-api                     # 日志门面
└── org.projectlombok:lombok (provided)     # 编译期注解
```

- 源码编码 UTF-8、无 BOM；JDK 25。
- 对话能力不依赖 OBS SDK，纯 HTTP（复用底层 `HttpClientFactory`）；存储能力依赖 OBS SDK。
- 交付校验口径：跨模块持久层引用 0、无反向依赖、编译 `EXIT=0`、编码 `BOM=0 / U+FFFD=0`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去哪了 / 说明 |
| --- | --- |
| 流式（SSE）增量输出 | 本实现不产生 `STREAMING` 中间事件；`chat` 以 `START`/`STOP`/`ERROR` 一次性回调，完整响应体在 `STOP` 事件中 |
| 文件/远程附件对话 | `addAttachment` / `addAttachmentUrl` 直接抛 `UnsupportedOperationException` |
| 图片输入、`thinkingEffort`、`session` | 链式方法存在，但当前实现的请求体未使用这些参数 |
| 盘古之外的大模型、OBS 之外的华为云服务 | 分别由同仓其它云服务 starter 或自行实现 `ChatClient` / `FileStorage` SPI |
| 分片上传的原生实现 | 沿用 `AbstractFileStorage` 的默认本地临时目录实现（本类未覆写） |
| Spring Boot 自动装配 / yaml 配置 | 无；配置通过 `ChatClientSetting` / `BucketSetting` 代码传入 |
| 渠道管理等业务逻辑 | 按仓库口径属业务层模块；本模块是最底层工具库，只做厂商协议适配，不含业务表与持久层 |
