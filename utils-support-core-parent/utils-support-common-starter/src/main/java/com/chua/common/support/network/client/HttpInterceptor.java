package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpHeader;

/**
* HTTP 客户端拦截器接口，采用<b>洋葱模型（Onion Model）</b>实现对请求/响应的统一横切处理。
*
* <p>拦截器围绕真实网络调用组成一条责任链，每个拦截器可以在 {@code proceed()} 调用前后
* 自由处理请求与响应，也可以不调用 {@code proceed()} 直接短路返回（例如缓存命中、
* 本地 Mock、权限校验失败直接返回 403 等）。</p>
*
* <p><b>与 {@code addInterceptor}/{@code addNetworkInterceptor} 的分工：</b></p>
* <ul>
*   <li><b>应用层拦截器（addInterceptor）</b> — 最外层，适合统一加 Token、Header、日志、
*       请求预处理、响应后处理、短路 Mock/缓存等业务级横切逻辑；只执行一次。</li>
*   <li><b>网络层拦截器（addNetworkInterceptor）</b> — 紧贴网络调用，适合观察真实请求、
*       统一接入耗时监控、错误码统一包装、网络层日志等；位于应用层拦截器内层。</li>
* </ul>
*
* <p><b>拦截器链组装顺序：</b>
* {@code [应用层拦截器按注册顺序...] → [网络层拦截器按注册顺序...] → 真实网络调用}</p>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* // 应用层：统一加 Token + 日志
* HttpClient client = HttpClientFactory.getClient();
* client.addInterceptor((chain, request) -> {
*     request.header("Authorization", "Bearer " + TokenManager.getToken());
*     System.out.println(">> " + request.getMethod() + " " + request.getUrl());
*     long start = System.currentTimeMillis();
*     ClientResponse resp = chain.proceed(request);
*     System.out.println("<< " + resp.getStatusCode() + " 耗时 " + (System.currentTimeMillis() - start) + "ms");
*     return resp;
* });
*
* // 网络层：接入监控
* client.addNetworkInterceptor((chain, request) -> {
*     ClientResponse resp = chain.proceed(request);
*     metrics.record(request.getUrl(), resp.getStatusCode());
*     return resp;
* });
*
* // 短路：缓存命中直接返回，不走网络
* client.addInterceptor((chain, request) -> {
*     ClientResponse cached = cache.get(request.getUrl());
*     return cached != null ? cached : chain.proceed(request);
* });
* }</pre>
*
* <p><b>线程安全性：</b>拦截器实例会被多个线程共享执行，实现必须保证线程安全。</p>
*
* @author CH
* @see HttpClient#addInterceptor(HttpInterceptor)
* @see HttpClient#addNetworkInterceptor(HttpInterceptor)
* @see ClientRequest
* @see ClientResponse
* @since 4.0.0.42
 */
@FunctionalInterface
public interface HttpInterceptor {

    /**
    * 拦截一次 HTTP 请求并返回响应。
    *
    * <p>实现应调用 {@link Chain#proceed(ClientRequest)} 将请求（可修改）放行到下一层拦截器
    * 或最终的网络调用；不调用 {@code proceed()} 即短路返回。可以在 {@code proceed()} 前后
    * 插入自定义逻辑，也可以包装/替换/捕获异常。</p>
    *
    * @param chain   拦截器链，用于放行请求到下一层
    * @param request 当前请求（可修改后传给 {@code chain.proceed(request)}）
    * @return 响应对象
    * @throws Exception 网络异常或拦截器自身抛出的异常
    */
    ClientResponse intercept(Chain chain, ClientRequest request) throws Exception;

    /**
    * 拦截器链，通过 {@link #proceed(ClientRequest)} 将请求放行到下一层拦截器或网络调用。
    *
    * @see HttpInterceptor
    */
    interface Chain {

        /**
        * 获取当前请求。
        *
        * @return 当前请求对象
        */
        ClientRequest request();

        /**
        * 将请求放行到下一层拦截器（或最终网络调用）。
        *
        * <p>网络层拦截器应只调用一次 {@code proceed()}；应用层拦截器可在需要时
        * 多次调用（如重试）或完全不调用（如短路缓存）。</p>
        *
        * @param request 请求对象，通常为 {@link #request()} 或其修改副本
        * @return 响应对象
        * @throws Exception 下一层执行时抛出的异常
        */
        ClientResponse proceed(ClientRequest request) throws Exception;

        /**
        * 便捷方法：为当前请求添加请求头后放行。
        *
        * <p>等价于 {@code request.header(name, value); return proceed(request);}。
        * 适用于统一注入 Token、公共 Header 等场景。</p>
        *
        * @param name  请求头名称
        * @param value 请求头值
        * @return 响应对象
        * @throws Exception 下一层执行时抛出的异常
        */
        default ClientResponse header(String name, String value) throws Exception {
            return proceed(request().header(name, value));
        }

        /**
        * 便捷方法：为当前请求批量添加请求头后放行。
        *
        * <p>等价于 {@code request.headers(values); return proceed(request);}。</p>
        *
        * @param values 请求头键值集合
        * @return 响应对象
        * @throws Exception 下一层执行时抛出的异常
        */
        default ClientResponse headers(HttpHeader values) throws Exception {
            if (values != null) {
                ClientRequest r = request();
                for (var entry : values.toMap().entrySet()) {
                    r.header(entry.getKey(), entry.getValue());
                }
                return proceed(r);
            }
            return proceed(request());
        }
    }
}
