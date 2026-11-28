# utils-support-openai-starter

OpenAI 协议族的客户端实现。对话、嵌入、翻译与决策复用 OpenAI Java SDK 或其 HTTP 通道；图像、音频和视频分别实现对应公共 SPI。兼容服务能否使用，取决于端点、鉴权和响应结构，不因 provider 别名存在就自动获得全部能力。

## 安装与连接配置

JDK 25：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-openai-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

公共门面位于 `utils-support-common-starter`，由本模块注册实现。官方基址默认 `https://api.openai.com/v1`；第三方服务通常要显式传自己的 API 基址及可用模型，不能只改 provider 名。凭据由运行环境提供，不写进代码。

## 对话、历史与流式结果

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import java.util.List;

var setting = ChatClientSetting.builder()
        .provider("openai")
        .appKey(System.getenv("OPENAI_API_KEY"))
        .baseUrl("https://api.openai.com/v1")
        .model("gpt-4o-mini")
        .maxTokens(1024)
        .build();
try (ChatClient client = ChatClient.create(setting)) {
    client.system("回答简明，无法确认时说明原因。")
            .history(List.of(
                    ChatMessage.builder().role("user").content("我在学习 Java。").build(),
                    ChatMessage.builder().role("assistant").content("可以从集合与类型开始。").build()));
    var answer = client.chatSyncWithResponse("解释 List 与 Set 的区别。");
    System.out.println(answer.getText());
    System.out.println(answer.getUsage());
}
```

`chatSync` 只返回正文；`chatSyncWithResponse` 同时携带推理文本与用量。同步调用强制非流式，不修改客户端的共享 stream 开关。需要逐片接收时：

```java
import com.chua.common.support.ai.chat.ChatResponse;

try (ChatClient client = ChatClient.create(setting)) {
    client.stream(true).chat("给出三个学习建议。", response -> {
        if (response.getState() == ChatResponse.State.STREAMING
                && response.getText() != null) {
            System.out.print(response.getText());
        }
        if (response.getState() == ChatResponse.State.STOP) {
            System.out.println(response.getUsage());
        }
    });
}
```

`history` 提供外部历史，`clearHistory()` 清理会话状态；附件通过 `ChatClient` 的 `attachment(...)` 入口加入，目标模型须支持对应模态。客户端包含可变历史、工具与附件状态，跨会话复用时应主动隔离，不能当作无状态单例。

## 工具调用

```java
import com.chua.common.support.ai.chat.ChatTool;
import java.util.Map;

var tool = ChatTool.builder()
        .name("lookup_order")
        .description("按订单号查询订单状态")
        .parameters(Map.of("type", "object", "properties",
                Map.of("orderNo", Map.of("type", "string")),
                "required", List.of("orderNo")))
        .build();
try (ChatClient client = ChatClient.create(setting)) {
    client.tools(List.of(tool)).toolChoice("auto")
            .chat("查询订单 ORDER-001 的状态。", response -> {
                if (response.getToolCalls() != null) {
                    System.out.println(response.getToolCalls());
                }
            });
}
```

工具定义只描述 JSON Schema，不执行本地函数。宿主必须校验模型参数、实际调用业务接口，再用带 `toolCalls` 的 assistant 历史及带 `toolCallId` 的 tool 消息回传结果后继续对话。工具权限、超时和事务由宿主负责。

`extraHeaders/extraBody`、`responseFormat`、`thinking/thinkingEffort` 等用于适配上游；参数能否生效以该服务协议为准。非 Bearer 鉴权应通过对应 Setting 的 `authScheme/extraHeaders` 配置，不能假定所有模态都复用对话鉴权逻辑。

## 文本嵌入、翻译与类型化决策

```java
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.translate.TranslateClient;
import com.chua.common.support.ai.chat.DecisionQuestion;
import com.chua.common.support.ai.chat.DecisionsClient;

