# utils-support-amazon-starter

> 亚马逊云集成模块：**S3 对象存储 + AWS Bedrock 大模型（对话 / 图片生成）**。本模块是 `utils-support-common-starter` 中 `FileStorage`、`ChatClient`、`ImageClient` 三个 SPI 的 Amazon 实现，通过 SPI 名称 `amazon`（存储另含 `s3`）装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.amazon.support`（含 `.image`、`.storage` 子包）

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 S3 对象存储](#31-s3-对象存储)
  - [3.2 Bedrock 对话](#32-bedrock-对话)
  - [3.3 Bedrock 图片生成](#33-bedrock-图片生成)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Amazon/AWS 云能力的 SPI 实现层：S3 存储、Bedrock Converse 对话、Bedrock InvokeModel 图片生成 | 不是业务模块，不含任何业务规则、表、Controller |
| 依赖方向 | 只依赖底层 `utils-support-common-starter` 与 AWS S3 SDK | 不依赖任何上层业务 starter，不依赖 Spring |
| 接入方式 | 上层通过 common 层的 SPI 门面（`ChatClient.create(...)`、`ImageClient.create(...)`、`FileStorage.createStorage(...)`）按名称使用 | 不直接向业务暴露自有门面或注解 |
| 协议 | Bedrock 走 HTTP（`Authorization: Bearer <appKey>`）；S3 走 AWS SDK for Java（path-style） | 不含签名 v4 细节定制、不含 IAM/STS 临时凭证体系 |

---

## 2. 核心 API 与速查表

本模块共 3 个公开实现类，均为 `@Spi` 注册、`@since 4.0.0.42`：

| 需求 | 入口 | SPI 名称 | 说明 |
| --- | --- | --- | --- |
| S3 / 兼容 S3 协议的对象存储 | `AmazonS3FileStorage` | `s3`、`amazon` | 经 `FileStorage.createStorage("s3", setting)` 获取；兼容 MinIO、Ceph、JuiceFS 等 S3 协议存储 |
| AWS Bedrock 对话 | `AmazonChatClient` | `amazon` | 经 `ChatClient.create(setting)` 获取；调用 Bedrock Runtime `Converse` 接口 |
| AWS Bedrock 图片生成 | `AmazonImageClient` | `amazon` | 经 `ImageClient.create(provider, appKey)` 获取；调用 Bedrock Runtime `InvokeModel` 接口 |

### 2.1 AmazonS3FileStorage（`com.chua.amazon.support.storage`）

| 方法 | 说明 |
| --- | --- |
| `putObject(PutObjectRequest)` | 上传字节数组到指定 key，返回 `PutObjectResult`（成功/失败均不抛异常，失败信息在 `message`） |
| `getObject(GetObjectRequest)` / `getObject(String key)` | 下载对象，返回带 `inputStream` 与 `metadata`（size、contentType、lastModified）的 `GetObjectResult` |
| `deleteObject(String key)` | 删除对象 |
| `existObject(ExistObjectRequest)` | 判断对象是否存在 |
| `listObject(ListObjectRequest)` | `ListObjectsV2` 列举；`request.getMarker()` 作为 continuation token，结果被截断时 `marker` 返回下一页 token |
| `createMultipartStorage()` | 继承自 `AbstractFileStorage` 的默认实现：分片暂存本地临时目录，完成时合并后走 `putObject` |
| `close()` | 关闭并释放底层 `AmazonS3` 客户端 |

构造时使用 `BucketSetting` 的 `accessKeyId` / `accessKeySecret` / `endpoint` / `region` 构建 `AmazonS3` 客户端，并开启 **path-style 访问**（`withPathStyleAccessEnabled(true)`）。

### 2.2 AmazonChatClient（`com.chua.amazon.support`）

链式配置后调用，均返回 `this`：

| 方法 | 说明 |
| --- | --- |
| `model(String)` | Bedrock modelId；未设置时默认 `anthropic.claude-3-sonnet-20240229` |
| `temperature(double)` / `maxTokens(int)` | 写入 `inferenceConfig`；未设置时默认 `0.3` / `2048` |
| `system(String)` | 系统提示词，写入请求体 `system` 字段 |
| `thinking(boolean)` | `true` 时请求体追加 `"thinking":true` |
| `thinkingEffort(String)` | 仅保存到本地字段，当前实现未写入请求体（无实际效果） |
| `smartSearch(boolean)` | `true` 时请求体追加 `"enable_search":true` |
| `skill(SkillManager)` | 技能注入：通过 `SkillPrompt.inject(system, skillManager)` 合并进系统提示词 |
| `addUserHistory(String)` / `addAssistantHistory(String)` / `history(List<ChatMessage>)` | 历史管理：写入请求体 `messages`；设置 `history(...)` 后优先使用外部历史 |
| `addImage(String)` / `session(String)` | 仅保存到本地字段，当前实现未写入请求体（无实际效果） |
| `newChat()` | 清空本地历史与图片 |
| `chat(prompt, Consumer<ChatResponse>)` | 同步 HTTP POST `/model/{modelId}/converse`；依次回调 `START` → `STOP`（完整响应体在 `content`/`fullContent`）或 `ERROR` |
| `chat(prompt, consumer, onComplete, onError)` | 同上，附带完成/异常回调 |
| `chatSync(String)` | 见下方「重要行为」 |
| `getSetting()` | 返回构造时传入的 `ChatClientSetting` |

**重要行为（与源码一致）**：

- `chat()` 是**一次性 HTTP 请求**，不是流式：`STOP` 回调携带 Bedrock 返回的完整 JSON 响应体；非 200 时回调 `ERROR`，消息形如 `Bedrock API 返回错误: <状态码> - <响应体>`。
- `chatSync()` 只累积 `STREAMING` 状态的片段，而本实现不产生 `STREAMING` 回调，因此 **`chatSync` 返回空串**；请使用 `chat(prompt, consumer)` 并在 `STOP` 分支读取 `getContent()`。
- 响应体中的 `usage.input_tokens` / `usage.output_tokens` 会被解析为 `AiUsage`（`provider=amazon`，含耗时），附加在 `STOP` 回调上；解析失败仅 debug 日志，不影响结果。
- `addAttachment(...)` / `addAttachmentUrl(...)` 抛 `UnsupportedOperationException`（该服务商不支持文件附件）。
- 超时：连接 30s，读取 90s；支持 `ChatClientSetting.proxy`（格式 `http(s)://host:port`）。

