package com.chua.common.support.network.client;


/**
* 通用异步回调接口，用于 HTTP 客户端的异步调用结果通知。
*
* <p>本接口定义了统一的异步回调契约，支持成功回调和失败回调两种场景。
* 配合 {@link HttpClient#executeAsync(ClientRequest, Callback)} 使用，
* 在异步 HTTP 请求完成后通过回调通知调用方结果。</p>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* HttpClientFactory.of("http://api.example.com/users")
*     .getAsync(new Callback<ClientResponse>() {
*         public void onSuccess(ClientResponse response) {
*             System.out.println("状态码: " + response.getStatusCode());
*             System.out.println("响应体: " + response.getBodyString());
*         }
*         public void onError(Throwable error) {
*             System.err.println("请求失败: " + error.getMessage());
*         }
*     });
* }</pre>
*
* <p><b>Lambda 简化写法：</b></p>
* <pre>{@code
* // 仅关注成功回调
* HttpClientFactory.of("http://api.example.com/users")
*     .getAsync(resp -> System.out.println(resp.getBodyString()));
*
* // 同时关注成功和失败
* HttpClientFactory.of("http://api.example.com/users")
*     .getAsync(
*         resp -> System.out.println(resp.getBodyString()),
*         err  -> System.err.println(err.getMessage())
*     );
* }</pre>
*
* @param <T> 回调结果类型，通常为 {@link ClientResponse}
* @author CH
* @since 4.0.0.42
* @see HttpClient#executeAsync(ClientRequest, Callback)
* @see HttpClientBuilder
 */
@FunctionalInterface
public interface Callback<T> {

    /**
    * 异步操作成功时的回调方法。
    *
    * <p>当异步 HTTP 请求成功完成时调用此方法，传入执行结果。
    * 执行结果中可能包含成功响应，也可能包含非 2xx 状态码的响应，
    * 调用方应在回调中自行判断 {@code result.isSuccess()} 决定业务逻辑。</p>
    *
    * @param result 异步执行结果，通常为 {@link ClientResponse} 对象
     */
    void onSuccess(T result);

    /**
    * 异步操作失败时的回调方法。
    *
    * <p>当异步 HTTP 请求因网络异常、超时、解析错误等原因失败时调用此方法。
    * 默认实现为空方法，调用方可按需覆盖以处理异常。</p>
    *
    * @param error 异常信息，包含失败原因和堆栈
     */
    default void onError(Throwable error) {
    }
}