String key = System.getenv("OPENAI_API_KEY");
try (var embedding = EmbeddingClient.create("openai", key)) {
    float[] vector = embedding.model("text-embedding-3-small")
            .dimensions(256).embedding("需要检索的文本");
    System.out.println(vector.length);
}
try (var translator = TranslateClient.create("openai", key)) {
    String text = translator.model("gpt-4o-mini")
            .sourceLang("中文").targetLang("英语").translateSync("你好，世界。");
    System.out.println(text);
}
try (var decisions = DecisionsClient.create("openai", key)) {
    var result = decisions.question(DecisionQuestion.noul("duplicate", "是否重复扣费"))
            .question(DecisionQuestion.choice("department", "应由哪个部门处理？",
                    Map.of("billing", "账单退款", "technical", "技术问题")))
            .decide("我的同一笔订单被收取了两次款项。");
    System.out.println(result);
}
```

翻译使用 Chat Completions 与翻译指令，不是独立机器翻译端点。决策使用 `/decisions` 或显式 `DecisionsProtocol.SYSTEMONE` 的 `/systemone`，需要真实支持该协议的服务与模型；普通 Chat Completions 服务不能直接代替。嵌入维度也须由目标模型支持。

## 图像、语音与视频

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.video.VideoClient;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

try (var images = ImageClient.create("openai", key)) {
    var image = images.model("dall-e-3").size(1024, 1024).generate("雨后的竹林水彩画");
    ImageIO.write(image, "png", Path.of("result.png").toFile());
}
try (var asr = VirtualClient.create("openai-asr", key)) {
    System.out.println(asr.model("whisper-1").language("zh").transcribe(Path.of("speech.wav")));
}
try (var tts = TextToAudioClient.create("openai-tts", key)) {
    Files.write(Path.of("speech.mp3"), tts.model("tts-1").voice("alloy")
            .format("mp3").synthesize("欢迎使用。"));
}
try (var video = VideoClient.create("openai", key)) {
    String taskId = video.model("sora-2").duration(4).createTask("固定镜头拍摄海浪。");
    System.out.println(video.queryTask(taskId));
}
```

- 图像调用 `/images/generations`，解析 `data[0].url` 或 `b64_json`。`referenceImage` 以 Data URI 数组放入同一请求，每次构建载荷后清空；仅适用于接受这种结构的上游，不能等同于 multipart `/images/edits`。强度/ControlNet 不支持，`style` 不下发，风格写进提示词。`createTask/queryTask` 抛不支持异常。
- ASR 使用 `/audio/transcriptions`，TTS 使用 `/audio/speech`，本实现走同步请求；不宣称本地调用包装等同于上游异步任务。
- 视频采用创建任务与轮询分离。`OpenAiVideoClient` 的轮询路径是 `/videos/{id}`；`AgnesVideoClient`（provider `agnes`）单独实现其查询协议。调用方需要有界轮询终态，而非只查一次就认定完成。

## SPI 与其它通道

不同接口有各自的注册名：对话包含 `openai/siliconflow/sensetime/github/gitee/dots/kilo/opencode/pollinations/custom/qianfan`；翻译、决策、图像与音频的别名集合并不相同，应以各接口的 `META-INF/extensions` 为准。对兼容服务直接选择 `openai` 并显式设置 baseUrl 可避免把未注册别名用在错误的接口上。

本模块还提供 `InceptionChatClient`（`inceptionlabs/mercury`）、`PteroChatClient`（`ptero`）与 `EacChatClient`（`eac`）。它们使用各自的网页/公开 API 通道，不是官方 OpenAI 地址的别名。

```java
var ptero = ChatClient.create(ChatClientSetting.builder()
        .provider("ptero")
        .model("deepseek-v4.1-flash:free")
        .extraHeaders(Map.of("x-ptero-mode", "quick"))
        .build());
try (ptero) {
    System.out.println(ptero.chatSync("你好"));
}
```

Ptero 的 appKey 留空表示游客身份，模式通过 `x-ptero-mode` 配置为 `smart/fast/quick/complex/full`。游客令牌、登录状态和模型供应受上游控制；上游 SSE 可能整段生成后才下发，不能据接口流式形式承诺首字延迟。

本模块不会安装浏览器、代理或启动模型服务，也不会因依赖引入自动获取凭据。以上是源码/API 用法，未声称所有上游均已完成真实推理验收。

返回 [云平台目录](../README.md) / [完整目录](../../README.md)。