### 2.3 AmazonImageClient（`com.chua.amazon.support.image`）

| 方法 | 说明 |
| --- | --- |
| `model(String)` | Bedrock modelId；未设置时默认 `amazon.titan-image-generator-v1` |
| `size(int width, int height)` | 图片尺寸；未设置时默认 `1024 x 1024` |
| `prompt(String)` / `negativePrompt(String)` | 正/反向提示词（写入 `textToImageParams`） |
| `quality(String)` | 质量等级，默认 `standard`（写入 `imageGenerationConfig`） |
| `seed(Long)` | 随机种子，写入 `imageGenerationConfig` |
| `style(String)` / `steps(Integer)` | 仅保存到本地字段，当前实现未写入请求体（无实际效果） |
| `generate(String prompt)` | 同步生成：POST `/model/{modelId}/invoke`，`taskType=TEXT_IMAGE`、`numberOfImages=1`；从响应 `images[0]` 解码 Base64 为 `BufferedImage` 返回 |
| `close()` | 空实现 |

不支持的能力（均抛 `UnsupportedOperationException`）：`referenceImage(...)`、`imageStrength(...)`、`controlType(...)`、`createTask(...)`、`queryTask(...)`、`models()`。超时：连接 30s，读取 120s。

---

## 3. 快速开始

### 3.1 S3 对象存储

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import java.io.File;

BucketSetting setting = BucketSetting.builder()
        .bucket("my-bucket")
        .region("us-east-1")
        .accessKeyId("<AccessKeyId>")
        .accessKeySecret("<AccessKeySecret>")
        .endpoint("https://s3.us-east-1.amazonaws.com")
        .build();

