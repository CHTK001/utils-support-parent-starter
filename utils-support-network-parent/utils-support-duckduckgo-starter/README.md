# utils-support-duckduckgo-starter

把 <https://duck.ai> 的网页对话封装成统一的 `ChatClient`，不需要 API Key。

## 特点

- **同源接口**：`/duckchat/v1/status`、`/duckchat/v1/chat` 都在 `duck.ai` 域下，走浏览器通道没有跨域问题。
- **真实挑战**：`x-vqd-hash-1` 是官方下发的 base64 脚本，读取 `navigator.userAgent` 与 DOM 尺寸；本模块在真实页面里执行它，不做硬编码。
- **浏览器复用**：一个 `DuckChatClient` 对应一个浏览器进程，多次对话复用同一个页面，`close()` 时释放。
- **流式 + 同步**：`chat(...)` 逐段回调，`chatSync(...)` 聚合返回。

## 使用

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;

ChatClient client = ChatClient.create(ChatClientSetting.builder()
        .provider("duck")          // 或 "duckduckgo"
        .model("gpt-6-luna")
        .timeoutMillis(180_000L)
        .build());

try {
    System.out.println(client.chatSync("你好"));
    client.chat("讲个笑话", event -> {
        if (event.getState() == ChatResponse.State.STREAMING) {
            System.out.print(event.getContent());
        }
    });
} finally {
    client.close();
}
```

## 控制项

| 控制项 | 默认 | 说明 |
| --- | --- | --- |
| `x-duck-headless` / `duck.headless` / `DUCK_HEADLESS` | `true` | 设为 `false` 使用有头模式 |
| `x-duck-chrome` / `duck.chrome` / `DUCK_CHROME` | 自动探测 | 指定 Chromium 可执行文件 |
| `x-cdp-ua` / `User-Agent` / `chua.browser.userAgent` | 浏览器真实 UA | 固定自定义 UA |
| `x-cdp-rotate-ua` / `chua.browser.rotateUserAgent` | `false` | 每个页面随机轮换 UA |
| `baseUrl` | `https://duck.ai` | 站点根地址 |

> UA 覆盖会同时写入匹配的 `Sec-CH-UA` 与 `navigator.userAgentData`，不会出现「UA 是 Chrome、客户端提示是 Edge」的穿帮。

## 协议说明

1. `GET /duckchat/v1/status`，请求头 `x-vqd-accept: 1`，响应头 `x-vqd-hash-1` 为 base64 挑战脚本。
2. 在页面上下文执行脚本，得到 `{server_hashes, client_hashes, signals, meta}`。
3. 对 `client_hashes` 每项做 `SHA-256` 再 Base64，与 `server_hashes`、`meta` 一起 JSON 后 Base64，作为请求头 `x-vqd-hash-1`；
   `meta` 必须补上 `origin`、`stack`、`duration`，否则上游返回 `ERR_CHALLENGE`。
4. `POST /duckchat/v1/chat`，请求体包含 `model`、`messages`、`canUseTools` 等字段，响应为 SSE：
   `data: {"message":"..."}`，以 `data: [DONE]` 结束。

### 消息结构（实测）

- `user` 消息的 `content` 用 parts 数组：`[{"type":"text","text":"..."}]`。
- `assistant` 消息的 `content` 必须是**字符串**，传 parts 数组会被判 `400 ERR_BAD_REQUEST`。
- 上游没有 `system` 角色，传 `system` 同样会 `400`；`system(...)` 会作为第一条 `user` 消息下发。


## 已知限制

- 上游不支持 `temperature`、`maxTokens`、`topP`、`stop`、`seed`。
- Duck.ai 对出口 IP 有风控：可能返回 `HTTP 418 ERR_BN_LIMIT`，此时官方网页同样不可用，属于网络环境问题；客户端会重试 3 次。
- 模型列表随站点更新，`models()` 只列出常用项；实际可用模型以 `/duckchat/v1/models` 为准。
