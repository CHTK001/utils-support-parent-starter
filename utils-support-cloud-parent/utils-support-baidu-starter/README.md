# utils-support-baidu-starter

> 百度云集成模块：通过 `utils-support-common-starter` 的 SPI 契约，提供**文心一言对话、文心一格图片生成、百度云短信、百度云 BOS 对象存储**四类能力的实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.baidu.support`（及 `.image`、`.sms`、`.storage` 子包）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 核心 API 详解](#4-核心-api-详解)
- [5. 配置项](#5-配置项)
- [6. SPI 注册与扩展点](#6-spi-注册与扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-baidu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 百度云四类能力的 SPI **实现模块**：AI 对话、图片生成、短信推送、对象存储 | 不是 SPI 契约定义方（接口在 `utils-support-common-starter`） |
| 实现方式 | 对话走百度千帆 SDK（`qianfan`），存储走百度云 BOS SDK（`bce-java-sdk`），短信走百度云 SMS HTTP API + v3 SDK，图片走文心一格 HTTP API | 不提供百度云其他产品（如计算、NLP、语音等）的实现，源码未提供 |
| 依赖形态 | 纯 SPI 实现，无 Spring 依赖，无 Spring Boot 自动装配 | 不含配置文件键绑定，所有配置通过代码内 `Setting` / `MessageEnvironment` 传入 |
| 注册方式 | `META-INF/extensions/` 与 `META-INF/services/` 登记文件 + GraalVM native-image 配置 | 不提供运行期自动扫描业务包的能力 |

### 1.3 规模

- Java 源文件 **4** 个：`BaiduChatClient`、`BaiduImageClient`、`BaiduSmsMessagePush`、`BaiduBosFileStorage`。
- SPI 扩展登记 **3** 个（`META-INF/extensions/`）+ 服务登记 **1** 个（`META-INF/services/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`proxy-config.json`、`resource-config.json`。
- 四个实现类均为 `@since 4.0.0.42`。

---

## 2. 常用能力速查

| 需求 | 入口 | SPI 名称 | 说明 |
| --- | --- | --- | --- |
| 文心一言对话（同步） | `ChatClient.create(setting)` → `chatSync(prompt)` | `baidu` | 基于千帆 SDK，默认模型 `ernie-3.5-8k` |
| 文心一言对话（回调/流式状态） | `chat(prompt, consumer)` / `chat(prompt, consumer, onComplete, onError)` | `baidu` | 回调收到 `START` / `STOP` / `ERROR` 状态与 `AiUsage` 用量 |
| 深度思考 / 联网搜索 | `thinking(true)` / `smartSearch(true)` | `baidu` | 链式开关；`thinking` 走千帆扩展参数 |
| 文心一格图片生成 | `ImageClient.create(setting)` → `generate(prompt)` | `baidu` | 同步生成，返回 `BufferedImage`，默认尺寸 1024x1024 |
| 百度云短信发送 | `MessagePush.create("baidu-sms")` → `send(request)` | `baidu-sms` | HTTP API，AK/SK 签名认证 |
| 百度云短信模板同步 | `BaiduSmsMessagePush#listTemplates()` | `baidu-sms` | 拉取已审核通过（READY）的模板 |
| BOS 对象存储 | `FileStorage.createStorage("baidu", bucketSetting)` | `baidu`（别名 `bos`） | 上传 / 下载 / 删除 / 存在判断 / 列表 |
| BOS 对象列表与分页 | `listObject(ListObjectRequest)` | `baidu`（别名 `bos`） | 支持 prefix、limit、marker 分页 |

> 速查表中的 `ChatClient` / `ImageClient` / `MessagePush` / `FileStorage` 均为 common-starter 的契约接口；`create(...)` 静态工厂内部按 SPI 名称实例化本模块实现。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-baidu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 文心一言对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClientSetting setting = ChatClientSetting.builder()
        .provider("baidu")                      // SPI 名称
        .appKey("你的 API Key")                  // 对应 setting.getAppKey()
        .appSecret("你的 Secret Key")            // 对应 setting.getAppSecret()
        .model("ernie-3.5-8k")                  // 不填时默认 ernie-3.5-8k
        .build();

