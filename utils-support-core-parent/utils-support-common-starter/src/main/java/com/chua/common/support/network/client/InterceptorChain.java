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
 * 网络回调（{@link RealCall}）执行真实的网络调用。</p>
 *
 * <p><b>重试语义：</b>每次 {@code proceed()} 都会基于当前拦截器的位置生成<b>新的子链</b>，
 * 因此同一拦截器可以多次调用 {@code proceed()}（如重试），每次都从<b>下一个</b>拦截器重新执行，
 * 不会跳过任何一层。这与 OkHttp 的拦截器模型语义一致。</p>
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
     * 拦截器序列（客户端应用层 + 请求级 + 客户端网络层已合并）。
     */
    private final List<HttpInterceptor> interceptors;

    /**
     * 当前拦截器下标（指向将要执行的拦截器）。
     */
    private final int index;

    /**
     * 当前请求对象。
     */
    private final ClientRequest request;

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
     * 构造拦截器链（入口，下标从 0 开始）。
     *
     * <p>内部将客户端应用层、请求级、客户端网络层拦截器合并为一条执行序列。</p>
     *
     * @param appInterceptors     客户端应用层拦截器列表，可为空
     * @param requestInterceptor  请求级拦截器（{@link ClientRequest#getInterceptor()}），可为 null
     * @param networkInterceptors 客户端网络层拦截器列表，可为空
     * @param request             初始请求对象
     * @param realCall            最内层网络调用回调
     */
    InterceptorChain(List<HttpInterceptor> appInterceptors,
                     HttpInterceptor requestInterceptor,
                     List<HttpInterceptor> networkInterceptors,
                     ClientRequest request,
                     RealCall realCall) {
        this(merge(appInterceptors, requestInterceptor, networkInterceptors), 0, request, realCall);
    }

    /**
     * 合并应用层、请求级、网络层拦截器为完整执行序列。
     *
     * @param appInterceptors     客户端应用层拦截器列表
     * @param requestInterceptor  请求级拦截器
     * @param networkInterceptors 客户端网络层拦截器列表
     * @return 合并后的拦截器序列
     */
    private static List<HttpInterceptor> merge(List<HttpInterceptor> appInterceptors,
                                               HttpInterceptor requestInterceptor,
                                               List<HttpInterceptor> networkInterceptors) {
        List<HttpInterceptor> merged = new ArrayList<>();
        if (appInterceptors != null) {
            merged.addAll(appInterceptors);
        }
        if (requestInterceptor != null) {
            merged.add(requestInterceptor);
        }
        if (networkInterceptors != null) {
            merged.addAll(networkInterceptors);
        }
        return merged;
    }

    /**
     * 内部构造子链。
     *
     * @param interceptors 完整拦截器序列
     * @param index        将要执行的拦截器下标
     * @param request      当前请求对象
     * @param realCall     最内层网络调用回调
     */
    private InterceptorChain(List<HttpInterceptor> interceptors, int index,
                             ClientRequest request, RealCall realCall) {
        this.interceptors = interceptors;
        this.index = index;
        this.request = request;
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
     * <p>每次调用都会基于当前下标生成子链，因此同一拦截器多次调用（重试）不会跳过任何层。</p>
     *
     * @param request 请求对象（可为 null，此时沿用当前请求）
     * @return 响应对象
     */
    @Override
    public ClientResponse proceed(ClientRequest request) {
        ClientRequest current = request != null ? request : this.request;
        if (index >= interceptors.size()) {
            return realCall.proceed(current);
        }
        InterceptorChain next = new InterceptorChain(interceptors, index + 1, current, realCall);
        try {
            return interceptors.get(index).intercept(next, current);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("interceptor execute failed: " + e.getMessage(), e);
        }
    }
}