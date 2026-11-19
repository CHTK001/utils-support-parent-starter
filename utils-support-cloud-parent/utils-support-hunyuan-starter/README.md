# utils-support-hunyuan-starter

> 腾讯云生态的 **SPI 扩展模块**：提供腾讯混元大模型的对话客户端与生图客户端、腾讯云 COS 对象存储、飞书机器人消息推送，全部挂接在 `utils-support-common-starter` 的统一 SPI 门面上。Maven 描述为「腾讯混元大模型集成模块」；实际还包含 COS 存储与飞书推送两个 SPI 实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，全仓开启 `--enable-preview`）｜包名：`com.chua.hunyuan.support`（含 `image`、`message`、`storage` 子包）｜四个实现类均 `@since 4.0.0.42`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
  - [3.1 混元对话](#31-混元对话)
  - [3.2 混元生图](#32-混元生图)
  - [3.3 腾讯云 COS 对象存储](#33-腾讯云-cos-对象存储)
  - [3.4 飞书消息推送](#34-飞书消息推送)
- [4. 配置项](#4-配置项)
- [5. SPI 注册与扩展点](#5-spi-注册与扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hunyuan-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块位于 `utils-support-parent-starter/utils-support-cloud-parent/utils-support-hunyuan-starter`，直接父模块为 `utils-support-cloud-parent`（云服务集成父模块），顶层 parent 为 `utils-support-parent-starter`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| AI 对话 | `ChatClient` 的腾讯混元实现 `TencentHunyuanChatClient`（腾讯云 SDK `hunyuan v20230901`） | 不是对话编排 / Agent / RAG 框架（契约在 `utils-support-common-starter` 的 `ai` 包） |
| AI 生图 | `ImageClient` 的混元生图实现 `HunyuanImageClient`（HTTP 调混元生图接口） | 不含参考图 / ControlNet / 异步任务（调用即抛 `UnsupportedOperationException`） |
| 对象存储 | `FileStorage` 的腾讯云 COS 实现 `TencentCosFileStorage`（`cos_api` SDK） | 不含 S3 / OSS / MinIO 等其它对象存储 |
| 消息推送 | `MessagePush` 的飞书机器人 Webhook 实现 `FeishuMessagePush` | 不含钉钉、企业微信等其它渠道 |
| 形态 | 纯 SPI 扩展模块，随包带 GraalVM `native-image` 元数据 | 不依赖 Spring、无自动装配类、无 `application.yml` 配置键 |

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 混元对话 | `ChatClient.create(setting)`，provider `tencent-hunyuan` | 回调式 `chat(...)`；STOP 事件携带完整内容与 Token 用量 |
| 混元生图 | `ImageClient.create(setting)`，provider `tencent-hunyuan` | `generate(prompt)` 返回 `BufferedImage`，自动识别 base64 / URL 两种返回 |
| 深度思考 / 联网搜索 | `client.thinking(true)` / `client.smartSearch(true)` | 透传混元 `enableThinking` / `searchInfo` |
| 技能注入 | `client.skill(skillManager)` | 经 `SkillPrompt.inject` 合入系统提示词 |
| COS 对象存储 | `FileStorage.createStorage("cos", setting)` | put / get / delete / exists / list，SPI 名 `cos` |
| COS 桶配置 | `BucketSetting` | endpoint / region / bucket / accessKeyId / accessKeySecret |
| 飞书消息 | `new FeishuMessagePush(environment)`，SPI 名 `feishu` | Webhook 发送文本 / 富文本（`post`） |
| 会话与历史 | `client.history(...)` / `addUserHistory(...)` / `addAssistantHistory(...)` / `newChat()` | 外部历史优先于内部累积历史 |

---

## 3. 快速开始

### 3.1 混元对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;

ChatClient client = ChatClient.create(ChatClientSetting.builder()
        .provider("tencent-hunyuan")      // 注册 key；注解别名还有 "tencent"
        .appKey("你的 SecretId")          // 腾讯云 API 密钥 SecretId
        .appSecret("你的 SecretKey")      // 腾讯云 API 密钥 SecretKey
        .model("hunyuan-pro")             // 不设置时默认 hunyuan-pro
        .build());

client.system("你是一名严谨的工程师")
      .temperature(0.5)
      .maxTokens(2048)
      .chat("用一句话介绍混元大模型", resp -> {
          switch (resp.getState()) {
              case START -> { /* 请求已发出 */ }
              case STOP -> System.out.println(resp.getFullContent());
              case ERROR -> System.err.println(resp.getErrorMessage());
              case STREAMING -> { /* 本实现按非流式返回，不会出现 */ }
          }
      });
```

说明（均来自实现源码）：

- 地域固定为 `ap-guangzhou`（`DEFAULT_REGION`），接入端点由腾讯云 SDK 决定。
- 未设置温度时默认 `0.3`；`topP` 固定 `0.7`；`stream=false`（非流式，一次返回）。
- STOP 事件的 `usage` 为 `AiUsage`：`inputTokens` / `outputTokens` / `totalTokens` 来自混元 `Usage`，`provider` 记为 `tencent-hunyuan`。
- 系统提示词可被 `SkillManager` 注入的技能提示词增强（`SkillPrompt.inject`）。
- `addAttachment(...)` / `addAttachmentUrl(...)` 不支持，调用抛 `UnsupportedOperationException`。

### 3.2 混元生图

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import java.awt.image.BufferedImage;

ImageClient image = ImageClient.create(ImageClientSetting.builder()
        .provider("tencent-hunyuan")
        .appKey("你的混元 API Key")
        .build());

BufferedImage img = image.model("hunyuan-pro")
        .size(1024, 1024)
        .prompt("一只穿宇航服的橘猫，扁平插画风")
        .generate(null);          // 传 null 时使用链式设置的 prompt
```

说明：

- 默认接口地址 `https://api.hunyuan.cloud.tencent.com/v1`（`DEFAULT_URL`），可用 `ImageClientSetting.baseUrl` 覆盖；实际请求 `POST {baseUrl}/chat/completions`，认证头 `Authorization: Bearer {appKey}`，连接超时 30s、读取超时 120s。
- 返回的 `content` 可能是 base64（`data:image` 前缀、`/9j/`、`iVBOR` 开头）或图片 URL，实现自动识别；URL 形态会再下载（读取超时 60s）转成 `BufferedImage`。
- `referenceImage(...)` / `imageStrength(...)` / `controlType(...)` / `createTask(...)` / `queryTask(...)` 不支持，调用抛 `UnsupportedOperationException`；`close()` 为空实现。

### 3.3 腾讯云 COS 对象存储

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import java.io.File;

FileStorage storage = FileStorage.createStorage("cos", BucketSetting.builder()
        .endpoint("https://cos.ap-guangzhou.myqcloud.com")
        .region("ap-guangzhou")
        .bucket("my-bucket-1250000000")   // 不含 APPID 后缀，SDK 自动拼接
        .accessKeyId("你的 SecretId")
        .accessKeySecret("你的 SecretKey")
        .build());

storage.putObject(PutObjectRequest.of(new File("a.png"), "dir/a.png"));

GetObjectResult got = storage.getObject("dir/a.png");
got.getInputStream();

boolean exists = storage.existObject(ExistObjectRequest.builder()
        .fileName("a.png").filePath("dir").build()).isExists();

storage.deleteObject("dir/a.png");
storage.close();
```

说明：

- SPI 名 `cos`（注解别名 `tencent`），经 `META-INF/services/com.chua.common.support.storage.FileStorage` 登记；也可直接 `new TencentCosFileStorage(bucketSetting)`。
- `putObject` 成功结果中的 `url` 为 `endpoint + "/" + bucket + "/" + key`。
- `existObject` 遇到 `CosServiceException`（如 404）按「不存在」处理。
- `listObject` 支持 `filePath`（前缀）、`limit`（对应 `maxKeys`）、`marker`；结果被截断时返回下一页 `marker`。

### 3.4 飞书消息推送

```java
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;

MessagePush push = new FeishuMessagePush(new MessageEnvironment()
        .set("feishu.webhookUrl", "https://open.feishu.cn/open-apis/bot/v2/hook/xxxx"));

// 所在方法需声明 throws Exception
MessageResponse resp = push.send(MessageRequest.builder()
        .to("feishu-bot")                // MessageRequest 构建时强制要求接收人非空；飞书 Webhook 场景仅作占位，send 不使用该值
        .subject("构建通知")             // 富文本时作为标题
        .content("main 分支构建成功")
        .build());

if (resp.isSuccess()) {
    System.out.println("已发送: " + resp.getMessageId());
}
```

说明：

- `contentType("rich_text")` 时按富文本（`msg_type=post`）发送，`subject` 作为标题；否则按文本（`msg_type=text`）。
- 成功判定：HTTP 200 且响应体包含 `"StatusCode":0`；`messageId` 为本端生成的 UUID。
- `sendTemplate(templateId, to, params)` 组装 `MessageRequest` 后复用 `send`；类上另有 `registerTemplate(TemplateInfo)` / `getTemplate(String)` / `listTemplates()` 维护内存模板表。
- 也可经 SPI 获取：`ServiceProvider.of(MessagePush.class).getExtension("feishu")`。

---

## 4. 配置项

本模块**不读取任何 Spring / `application.yml` 配置键**（依据：pom 无 Spring 依赖，源码无 `@ConfigurationProperties` / `@Value`），全部配置通过构造参数与设置对象传入：

| 入口 | 键 / 字段 | 必填 | 说明 |
| --- | --- | --- | --- |
| `ChatClientSetting` | `appKey` | 是 | 腾讯云 SecretId |
| `ChatClientSetting` | `appSecret` | 是 | 腾讯云 SecretKey |
| `ChatClientSetting` | `model` | 否 | 默认 `hunyuan-pro` |
| `ChatClientSetting` | `temperature` | 否 | 默认 `0.3` |
| `ChatClientSetting` | `maxTokens` / `system` | 否 | 最大输出 Token / 系统提示词 |
| `ChatClientSetting` | `proxy` | 否 | 如 `http://host:port`、`socks5://host:port`，解析后设入 SDK 的 `HttpProfile` |
| `ImageClientSetting` | `appKey` | 是 | 混元 API Key（Bearer） |
| `ImageClientSetting` | `baseUrl` | 否 | 默认 `https://api.hunyuan.cloud.tencent.com/v1` |
| `ImageClientSetting` | `model` | 否 | 默认 `hunyuan-pro` |
| `ImageClientSetting` | `width` / `height` | 否 | 默认 1024×1024（见下方注意） |
| `MessageEnvironment`（飞书） | `feishu.webhookUrl` | 是 | 机器人 Webhook 地址，缺失时 `send` 抛 `IllegalArgumentException` |
| `MessageEnvironment`（飞书） | `feishu.appId` / `feishu.appSecret` | 否 | 已在 `@SpiDescribe` 声明（API 模式预留），当前 `send` 仅使用 webhookUrl |
| `BucketSetting`（COS） | `endpoint` | 是 | 如 `https://cos.ap-guangzhou.myqcloud.com` |
| `BucketSetting`（COS） | `region` | 是 | 如 `ap-guangzhou` |
| `BucketSetting`（COS） | `bucket` | 是 | 存储桶名，不含 APPID 后缀 |
| `BucketSetting`（COS） | `accessKeyId` / `accessKeySecret` | 是 | 腾讯云 SecretId / SecretKey |

> 注意：① 混元对话地域固定 `ap-guangzhou`，暂无配置项；`ChatClientSetting.baseUrl` 在对话实现中未被读取（走腾讯云 SDK 端点）。② 混元生图当前请求体仅包含 `model`、`messages`、`stream`、`max_tokens`，尺寸 / 风格 / 步数等链式参数未随请求发送。

---

## 5. SPI 注册与扩展点

本模块是 SPI 的「实现方」；接口与装配逻辑（`ServiceProvider`、`@Spi`）在 `utils-support-common-starter`。本模块的注册清单：

| SPI 接口 | key | 实现 | 登记文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `tencent-hunyuan`（注解别名 `tencent`） | `com.chua.hunyuan.support.TencentHunyuanChatClient` | `META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | `tencent-hunyuan`（注解别名 `tencent`） | `com.chua.hunyuan.support.image.HunyuanImageClient` | `META-INF/extensions/com.chua.common.support.ai.image.ImageClient` |
| `com.chua.common.support.task.message.MessagePush` | `feishu` | `com.chua.hunyuan.support.message.FeishuMessagePush` | `META-INF/extensions/com.chua.common.support.task.message.MessagePush` |
| `com.chua.common.support.storage.FileStorage` | `cos`（注解别名 `tencent`） | `com.chua.hunyuan.support.storage.TencentCosFileStorage` | `META-INF/services/com.chua.common.support.storage.FileStorage`（标准 `ServiceLoader` 格式） |

- `ChatClient.create(setting)` / `ImageClient.create(setting)` 内部经 `ServiceProvider.of(...).getNewExtension(provider, setting)` 按名实例化；`FileStorage.createStorage("cos", setting)` 同理。
- 四个实现类均可直接 `new`：`TencentHunyuanChatClient(ChatClientSetting)`、`HunyuanImageClient(ImageClientSetting)`、`FeishuMessagePush()` / `FeishuMessagePush(MessageEnvironment)`、`TencentCosFileStorage(BucketSetting)`。
- `TencentHunyuanChatClient` 支持 `SkillManager` 技能注入；`FeishuMessagePush` 通过 `@SpiDescribe` 声明了自身描述与参数清单。
- GraalVM：`src/main/resources/META-INF/native-image/com.chua/utils-support-hunyuan-starter/` 下，`reflect-config.json` 已登记三个 `@Spi` 实现类（`TencentHunyuanChatClient`、`HunyuanImageClient`、`TencentCosFileStorage`）的全部构造器 / 方法 / 字段，`proxy-config.json` 登记 `com.chua.common.support.storage.FileStorage` 接口代理，另有 `resource-config.json`。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（`maven.compiler.release=25`，全仓开启 `--enable-preview`）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译。

```bash
mvn -pl utils-support-cloud-parent/utils-support-hunyuan-starter -am clean package
```

### 6.2 依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` | SPI 接口、HTTP 客户端、JSON、设置对象等 |
| `com.tencentcloudapi:tencentcloud-sdk-java-hunyuan` | `3.2.32` | 混元对话 SDK（`v20230901`） |
| `com.qcloud:cos_api` | `5.6.279` | 腾讯云 COS SDK |
| `org.slf4j:slf4j-api` | 随父 pom | 日志 |
| `org.projectlombok:lombok` | 随父 pom（`provided`） | 编译期注解处理 |

> 混元 SDK 的公共包由 hunyuan 制品的传递依赖提供（pom 注释原文：「混元 SDK（v20230901）：公共 common 由 hunyuan 制品的传递依赖提供」）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去向 / 现状 |
| --- | --- |
| 对话编排 / Agent / RAG / Embedding / 视频生成 | `utils-support-common-starter` 的 `ai.*` 包；本模块只实现 `ChatClient` / `ImageClient` 两个 SPI |
| 其它大模型（openai、claude、zhipu、doubao 等） | `utils-support-cloud-parent` 下对应 starter |
| 其它对象存储（S3 / OSS / MinIO / 七牛等） | `utils-support-cloud-parent` 下对应 starter（amazon、alibaba、qiniu、huawei 等） |
| 文件系统、向量库、任务调度、HTTP 服务端等通用能力 | `utils-support-common-starter` 及对应 starter |
| 混元对话的文件附件（`addAttachment` / `addAttachmentUrl`） | 不支持，调用抛 `UnsupportedOperationException` |
| 混元对话的图片附件（`addImage`） | 当前实现仅记录 URL，请求体未携带图片 |
| 混生图的参考图 / ControlNet / 异步任务（`referenceImage` / `imageStrength` / `controlType` / `createTask` / `queryTask`） | 不支持，调用抛 `UnsupportedOperationException` |
| 混元对话流式输出 | 当前实现固定 `stream=false`，回调只会收到 `START` / `STOP` / `ERROR` |
| Spring 自动装配 | 本模块无 Spring 依赖、无自动配置类 |
