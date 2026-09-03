package com.chua.common.support.network.client;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 拦截器链执行器，按<b>洋葱模型</b>组装并执行拦截器序列。
 *
 * <p>链的组装顺序（从外到内）：</p>
 * <pre>{@code
 * [客户端应用层拦截器] → [请求级拦截器] → [客户端网络层拦截器] → 网络调用/doExecute()
 * }</pre>
 *
 * <p>每个拦截器通过 {@link HttpInterceptor.Chain#proceed(ClientRequest)} 将请求放行到下一层；
 * 拦截器也可以不调用 proceed() 直接短路返回（如缓存命中、Mock）。最内层由客户端自身的
 * {@link HttpClient#execute(ClientRequest)} 执行真实的网络调用。</p>
 *
 * <p>本类不是线程安全的，每次执行请求都应创建独立的实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpInterceptor
 * @see ClientRequest
 */
final class InterceptorChain implements HttpInterceptor.Chain {

    /**
     * 当前请求对象。
     */
    private ClientRequest request;

    /**
     * 拦截器序列（KDN app + request 级 + network 已合并）。
     */
    private final List<HttpInterceptor> interceptors;

    /**
     * 当前执行到的下标。
     */
    private int index;

    /**
     * 最内层真实网络调用回调。
     */
    private final RealCall realCall;

    /**
     * 真实网络调用回调接口，由 {@link HttpClient} 提供。
     *
     * <p>当拦截器链执行到最内层时，调用此回调完成真实的网络请求。</p>
     */
    interface RealCall {
        /**
         * 执行真实请求。
         *
         * @param request 请求对象（已被拦截器修改）
         * @return 响应对象
         */
        ClientResponse proceed(ClientRequest request);
    }

    /**
     * 构造拦截器链。
     *
     * @param appInterceptors     客户端应用层拦截器列表，可为空
     * @param requestInterceptor  请求级拦截器（{@link ClientRequest#getInterceptor()}），可为 null
     * @param networkInterceptors 客户端网络层拦截器列表，可为空
     * @param realCall            最内层网络调用回调
     */
    InterceptorChain(List<HttpInterceptor> appInterceptors,
                     HttpInterceptor requestInterceptor,
                     List<HttpInterceptor> networkInterceptors,
                     RealCall realCall) {
        this.interceptors = new ArrayList<>();
        if (appInterceptors != null) {
            this.interceptors.addAll(appInterceptors);
        }
        if (requestInterceptor != null) {
            this.interceptors.add(requestInterceptor);
        }
        if (networkInterceptors != null) {
            this.interceptors.addAll(networkInterceptors);
        }
        this.realCall = realCall;
    }

    /**
     * 获取当前请求。
     *
     * @return 当前请求对象
     */
    @Override
    public ClientRequest request() {
        return request;
    }

    /**
     * 将请求放行到下一层拦截器；已是最后一层时执行真实网络调用。
     *
     * @param request 请求对象
     * @return 响应对象
     */
    @Override
    public ClientResponse proceed(ClientRequest request) {
        if (request != null) {
            this.request = request;
        }
        if (index < interceptors.size()) {
            HttpInterceptor interceptor = interceptors.get(index++);
            try {
                return interceptor.intercept(this, this.request);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("interceptor execute failed: " + e.getMessage(), e);
            }
        }
        return realCall.proceed(this.request);
    }
}