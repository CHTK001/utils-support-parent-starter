package com.chua.common.support.network.client;

import java.util.concurrent.CompletableFuture;

/**
 * HTTP 客户端抽象基类，采用<b>模板方法模式（Template Method）</b>封装通用逻辑。
 *
 * <p>本类实现了 {@link HttpClient} 接口的 {@link #execute(ClientRequest)} 方法，
 * 在其周围提供了 {@link #beforeExecute(ClientRequest)} 和 {@link #afterExecute(ClientRequest)}
 * 两个扩展点。子类只需实现 {@link #doExecute(ClientRequest)} 完成底层 HTTP 通信，
 * 无需重复处理配置管理、前置/后置拦截等通用逻辑。
 *
 * <p><b>执行流程：</b>
 * <pre>{@code
 * execute(request)
 *   ├── beforeExecute(request)    ← 扩展点：日志、鉴权、参数校验
 *   ├── doExecute(request)        ← 子类实现：实际 HTTP 通信
 *   └── afterExecute(request)     ← 扩展点：资源清理、统计
 * }</pre>
 *
 * <p><b>子类实现示例：</b>
 * <pre>{@code
 * public class MyHttpClient extends AbstractHttpClient {
 *     public MyHttpClient(ClientSetting setting) {
 *         super(setting);
 *     }
 *
 *     protected ClientResponse doExecute(ClientRequest request) {
 *         // 使用 setting 中的配置（超时、代理等）发起实际 HTTP 请求
 *         // ...
 *         return response;
 *     }
 *
 *     protected void beforeExecute(ClientRequest request) {
 *         // 打印请求日志
 *         System.out.println("Request: " + request.getMethod() + " " + request.getUrl());
 *     }
 * }
 * }</pre>
 *
 * <p><b>配置管理：</b>
 * 子类可以通过 {@link #setting} 字段获取全局客户端配置
 * （超时时间、代理设置、重试策略等），无需自行管理配置参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpClient
 * @see ClientSetting
 * @see DefaultHttpClient
 */
public abstract class AbstractHttpClient implements HttpClient {

    /**
     * 客户端全局配置。
     *
     * <p>包含连接超时、读取超时、代理、重试等参数。
     * 子类在 {@link #doExecute(ClientRequest)} 中应使用此配置初始化底层 HTTP 客户端。
     * 如果构造时传入 null，会使用默认配置 {@link ClientSetting} 的无参构造。
     */
    protected final ClientSetting setting;

    /**
     * 使用指定配置创建抽象 HTTP 客户端。
     *
     * @param setting 客户端全局配置，为 null 时使用默认配置（超时 30s、不重试、无代理）
     */
    protected AbstractHttpClient(ClientSetting setting) {
        this.setting = setting != null ? setting : new ClientSetting();
    }

    /**
     * 执行 HTTP 请求（模板方法）。
     *
     * <p>此方法是模板方法模式的核心，定义了不可变的执行流程：
     * <ol>
     *   <li>调用 {@link #beforeExecute(ClientRequest)} 进行前置处理</li>
     *   <li>调用子类实现的 {@link #doExecute(ClientRequest)} 发起实际请求</li>
     *   <li>在 finally 块中调用 {@link #afterExecute(ClientRequest)} 确保后置处理</li>
     * </ol>
     *
     * @param request 请求对象
     * @return 响应对象
     */
    @Override
    public ClientResponse execute(ClientRequest request) {
        beforeExecute(request);
        try {
            return doExecute(request);
        } finally {
            afterExecute(request);
        }
    }

    /**
     * 执行实际的 HTTP 请求，由子类实现底层通信逻辑。
     *
     * <p>子类在此方法中调用底层 HTTP 库（如 {@link java.net.http.HttpClient}、OkHttp、Apache HttpClient5）
     * 发起实际的网络请求，并将底层响应转换为 {@link ClientResponse}。
     *
     * <p>子类可以通过 {@link #setting} 获取超时、代理、重试等全局配置。
     *
     * @param request 封装好的请求对象
     * @return 响应对象
     */
    protected abstract ClientResponse doExecute(ClientRequest request);

    /**
     * 请求执行前的回调方法（扩展点）。
     *
     * <p>在 {@link #doExecute(ClientRequest)} 之前调用，子类可覆盖此方法以添加前置处理逻辑。
     * 典型用途包括：</p>
     * <ul>
     *   <li>打印请求日志（URL、方法、请求头等）</li>
     *   <li>添加全局鉴权信息（如自动注入 Token）</li>
     *   <li>校验请求参数的合法性</li>
     *   <li>请求计数和限流</li>
     * </ul>
     *
     * <p>默认实现为空方法，子类按需覆盖。</p>
     *
     * @param request 即将执行的请求对象
     */
    protected void beforeExecute(ClientRequest request) {
    }

    /**
     * 请求执行后的回调方法（扩展点）。
     *
     * <p>在 {@link #doExecute(ClientRequest)} 完成后调用（无论成功或异常），
     * 位于 finally 块中确保一定执行。子类可覆盖此方法以添加后置处理逻辑。
     * 典型用途包括：</p>
     * <ul>
     *   <li>资源清理（如关闭临时文件流）</li>
     *   <li>请求耗时统计和监控指标上报</li>
     *   <li>打印响应日志</li>
     * </ul>
     *
     * <p>默认实现为空方法，子类按需覆盖。</p>
     *
     * @param request 已执行的请求对象
     */
    protected void afterExecute(ClientRequest request) {
    }

    /**
     * 异步执行 HTTP 请求（模板方法 + 虚拟线程）。
     *
     * <p>覆写 {@link HttpClient#executeAsync(ClientRequest)} 方法，
     * 使用虚拟线程包装异步执行流程，确保 {@link #beforeExecute(ClientRequest)}
     * 和 {@link #afterExecute(ClientRequest)} 扩展点仍然生效。</p>
     *
     * <p><b>执行流程：</b></p>
     * <ol>
     *   <li>在虚拟线程中执行 {@link #beforeExecute(ClientRequest)}</li>
     *   <li>调用子类实现的 {@link #doExecute(ClientRequest)} 发起请求</li>
     *   <li>在 finally 块中执行 {@link #afterExecute(ClientRequest)}</li>
     * </ol>
     *
     * <p><b>关于虚拟线程：</b></p>
     * <p>虚拟线程（Virtual Threads）是 Java 21 引入的轻量级线程，
     * 由 JVM 管理而非操作系统。与平台线程相比：</p>
     * <ul>
     *   <li>创建成本极低，适合大量并发 I/O 场景</li>
     *   <li>在阻塞操作（如 I/O）时自动让出载体线程</li>
     *   <li>与 {@link HttpClient#execute(ClientRequest)} 完全兼容，无需改动同步代码</li>
     * </ul>
     *
     * @param request 封装好的请求对象
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    @Override
    public CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            beforeExecute(request);
            try {
                return doExecute(request);
            } finally {
                afterExecute(request);
            }
        });
    }

    /**
     * 释放客户端资源。
     *
     * <p>默认实现为空，子类如有连接池或其他资源需要释放，应覆盖此方法。</p>
     */
    @Override
    public void close() {
    }
}
