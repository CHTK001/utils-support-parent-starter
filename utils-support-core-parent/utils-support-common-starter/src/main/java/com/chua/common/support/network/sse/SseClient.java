package com.chua.common.support.network.sse;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.spi.ServiceProvider;

import java.util.concurrent.CompletableFuture;

/**
* SSE（Server-Sent Events）客户端接口
*
* <p>提供统一的 SSE 事件流抽象，支持通过 SPI 机制切换底层实现
* （JDK HttpClient / OkHttp / HttpClient5 等）。SSE 是服务端推送技术，
* 用于实时接收 AI 对话流式响应、日志推送、消息通知等场景。
*
* <h3>使用示例</h3>
* <pre>{@code
*   // 通过 SPI 获取实现
*   SseClient client = ServiceProvider.of(SseClient.class).getExtension("okhttp");
*
*   // 构建请求并连接
*   SseRequest request = SseRequest.builder()
*       .url("https://api.example.com/stream")
*       .method(HttpMethod.POST)
*       .header("Authorization", "Bearer sk-xxx")
*       .body("{\"prompt\":\"hello\"}")
*       .build();
*
*   SseConnection conn = client.connect(request, new SseListener() {
*       public void onData(String data) {
*           System.out.println("收到: " + data);
*       }
*       public void onError(Throwable error) {
*           error.printStackTrace();
*       }
*   });
*
*   // 关闭连接
*   conn.close();
* }</pre>
*
* @author CH
* @since 2026/07/21
* @see SseRequest
* @see SseListener
* @see SseConnection
 */
public interface SseClient {

    /**
    * 建立 SSE 连接并开始监听事件
    *
    * <p>根据 {@link SseRequest} 中的参数（URL、方法、请求头、请求体等）发送 HTTP 请求，
    * 然后持续读取服务端推送的 SSE 事件流，逐行解析 {@code data:} 前缀并回调 {@link SseListener}。
    *
    * <p>返回的 {@link SseConnection} 可用于主动断开连接。连接在以下情况自动断开：
    * <ul>
    *   <li>服务端关闭连接（正常结束）</li>
    *   <li>发生网络异常或超时</li>
    *   <li>调用方主动调用 {@link SseConnection#close()}</li>
    * </ul>
    *
    * @param request  SSE 请求参数
    * @param listener 事件监听器
    * @return 连接句柄，可用于主动关闭
     */
    SseConnection connect(SseRequest request, SseListener listener);

    /**
    * 获取当前客户端的连接句柄。
    *
    * @return 当前连接句柄，未建立连接时返回 null
     */
    default SseConnection getConnection() {
        return null;
    }

    /**
    * 获取默认的 SSE 客户端实现
    *
    * <p>通过 SPI 机制自动发现并返回优先级最高的可用实现。
    * 优先级顺序：OkHttp &gt; HttpClient5 &gt; JDK（内置）。
    *
    * @return SseClient 实例
     */
    static SseClient create() {
        return ServiceProvider.of(SseClient.class).getExtension();
    }

    /**
    * 建立 SSE 连接并返回连接句柄的异步包装。
    *
    * <p>使用默认客户端实现发起连接，返回已完成的 {@link CompletableFuture}。
    *
    * @param request  SSE 请求参数
    * @param listener 事件监听器
    * @return 已完成的连接句柄
     */
    static CompletableFuture<SseConnection> connectAsync(SseRequest request, SseListener listener) {
        SseClient client = create();
        client.connect(request, listener);
        return CompletableFuture.completedFuture(client.getConnection());
    }

    /**
    * 创建便捷的 GET 请求构建器
    *
    * @param url 请求地址
    * @return 默认实现的 SSE 客户端连接
     */
    static SseConnection get(String url, SseListener listener) {
        SseRequest request = SseRequest.builder()
                .url(url)
                .method(HttpMethod.GET)
                .build();
        return create().connect(request, listener);
    }

    /**
    * 创建便捷的 POST 请求构建器
    *
    * @param url  请求地址
    * @param body 请求体（JSON 字符串）
    * @return 默认实现的 SSE 客户端连接
     */
    static SseConnection post(String url, String body, SseListener listener) {
        SseRequest request = SseRequest.builder()
                .url(url)
                .method(HttpMethod.POST)
                .body(body)
                .build();
        return create().connect(request, listener);
    }
}