ChatClient client = ChatClient.create(setting);

// 同步对话
String answer = client.chatSync("你好，介绍一下你自己");

// 回调式对话（可拿到用量统计）
client.chat("写一首关于秋天的诗", response -> {
    // response.getState(): START / STOP / ERROR
    if (response.getUsage() != null) {
        System.out.println("tokens: " + response.getUsage().getTotalTokens());
    }
});
```

### 3.3 文心一格图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import java.awt.image.BufferedImage;

ImageClientSetting setting = ImageClientSetting.builder()
        .provider("baidu")
        .appKey("你的 API Key")
        .secretKey("你的 Secret Key")
        .model("你的模型名")          // 模型名按百度云控制台配置填写
        .width(1024)
        .height(1024)
        .build();

ImageClient client = ImageClient.create(setting);
BufferedImage image = client.generate("一只戴眼镜的柴犬，像素风");
```

### 3.4 百度云短信

```java
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.baidu.support.sms.BaiduSmsMessagePush;

MessageEnvironment env = new MessageEnvironment();
env.set("sms.accessKey", "你的 AccessKey");   // 必填
env.set("sms.secretKey", "你的 SecretKey");   // 必填

BaiduSmsMessagePush push = new BaiduSmsMessagePush(env);

MessageResponse resp = push.send(MessageRequest.builder()
        .to("13800000000")
        .content("您的验证码是 123456")
        .templateId("你的模板ID")
        .build());

System.out.println(resp.isSuccess());

// 拉取百度云已审核通过的模板列表
push.listTemplates().forEach(t -> System.out.println(t.id() + " " + t.name()));
```

### 3.5 百度云 BOS 对象存储

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.*;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;

BucketSetting bucketSetting = BucketSetting.builder()
        .bucket("你的 bucket 名")
        .accessKeyId("你的 AccessKey")
        .accessKeySecret("你的 SecretKey")
        .endpoint("bos.bj.baidubce.com")   // 按你的区域填写
        .build();

try (FileStorage storage = FileStorage.createStorage("baidu", bucketSetting)) {

    // 上传：of(File, path) 第二参为远程目录，对象键 = 目录 + "/" + 本地文件名，即 dir/a.png
    storage.putObject(PutObjectRequest.of(new java.io.File("a.png"), "dir"));

    // 下载
    GetObjectResult got = storage.getObject("dir/a.png");

    // 存在判断
    boolean exists = storage.existObject(ExistObjectRequest.builder()
            .fileName("a.png").filePath("dir").build()).isExists();

    // 列表（分页）
    ListObjectResult list = storage.listObject(ListObjectRequest.builder()
            .filePath("dir").limit(100).build());

    // 删除
    storage.deleteObject("dir/a.png");
}
```

---

## 4. 核心 API 详解

### 4.1 BaiduChatClient（文心一言对话）

`com.chua.baidu.support.BaiduChatClient`，`@Spi({"baidu"})`，实现 `com.chua.common.support.ai.chat.ChatClient`。

- 构造：`new BaiduChatClient(ChatClientSetting)`；内部创建千帆 SDK 客户端 `Qianfan(appKey, appSecret, baseUrl)`。
- 默认值：模型 `ernie-3.5-8k`（setting 未指定 model 时）、温度 `0.3`、最大输出 tokens `2048`、地址 `https://aip.baidubce.com`（自动去掉尾部 `/`）。
- 链式配置：`model` / `temperature` / `maxTokens` / `system` / `thinking` / `thinkingEffort` / `smartSearch` / `skill` / `addImage` / `addUserHistory` / `addAssistantHistory` / `history` / `session` / `newChat`。
- 技能注入：配置 `SkillManager` 后，通过 `SkillPrompt.inject(system, skillManager)` 将技能说明并入系统提示词。
- 用量回调：`STOP` 响应携带 `AiUsage`（`provider="baidu"`，含 `inputTokens` / `outputTokens` / `totalTokens`）。
- 代理：`ChatClientSetting.proxy` 支持 `http://`、`https://`、`socks5://`、`socks://` 前缀；实现方式是设置 JVM 系统属性 `http.proxyHost` / `http.proxyPort` / `https.proxyHost` / `https.proxyPort`（未写端口时默认 80）。
- 对话入口：`chatSync(prompt)`、`chat(prompt, consumer)`、`chat(prompt, consumer, onComplete, onError)`；请求失败时回调收到 `ERROR` 响应并触发 `onError`。
- `newChat()`：清空内部历史与图片附件，并重置外部传入的历史。

