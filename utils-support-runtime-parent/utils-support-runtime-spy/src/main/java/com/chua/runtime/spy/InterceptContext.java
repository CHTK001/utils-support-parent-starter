package com.chua.runtime.spy;

import com.chua.runtime.plugin.InterceptPoint;
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
}