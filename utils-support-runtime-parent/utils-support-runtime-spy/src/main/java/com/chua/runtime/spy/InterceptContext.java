package com.chua.runtime.spy;

import com.chua.runtime.plugin.InterceptPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 插桩上下文 — 由 ASM 字节码插入的 RuntimeSpy.onIntercept() 创建，
 * 传递给 Interceptor.onIntercept()。
 *
 * <p>携带目标方法元数据（类名、方法名、描述符）、插桩点、时间戳，
 * 以及链路追踪上下文（traceId / spanId / parentSpanId）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class InterceptContext {

    /**
     * 目标类名（内部名格式，如 {@code org/slf4j/Logger}）
     */
    private String className;

    /**
     * 目标方法名
     */
    private String methodName;

    /**
     * 方法描述符（如 {@code (Ljava/lang/String;)V}）
     */
    private String descriptor;

    /**
     * 插桩点
     */
    private InterceptPoint point;

    /**
     * 时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 异常（仅在 EXCEPTION 插桩点有效）
     */
    private Throwable throwable;

    /**
     * 用户附加数据
     */
    private Object userData;

    /**
     * 全局追踪 ID（同一根调用链共享）。
     *
     * <p>由 RuntimeSpy 在 ENTRY 插桩时为根调用生成，子调用继承。
     * 跨线程时可通过 {@link RuntimeSpy#capture()} 与
     * {@link RuntimeSpy#restore(RuntimeSpy.TraceContextSnapshot)} 传递。</p>
     */
    private String traceId;

    /**
     * 当前 Span ID（每次 ENTRY 新建）
     */
    private String spanId;

    /**
     * 父 Span ID（嵌套调用时指向调用方 span，根调用为 null）
     */
    private String parentSpanId;

    /**
     * 设置追踪栈（同时更新 traceId/spanId/parentSpanId）。
     *
     * @param stack 追踪栈对象，null 时不修改任何字段
     */
    public void setTraceStack(TraceStack stack) {
        if (stack == null) {
            return;
        }
        this.traceId = stack.traceId();
        this.spanId = stack.spanId();
        this.parentSpanId = stack.parentSpanId();
    }

    /**
     * 获取人类可读的类名（将内部名 {@code /} 转为 {@code .}）。
     *
     * @return 点分隔的类名
     */
    public String getReadableClassName() {
        return className.replace('/', '.');
    }

    /**
     * 获取方法签名。
     *
     * @return "类.方法(描述符)"
     */
    public String getSignature() {
        return getReadableClassName() + "." + methodName + descriptor;
    }

    /**
     * 是否入口插桩。
     *
     * @return true 表示入口
     */
    public boolean isEntry() {
        return point == InterceptPoint.ENTRY
                || point == InterceptPoint.LOG_PRE
                || point == InterceptPoint.NET_CONNECT_PRE
                || point == InterceptPoint.NET_READ_PRE
                || point == InterceptPoint.NET_WRITE_PRE
                || point == InterceptPoint.FILE_OPEN_PRE
                || point == InterceptPoint.HTTP_REQUEST_PRE
                || point == InterceptPoint.DB_SQL_PRE
                || point == InterceptPoint.THREAD_CREATE_PRE;
    }

    /**
     * 是否出口插桩。
     *
     * @return true 表示出口
     */
    public boolean isExit() {
        return point == InterceptPoint.EXIT
                || point == InterceptPoint.LOG_POST
                || point == InterceptPoint.NET_CONNECT_POST
                || point == InterceptPoint.HTTP_RESPONSE_POST
                || point == InterceptPoint.DB_SQL_POST;
    }

    /**
     * 是否异常插桩。
     *
     * @return true 表示异常
     */
    public boolean isException() {
        return point == InterceptPoint.EXCEPTION;
    }

    /**
     * 是否根 Span（无父调用）。
     *
     * @return true 表示根
     */
    public boolean isRootSpan() {
        return parentSpanId == null;
    }

    /**
     * 追踪栈对象 — 包含 traceId / spanId / parentSpanId 的不可变快照。
     *
     * @param traceId      全局追踪 ID
     * @param spanId       当前 Span ID
     * @param parentSpanId 父 Span ID（根调用为 null）
     * @author CH
     * @since 4.0.0.42
     */
    public record TraceStack(
            String traceId,
            String spanId,
            String parentSpanId
    ) {
    }
}