### 4.2 BaiduImageClient（文心一格图片生成）

`com.chua.baidu.support.image.BaiduImageClient`，`@Spi({"baidu"})`，实现 `com.chua.common.support.ai.image.ImageClient`。

- 构造：`new BaiduImageClient(ImageClientSetting)`。
- 鉴权：先以 `client_credentials` 方式请求 `/oauth/2.0/token` 获取 `access_token`（连接 30s / 读取 30s）。
- 生成：`generate(prompt)` POST `/rpc/2.0/ai_custom/v1/text-to-image?access_token=...`，请求体 `{"prompt":"...","size":"宽x高"}`（连接 30s / 读取 120s）；解析响应 `data[0].url` 后下载图片（连接 30s / 读取 60s），返回 `BufferedImage`。
- 默认值：地址 `https://aip.baidubce.com`；尺寸 `1024x1024`。
- 链式配置：`model` / `size(width, height)` / `prompt` / `style`。

### 4.3 BaiduSmsMessagePush（百度云短信）

`com.chua.baidu.support.sms.BaiduSmsMessagePush`，`@Spi("baidu-sms")`，实现 `com.chua.common.support.task.message.MessagePush`。

- 构造：`new BaiduSmsMessagePush()` 或 `new BaiduSmsMessagePush(MessageEnvironment)`；配置通过 `environment.get("sms.accessKey")`、`environment.get("sms.secretKey")` 读取，缺失时抛 `IllegalArgumentException`。
- `getProvider()` 返回 `"baidu-sms"`。
- `send(MessageRequest)`：POST `https://sms.bce.baidu.com/api/v2/sms`，JSON 体为 `phoneNumbers` / `content` / `templateId`；`Authorization` 头为 `bce-auth-v1/{ak}/{时间戳}/1800/host;x-bce-date/{HMAC-SHA256 签名}`，并携带 `X-Bce-Date` 头；HTTP 200 且响应体包含 `"code":0` 视为成功。
- `listTemplates()`：走百度云 SMS v3 SDK（`SmsClient`，endpoint `sms.bj.baidubce.com`），每页 50 条分页拉取，仅保留审核状态 `READY` 的模板；查询失败时抛 `RuntimeException`。
- `getTemplate(templateId)` / `registerTemplate(TemplateInfo)`：内存模板表，可手工登记模板码作为兜底。
- `sendTemplate(templateId, to, params)`：组装 `MessageRequest` 后走 `send`。

### 4.4 BaiduBosFileStorage（百度云 BOS 对象存储）

`com.chua.baidu.support.storage.BaiduBosFileStorage`，`@Spi({"bos", "baidu"})`，继承 `com.chua.common.support.storage.AbstractFileStorage`（`FileStorage` 的骨架实现）。

