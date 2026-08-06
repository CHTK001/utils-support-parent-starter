package com.chua.runtime.spy;

import com.chua.runtime.plugin.InterceptPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 插桩上下文 — 由 ASM 字节码插入的 RuntimeSpy.onIntercept() 创建，
 * 传递给 Interceptor.onIntercept()。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class InterceptContext {

    /**
     * 目标类名
     */
    private String className;

    /**
     * 目标方法名
     */
    private String methodName;

    /**
     * 方法描述符
     */
    private String descriptor;

    /**
     * 插桩点
     */
    private InterceptPoint point;

    /**
     * 时间戳
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
     * 跨线程时可通过 RuntimeSpy.capture()/restore() 传递。</p>
     */
    private String traceId;

    /**
     * 当前 Span ID（每次 ENTRY 新建）。
     */
    private String spanId;

    /**
     * 父 Span ID（嵌套调用时指向调用方 span，根调用为 null）。
     */
    private String parentSpanId;

    /**
     * 追踪栈便捷设置方法（替代三个独立 setter）。
     *
     * @param stack 追踪栈对象
     */
    public void setTraceStack(TraceStack stack) {
        if (stack == null) {
            return;
        }
        this.traceId = stack.getTraceId();
        this.spanId = stack.getSpanId();
        this.parentSpanId = stack.getParentSpanId();
    }

    /**
     * 获取人类可读的类名。
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
     * 追踪栈对象 — 包含 traceId/spanId/parentSpanId 的不可变快照。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TraceStack {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;

        /**
         * 获取 traceId。
         *
         * @return traceId
         */
        public String getTraceId() {
            return traceId;
        }

        /**
         * 获取 spanId。
         *
         * @return spanId
         */
        public String getSpanId() {
            return spanId;
        }

        /**
         * 获取 parentSpanId。
         *
         * @return parentSpanId
         */
        public String getParentSpanId() {
            return parentSpanId;
        }
    }
}