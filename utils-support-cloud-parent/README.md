# utils-support-cloud-parent

云平台、AI 上游和消息平台的适配模块集合。父 POM 只聚合构建；应用依赖具体 starter（版本 `4.0.0.51`），并提供该平台实际需要的凭据、地址与模型名。

## 模块目录

| 模块 README | 实际能力 |
|---|---|
| [alibaba](utils-support-alibaba-starter/README.md) | 通义对话/生图、OSS、短信、语音、支付宝与 CAS 证书 |
| [amazon](utils-support-amazon-starter/README.md) | S3 对象存储与 Bedrock 对话/生图 |
| [baidu](utils-support-baidu-starter/README.md) | 文心对话/生图、BOS 与短信 |
| [bilibili](utils-support-bilibili-starter/README.md) | Index-Translate 翻译 |
| [claude](utils-support-claude-starter/README.md) | Claude Messages 对话、上下文与思考 |
| [cloudflare](utils-support-cloudflare-starter/README.md) | D1 HTTP SQL 客户端与 DNS 记录管理 |
| [dingding](utils-support-dingding-starter/README.md) | 钉钉 AI 对话、机器人与 Webhook 推送 |
| [doubao](utils-support-doubao-starter/README.md) | 火山方舟 Ark 对话 |
| [feishu](utils-support-feishu-starter/README.md) | 飞书机器人长连接/Webhook 与消息收发 |
| [google](utils-support-google-starter/README.md) | Gemini、Imagen、Cloud Storage 与 Colab 客户端 |
| [huawei](utils-support-huawei-starter/README.md) | 盘古对话与 OBS 存储 |
| [huggingface](utils-support-huggingface-starter/README.md) | Hub 仓库、文件上传下载与 LFS |
| [hunyuan](utils-support-hunyuan-starter/README.md) | 混元对话/生图、COS 与飞书推送适配 |
| [ilink](utils-support-ilink-starter/README.md) | 微信 iLink 扫码登录、长轮询消息 |
| [liblib](utils-support-liblib-starter/README.md) | 星流 ultra 文生图、任务查询与签名 |
| [microsoft](utils-support-microsoft-starter/README.md) | Azure OpenAI 对话与图片生成 |
| [modelscope](utils-support-modelscope-starter/README.md) | 魔搭对话/生图与 Hub 模型文件管理 |
| [mosi](utils-support-mosi-starter/README.md) | MOSS 图片生成与语音合成 |
| [openai](utils-support-openai-starter/README.md) | OpenAI 兼容对话、向量、翻译、决策、图像、音频、视频与能力探测 |
| [qiniu](utils-support-qiniu-starter/README.md) | Kodo 存储；对话实现返回固定未支持提示 |
| [qq](utils-support-qq-starter/README.md) | QQ Bot REST/WebSocket 消息 |
| [tencent](utils-support-tencent-starter/README.md) | 微信支付：下单、查询、关单、退款与回调 |
| [tencentcloud](utils-support-tencentcloud-starter/README.md) | 腾讯云短信与模板查询 |
| [webhook](utils-support-webhook-starter/README.md) | 通用文本/Markdown/JSON 消息推送 |
| [wechat](utils-support-wechat-starter/README.md) | 公众号、小程序、企业微信推送与个人微信 Bot |
| [whatsapp](utils-support-whatsapp-starter/README.md) | WhatsApp Cloud API 消息与 Webhook |
| [xunfei](utils-support-xunfei-starter/README.md) | 星火大模型对话 |
| [zai](utils-support-zai-starter/README.md) | Z.AI 对话与图片生成 |
| [zhipu](utils-support-zhipu-starter/README.md) | GLM 对话、CogView 图片与 CogVideoX 视频 |

## 安装与选择

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-openai-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

对话通常从 `ChatClient`、生图从 `ImageClient`、存储从 `FileStorage` 的统一门面进入；不同上游的 SPI 名称与字段见各模块 README。安装 jar 不会创建云账号或授予调用权限；模型可用性取决于真实上游。

微信支付在 tencent，腾讯云短信在 tencentcloud，混元/COS 在 hunyuan，勿按“腾讯”名称互换。需要管理渠道、账号或凭据业务时由上层业务模块处理，本层只实现客户端协议。

JDK 25；在仓库根构建：`mvn -pl utils-support-cloud-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
