package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.spy.InterceptContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程追踪 处理器 — 拦截线程池任务提交与线程创建，记录线程事件并支持跨线程追踪。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code java.util.concurrent.ThreadPoolExecutor} — execute / submit（任务提交入口）</li>
 *   <li>{@code java.lang.Thread} — start（线程创建入口）</li>
 *   <li>{@code java.util.concurrent.CompletableFuture} — runAsync / supplyAsync（异步编排入口）</li>
 * </ul>
 *
 * <p>跨线程追踪说明：真正的上下文传播（capture / restore / wrap）由
 * {@link com.chua.runtime.spy.RuntimeSpy} 提供；本 Handler 负责把线程池/线程/异步任务
 * 作为独立的传输事件纳入链路，便于观察异步执行对调用链的影响。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ThreadHandler extends AbstractAppHandler {

    /**
     * thread游泳池执行器 类内部名
     */
    private static final String THREAD_POOL_EXECUTOR = "java/util/concurrent/ThreadPoolExecutor";

    /**
     * Thread 类内部名
     */
    private static final String THREAD_CLASS = "java/lang/Thread";

    /**
     * completable期货 类内部名
     */
    private static final String COMPLETABLE_FUTURE = "java/util/concurrent/CompletableFuture";

    /**
     * 线程池任务提交方法集合
     */
    private static final String[] POOL_METHODS = {"execute", "submit"};

    /**
     * Thread 创建方法集合
     */
    private static final String[] THREAD_METHODS = {"start"};

    /**
     * completable期货 异步编排方法集合
     */
    private static final String[] FUTURE_METHODS = {"runAsync", "supplyAsync"};

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /**
     * 创建 thread处理器 实例
    */
    public ThreadHandler() {
        super();
        this.started = new AtomicBoolean(false);
    }

    /**
     * 名称
    */
    @Override
    public String name() {
        return "thread-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "thread.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.THREAD;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(THREAD_POOL_EXECUTOR, POOL_METHODS);
        registerAll(THREAD_CLASS, THREAD_METHODS);
        registerAll(COMPLETABLE_FUTURE, FUTURE_METHODS);
    }

    /**
     * onintercept
    */
    @Override
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        super.onIntercept(ctx);
    }

    /**
     * deriveoperation
    */
    @Override
    protected String deriveOperation(InterceptContext ctx) {
        String method = ctx.getMethodName();
        if ("execute".equals(method) || "submit".equals(method)) {
            return "THREAD_POOL:" + method.toUpperCase();
        }
        if ("start".equals(method)) {
            return "THREAD_START";
        }
        if ("runAsync".equals(method) || "supplyAsync".equals(method)) {
            return "ASYNC:" + method;
        }
        return method.toUpperCase();
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.THREAD)
                .host("thread")
                .port(0)
                .path("/")
                .build();
    }
}