- 构造：`new BaiduBosFileStorage(BucketSetting)`；内部用 `BosClientConfiguration` + `DefaultBceCredentials(accessKeyId, accessKeySecret)` + `endpoint` 创建 `BosClient`。`bucket` / `accessKeyId` / `accessKeySecret` / `endpoint` 均来自 `BucketSetting`（由父类持有）。
- `putObject(PutObjectRequest)`：上传字节数组，成功返回 `SUCCESS` + key，失败返回 `FAILURE` + 错误信息。
- `getObject(GetObjectRequest)` / `getObject(String key)`：下载对象，返回输入流与 `Metadata`（名称、大小、Content-Type、最后修改时间）；字符串重载会按最后一个 `/` 拆分出路径与文件名。
- `existObject(ExistObjectRequest)`：`doesObjectExist` 判断。
- `listObject(ListObjectRequest)`：按 `filePath` 作为 prefix、`limit` 作为 maxKeys、`marker` 翻页；结果被截断时返回 `nextMarker`。
- `deleteObject(String key)`：删除对象。
- `close()`：`bosClient.shutdown()`。

---

## 5. 配置项

本模块**无 Spring 配置文件键、无 properties/yml 绑定**（源码未提供）；所有配置通过代码内的 Setting 对象或 `MessageEnvironment` 传入。

### 5.1 ChatClientSetting（BaiduChatClient 读取的字段）

| 字段 | 说明 | 必填 | 缺省行为 |
| --- | --- | --- | --- |
| `provider` | SPI 名称，固定传 `baidu` | 是 | — |
| `appKey` | 百度千帆 API Key | 是 | — |
| `appSecret` | 百度千帆 Secret Key | 是 | — |
| `model` | 模型名 | 否 | `ernie-3.5-8k` |
| `baseUrl` | API 地址 | 否 | `https://aip.baidubce.com` |
| `temperature` | 温度 | 否 | `0.3` |
| `maxTokens` | 最大输出 tokens | 否 | `2048` |
| `system` | 系统提示词 | 否 | 不设置 |
| `proxy` | 代理地址，如 `http://127.0.0.1:8080`、`socks5://127.0.0.1:1080` | 否 | 不启用 |

### 5.2 ImageClientSetting（BaiduImageClient 读取的字段）

| 字段 | 说明 | 必填 | 缺省行为 |
| --- | --- | --- | --- |
| `provider` | SPI 名称，固定传 `baidu` | 是 | — |
| `appKey` | 百度 API Key（OAuth client_id） | 是 | — |
| `secretKey` | 百度 Secret Key（OAuth client_secret） | 是 | — |
| `model` | 模型名 | 否 | 源码未提供默认值（请求体中未携带 model） |
| `baseUrl` | API 地址 | 否 | `https://aip.baidubce.com` |
| `width` / `height` | 图片宽高 | 否 | `1024 x 1024` |

### 5.3 MessageEnvironment 键（BaiduSmsMessagePush）

| 键 | 说明 | 必填 | 依据 |
| --- | --- | --- | --- |
| `sms.accessKey` | 百度云 AccessKey | 是 | 缺失时抛 `IllegalArgumentException("sms.accessKey 配置项必填")` |
| `sms.secretKey` | 百度云 SecretKey | 是 | 缺失时抛 `IllegalArgumentException("sms.secretKey 配置项必填")` |
| `sms.signName` | 短信签名 | 否 | `@SpiDescribe` 声明的可选参数；发送逻辑源码未读取该键 |

### 5.4 BucketSetting（BaiduBosFileStorage 读取的字段）

| 字段 | 说明 | 必填 | 依据 |
| --- | --- | --- | --- |
| `bucket` | Bucket 名称 | 是 | 构造后所有对象操作均使用 |
| `accessKeyId` | 百度云 Access Key ID | 是 | `DefaultBceCredentials` |
| `accessKeySecret` | 百度云 Secret Access Key | 是 | `DefaultBceCredentials` |
| `endpoint` | BOS 端点 | 是 | `BosClientConfiguration.setEndpoint` |
| `region` / `connectionTimeoutMills` / `sessionTimeoutMills` / `extraProperties` | BucketSetting 其余字段 | 否 | 本模块实现类源码未读取，行为由 SDK 决定 |

---

