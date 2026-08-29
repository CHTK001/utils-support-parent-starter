# utils-support-modelscope-starter

[ModelScope（魔搭）](https://www.modelscope.cn) AI 服务集成模块。提供三组能力：

| 能力 | 入口 | 说明 |
|---|---|---|
| 文生图 | `ImageClient.create("modelscope", token)` | OpenAI 兼容 `/v1/images/generations`，支持 SD/FLUX/Qwen-Image 等 |
| 多模态对话 | `ChatClient.create("modelscope", token)` | OpenAI 兼容 `/v1/chat/completions`，支持 Qwen/Llama/DeepSeek 等 |
| 模型下载/上传 | `new ModelscopeHubClient(token)` | git LFS 仓库快照、文件下载、git push 上传 |

> ModelScope 没有官方 Java SDK，本模块通过 HTTP / Git 直接对接其 OpenAI 兼容 API-Inference 与 Hub。

## 前置

- ModelScope 账号 + 访问令牌（`https://www.modelscope.cn` → 个人中心 → 访问令牌）
- Maven 依赖（version 由根 `utils.version` 统一管理）：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-modelscope-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

## 文生图

```java
BufferedImage image = ImageClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen-Image")          // 也可 SD/FLUX 等
        .size(1024, 1024)
        .negativePrompt("低质量、模糊")
        .steps(30)
        .seed(42L)
        .generate("一只柴犬在樱花树下，电影感光线");

// 异步任务（适合大模型/长耗时）
String taskId = ImageClient.create("modelscope", "ms-xxx")
        .model("damo/text-to-image-large")
        .createTask("夜景城市");
ImageResponse resp = ImageClient.create("modelscope", "ms-xxx")
        .model("damo/text-to-image-large")
        .queryTask(taskId);
if (resp.getStatus() == ImageResponse.Status.SUCCESS) {
    byte[] bytes = resp.getImageBytes();
}
```

## 多模态对话

```java
// 纯文本多轮
String answer = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2.5-7B-Instruct")
        .temperature(0.7)
        .maxTokens(2048)
        .system("你是助手")
        .addUserHistory("我叫小明")
        .chatSync("你还记得我叫什么吗？");

// 图文多模态
String reply = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2-VL-7B-Instruct")
        .addImage("https://example.com/cat.jpg")      // 也支持本地路径、data URI
        .chatSync("描述这张图");

// 完整响应（带 usage）
ChatSyncResponse resp = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2.5-7B-Instruct")
        .chatSyncWithResponse("hi");
System.out.println(resp.getText());
System.out.println(resp.getUsage());
```

## 模型下载/上传

需本机安装 `git` 与 `git-lfs`（ModelScope 大权重走 LFS）。

```java
ModelscopeHubClient hub = new ModelscopeHubClient("ms-xxx");

// 完整克隆（含 LFS 权重）到 /data/cache/<repo-name>
Path repo = hub.downloadSnapshot("microsoft/Mage-Flow-Turbo", Path.of("/data/cache"));

// 单文件下载（不依赖 git）
hub.downloadFile("microsoft/Mage-Flow-Turbo", "config.json", Path.of("./config.json"));

// 列出仓库文件
List<String> files = hub.listFiles("microsoft/Mage-Flow-Turbo");

// 上传（本地必须是 git 仓库并配置好 user.name/email + LFS）
hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));
```

## 端点

- API-Inference（公网）：`https://api-inference.modelscope.cn`
  - `POST /v1/chat/completions`（OpenAI 兼容）
  - `POST /v1/images/generations`（OpenAI 兼容）
  - `POST /v1/models/{model}/async-infer`（异步任务）
  - `GET  /v1/models/{model}/tasks/{task_id}`（任务查询）
  - `GET  /v1/models`（模型清单）
- Hub：`https://www.modelscope.cn`
  - `GET  /api/v1/models/{owner}/{repo}/repo/files`（文件清单）

## 自定义 baseUrl

默认走公网，如需走代理或自部署 ModelScope：

```java
ImageClient.create("modelscope", "ms-xxx")
        .baseUrl("https://your-proxy.example.com")
        ...
```

`baseUrl` 会自动剥离末尾斜杠和 `/v1` 后缀（路径由客户端拼接）。

## 鉴权

所有 API-Inference 请求需 `Authorization: Bearer <token>`；Hub git 推送使用 `oauth2:<token>@` 内嵌到 URL。Token 缺失时调用会抛 `IllegalStateException`。

## 已知模型节选

`ModelscopeImageClient.models()` / `ModelscopeChatClient.models()` 返回节选自魔搭首页的模型清单。完整列表请调用 `GET /v1/models`：

```java
// 待扩展：可调用 /v1/models 获取完整模型清单（按需实现）
```

## 设计说明

- **为何不用 openai-java 调 chat？** `openai-java` 依赖用于统一 OpenAI 风格客户端抽象（与 `utils-support-openai-starter` 一致），但本模块的 `ModelscopeChatClient` 直接走 `HttpClientFactory` 拼请求体，原因：ModelScope 的图像端点不在 OpenAI SDK 抽象内，统一走项目自带的 HTTP/JSON 工具更直观。
- **下载为何首选 git？** ModelScope 大权重走 Git LFS，HTTP 单文件拉取无法处理 LFS 指针。`git clone` 一次拉全最简单。本地需 `git-lfs install` 一次。
- **Mage 已独立：** Mage（微软）的文生图/编辑/多模态集成在 `utils-support-deeplearning-mage-starter`（SPI `mage`），本模块不重复。

## License

Java 客户端遵循仓库根 LICENSE。ModelScope 平台与模型权重许可参见 https://www.modelscope.cn。
