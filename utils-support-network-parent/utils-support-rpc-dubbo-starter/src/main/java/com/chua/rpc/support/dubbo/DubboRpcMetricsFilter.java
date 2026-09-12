package com.chua.rpc.support.dubbo;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
* Dubbo 服务端 RPC 指标拦截器。
*
* <p>通过 Dubbo SPI 的 {@link Activate} 注解自动生效（provider 调用链），
* 统计每次调用的耗时、成功/失败、方法级计数，供 {@link DubboRpcMetricsHolder} 暴露到监控 端点。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Activate(group = CommonConstants.PROVIDER)
@Slf4j
public class DubboRpcMetricsFilter implements Filter {

    /**
    * 在 提供者 侧拦截一次 RPC 调用，记录开始/结束与结果。
    *
    * @param invoker    Dubbo 服务代理对象
    * @param invocation 调用信息（方法名、参数、服务标识）
    * @return 调用结果
    * @throws RpcException Dubbo 远程调用异常
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String methodKey = buildMethodKey(invoker, invocation);
        long start = System.currentTimeMillis();
        log.debug("[DubboRpcMetricsFilter] invoke {}", methodKey);
        DubboRpcMetricsHolder.onStart(methodKey);
        try {
            Result result = invoker.invoke(invocation);
            long duration = System.currentTimeMillis() - start;
            if (result.hasException()) {
                Throwable e = result.getException();
                String errorMsg = e != null ? (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) : "null exception";
                DubboRpcMetricsHolder.onFailure(methodKey, duration, errorMsg);
            } else {
                DubboRpcMetricsHolder.onSuccess(methodKey, duration);
            }
            return result;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            DubboRpcMetricsHolder.onFailure(methodKey, duration, error);
            throw e;
        } finally {
            DubboRpcMetricsHolder.onComplete();
        }
    }

    /**
    * 构建方法键（接口全名 + "." + 方法名）。
    *
    * @param invoker    Dubbo 服务代理
    * @param invocation 调用信息
    * @return 方法键
     */
    private String buildMethodKey(Invoker<?> invoker, Invocation invocation) {
        Class<?> iface = invoker != null && invoker.getInterface() != null ? invoker.getInterface() : Object.class;
        return iface.getName() + "." + (invocation != null ? invocation.getMethodName() : "unknown");
    }
}