## 6. SPI 注册与扩展点

本模块**只实现 common-starter 的既有 SPI 契约，不新增 SPI 接口**。登记文件如下（均位于 `src/main/resources`）：

| SPI 接口 | SPI 名称 | 实现类 | 登记文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `baidu` | `com.chua.baidu.support.BaiduChatClient` | `META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | `baidu` | `com.chua.baidu.support.image.BaiduImageClient` | `META-INF/extensions/com.chua.common.support.ai.image.ImageClient` |
| `com.chua.common.support.task.message.MessagePush` | `baidu-sms` | `com.chua.baidu.support.sms.BaiduSmsMessagePush` | `META-INF/extensions/com.chua.common.support.task.message.MessagePush` |
| `com.chua.common.support.storage.FileStorage` | `baidu` / `bos`（来自 `@Spi` 注解） | `com.chua.baidu.support.storage.BaiduBosFileStorage` | `META-INF/services/com.chua.common.support.storage.FileStorage` |

说明：

- `META-INF/extensions/` 由 common-starter 的 `CustomServiceResolver` 读取（格式 `名称=实现类全名`）；`META-INF/services/` 由 `ServiceLoaderServiceResolver` 读取（每行一个实现类全名），两者都会被 `ServiceProvider` 发现。
- `BaiduSmsMessagePush` 额外标注 `@SpiDescribe`（`type="SMS"`，名称「百度云短信」），供 SPI 元数据展示与参数提示。
- GraalVM native-image 配置位于 `META-INF/native-image/com.chua/utils-support-baidu-starter/`：`reflect-config.json` 注册三个实现类的全部构造器/方法/字段，`proxy-config.json` 注册 `FileStorage` 代理，`resource-config.json` 放行 `META-INF/services/.*` 与 `META-INF/extensions/.*` 资源。

---

## 7. 构建与依赖

- 父模块：`com.chua:utils-support-cloud-parent:4.0.0.51`（其上级为 `utils-support-parent-starter`，仓库 JDK 统一为 25）。
- 编译：在本模块目录执行 `mvn -o -q compile -DskipTests` 即可（遵循仓库统一构建方式）。

依赖清单（`pom.xml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（即 4.0.0.51） | SPI 契约、Setting、请求/结果模型 |
| `com.baidubce:bce-java-sdk` | `0.10.304` | BOS 对象存储、SMS v3 模板查询（已排除 `jdk.tools`） |
| `com.baidubce:qianfan` | `0.1.6` | 千帆大模型对话 SDK |

---

## 8. 边界：这些能力不在本模块

- **SPI 契约本身不在本模块**：`ChatClient`、`ImageClient`、`MessagePush`、`FileStorage`、`ChatClientSetting`、`ImageClientSetting`、`BucketSetting`、`MessageEnvironment` 等接口与模型均定义于 `utils-support-common-starter`。
- **其他百度云产品不在本模块**：除上述对话、图片、短信、BOS 存储四个实现外，源码未提供百度云其他产品（如计算、数据库、NLP、语音等）的任何入口。
- **对话侧不支持**：文件附件（`addAttachment` 抛 `UnsupportedOperationException("该服务商不支持文件附件")`）、远程文件附件（`addAttachmentUrl` 抛 `UnsupportedOperationException("该服务商不支持远程文件附件")`）。
- **图片侧不支持**：参考图（`referenceImage` 两个重载均抛 `UnsupportedOperationException`）、参考图强度（`imageStrength`）、ControlNet（`controlType`）、异步任务（`createTask` / `queryTask` 均抛 `UnsupportedOperationException`，请使用 `generate()` 同步生成）。
- **无 Spring 装配**：本模块不依赖 Spring，无自动配置类；在 Spring 环境中使用时需自行创建实例或通过 `ServiceProvider` 获取。
- **无本地配置文件**：没有 `application.yml` 键绑定与 `spring.factories` / `AutoConfiguration.imports`，配置只能通过代码传入。
