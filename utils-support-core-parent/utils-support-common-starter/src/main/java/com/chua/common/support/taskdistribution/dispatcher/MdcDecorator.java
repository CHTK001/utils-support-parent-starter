package com.chua.common.support.taskdistribution.dispatcher;

import org.slf4j.MDC;

import java.util.Map;

/**
 * MDC 上下文装饰器。
 *
 * <p>为任务分发执行器提供 MDC 上下文传递能力，确保链路追踪 ID、任务 ID 等
 * 关键信息在跨线程执行时不会丢失。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * Runnable decorated = MdcDecorator.decorate(original, "taskId", task.getTaskId());
 * decorated.run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MdcDecorator {

    /**
     * 任务 ID 键。
     */
    public static final String KEY_TASK_ID = "taskId";

    /**
     * 链路追踪 ID 键。
     */
    public static final String KEY_TRACE_ID = "traceId";

    /** 创建 MdcDecorator 实例 */
    private MdcDecorator() {
    }

    /**
     * 装饰 Runnable，使用当前线程 MDC 上下文快照。
     *
     * @param runnable 原始 Runnable
     * @return 装饰后的 Runnable
     */
    public static Runnable decorate(Runnable runnable) {
        Map<String, String> contextSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (contextSnapshot != null) {
                    MDC.setContextMap(contextSnapshot);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * 装饰 Runnable，向 MDC 注入一个键值对。
     *
     * @param runnable 原始 Runnable
     * @param key      MDC 键
     * @param value    MDC 值
     * @return 装饰后的 Runnable
     */
    public static Runnable decorate(Runnable runnable, String key, String value) {
        Map<String, String> contextSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (contextSnapshot != null) {
                    MDC.setContextMap(contextSnapshot);
                } else {
                    MDC.clear();
                }
                if (value != null) {
                    MDC.put(key, value);
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * 从 MDC 移除指定键。
     *
     * @param key 键
     */
    public static void remove(String key) {
        if (key != null) {
            MDC.remove(key);
        }
    }

    /**
     * 清除当前线程 MDC 上下文。
     */
    public static void clear() {
        MDC.clear();
    }
}