try (FileStorage storage = FileStorage.createStorage("s3", setting)) {
    // filePath 为远程目录，最终 key = filePath + "/" + 文件名，即 dir/a.png
    storage.putObject(PutObjectRequest.of(new File("a.png"), "dir"));

    GetObjectResult got = storage.getObject("dir/a.png");
    got.getInputStream().readAllBytes();          // 读取内容；got.getMetadata() 含 size/contentType/lastModified

    storage.existObject(/* ExistObjectRequest */ null); // 按需构造 ExistObjectRequest
    storage.deleteObject("dir/a.png");
}
```

> 指向 MinIO / Ceph / JuiceFS 等兼容存储时，把 `endpoint` 换成对应地址即可（客户端已开启 path-style）。

### 3.2 Bedrock 对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;

ChatClientSetting setting = ChatClientSetting.builder()
        .provider("amazon")                                          // SPI 名称
        .appKey("<Bedrock/API Key>")                                 // 以 Bearer 方式放入 Authorization
        .baseUrl("https://bedrock-runtime.us-east-1.amazonaws.com")  // 可省略，默认即此地址
        .model("anthropic.claude-3-sonnet-20240229")                 // 可省略，默认即此模型
        .build();

ChatClient client = ChatClient.create(setting);

client.system("你是一个严谨的助手")
      .maxTokens(2048)
      .chat("用一句话介绍 Amazon Bedrock", resp -> {
          if (resp.getState() == ChatResponse.State.STOP) {
              System.out.println(resp.getContent());   // 完整响应体（JSON）
          } else if (resp.getState() == ChatResponse.State.ERROR) {
              System.err.println(resp.getErrorMessage());
          }
      });
```

> 注意：本实现的 `chatSync(...)` 不产生 `STREAMING` 回调，会返回空串，请使用上面的 `chat(...)` 回调写法。

### 3.3 Bedrock 图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import java.awt.image.BufferedImage;

ImageClient client = ImageClient.create("amazon", "<API Key>");

BufferedImage image = client
        .model("amazon.titan-image-generator-v1")   // 可省略，默认即此模型
        .size(1024, 1024)                           // 可省略，默认 1024x1024
        .negativePrompt("模糊、低质量")
        .generate("一只戴着宇航员头盔的猫");
```

---

## 4. 配置项

本模块无 Spring 配置文件、无 `application.yml` 键；全部配置通过 common 层的三个 Setting 对象以 Builder 传入。

### 4.1 BucketSetting（S3，`com.chua.common.support.storage.setting.BucketSetting`）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `bucket` | String | 是 | 存储桶名 |
| `region` | String | 是 | 区域，如 `us-east-1` |
| `accessKeyId` | String | 是 | 访问密钥 ID |
| `accessKeySecret` | String | 是 | 访问密钥 Secret |
| `endpoint` | String | 是 | 服务端点，如 `https://s3.us-east-1.amazonaws.com` |
| `connectionTimeoutMills` | long | 否 | 连接超时，默认 `10000` 毫秒（本模块未显式读取） |
| `sessionTimeoutMills` | long | 否 | 会话超时，默认 `10000` 毫秒（本模块未显式读取） |
| `extraProperties` | Map | 否 | 附加配置（本模块未显式读取） |

### 4.2 ChatClientSetting（对话，`com.chua.common.support.ai.chat.ChatClientSetting`）

本模块实际读取的字段：

| 字段 | 说明 |
| --- | --- |
| `provider` | 必须为 `amazon`，SPI 装配依据 |
| `appKey` | API 密钥，作为 `Authorization: Bearer <appKey>` |
| `baseUrl` | 覆盖默认地址 `https://bedrock-runtime.us-east-1.amazonaws.com`；末尾 `/` 会被去除 |
| `model` | 默认 modelId（缺省 `anthropic.claude-3-sonnet-20240229`） |
| `temperature` | 默认温度（缺省 `0.3`） |
| `maxTokens` | 默认最大输出 Token（缺省 `2048`） |
| `system` | 默认系统提示词 |
| `proxy` | HTTP 代理，格式 `http://host:port` 或 `socks5://host:port`（仅取 host:port） |

`ChatClientSetting` 中其余字段（`tools`、`topP`、`authScheme`、`routes` 等）在本实现中**未被读取**。

### 4.3 ImageClientSetting（图片，`com.chua.common.support.ai.image.ImageClientSetting`）

本模块实际读取的字段：`provider`（= `amazon`）、`appKey`、`baseUrl`、`model`、`width`、`height`。其余字段（`prompt`、`negativePrompt`、`quality`、`style`、`seed`、`steps` 等）通过链式方法逐次设置，不从 Setting 读取。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，扩展点契约定义在 `utils-support-common-starter`：

| SPI 接口 | 注册文件（`src/main/resources/META-INF/`） | 注册内容 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `extensions/com.chua.common.support.ai.chat.ChatClient` | `amazon=com.chua.amazon.support.AmazonChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | `extensions/com.chua.common.support.ai.image.ImageClient` | `amazon=com.chua.amazon.support.image.AmazonImageClient` |
| `com.chua.common.support.storage.FileStorage` | `services/com.chua.common.support.storage.FileStorage` | `com.chua.amazon.support.storage.AmazonS3FileStorage`（`@Spi({"s3","amazon"})`） |

上层获取方式（均来自 common 层门面，本模块不另设入口）：

```java
ChatClient.create(ChatClientSetting);          // 按 provider 走 SPI
ImageClient.create("amazon", appKey);          // 按 provider 走 SPI
FileStorage.createStorage("s3", bucketSetting);
// 也可直接使用 ServiceProvider：
// ServiceProvider.of(ChatClient.class).getNewExtension("amazon", setting)
```

模块同时内置 GraalVM `native-image` 配置：`META-INF/native-image/com.chua/utils-support-amazon-starter/` 下的 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

---

## 6. 构建与依赖

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-amazon-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖清单（来自本模块 `pom.xml`）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI 契约、HTTP 客户端、JSON 等底层能力 |
| `com.amazonaws:aws-java-sdk-s3` | `1.12.797` | AWS S3 SDK |
| `org.slf4j:slf4j-api` | 随父 pom | 日志门面 |
| `org.projectlombok:lombok` | 随父 pom（provided） | 编译期注解 |

构建命令（仓库根目录）：

```bash
mvn -o -q compile -DskipTests          # 编译
mvn -pl utils-support-cloud-parent/utils-support-amazon-starter -am clean package -DskipTests
```

> 编译需 JDK 25 并开启 `--enable-preview`（父 pom 已统一配置）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 说明 |
| --- | --- |
| SPI 接口与门面（`ChatClient`、`ImageClient`、`FileStorage`、`ServiceProvider`） | `utils-support-common-starter` |
| 其它云厂商（阿里云、腾讯云、华为云、百度、Google、Cloudflare 等） | 同仓 `utils-support-cloud-parent` 下的各自 starter，本模块只做 Amazon/AWS |
| Bedrock 流式（SSE）输出 | 本模块未实现：`chat()` 为一次性 HTTP 请求，`chatSync()` 因无 `STREAMING` 回调而返回空串 |
| 对话文件附件 | `addAttachment` / `addAttachmentUrl` 抛 `UnsupportedOperationException` |
| 图片异步任务、参考图（img2img）、ControlNet、模型列表查询 | `createTask` / `queryTask` / `referenceImage` / `imageStrength` / `controlType` / `models` 均抛 `UnsupportedOperationException` |
| Bedrock 的 IAM 签名 v4、STS 临时凭证、Agent/知识库等高阶能力 | 源码未提供 |
| S3 分片上传的原生实现 | 未覆盖：`createMultipartStorage()` 沿用 `AbstractFileStorage` 的本地临时目录合并方案 |
| 业务编排（渠道管理、会话存储、计费等） | 属于上层业务 starter；本模块只提供云能力调用 |
