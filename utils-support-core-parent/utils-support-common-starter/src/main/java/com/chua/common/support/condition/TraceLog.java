package com.chua.common.support.condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分支执行的过程日志：链上每个环节追加一条 {@link Step}，由
 * {@code print()} / {@code trace()} 统一渲染。
 *
 * <p>链式调用在单个线程内完成，这里不做同步。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
final class TraceLog {

    /**
     * 单条附加信息的最大长度，超出截断。
     */
    private static final int MAX_DETAIL_LENGTH = 80;

    /**
     * 步骤记录。
     */
    private final List<Step> steps = new ArrayList<>();

    /**
     * 追加一步。
     *
     * @param kind   步骤类型
     * @param label  业务标签，可为 null
     * @param state  执行状态
     * @param detail 附加信息（值或异常），渲染前转字符串并截断
     */
    void add(String kind, String label, Step.State state, Object detail) {
        steps.add(new Step(kind, label, state, describe(detail)));
    }

    /**
     * 全部步骤记录。
     *
     * @return 只读步骤列表
     */
    List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    private static String describe(Object detail) {
        if (detail == null) {
            return null;
        }
        String text = detail instanceof Throwable
                ? describeThrowable((Throwable) detail)
                : String.valueOf(detail);
        if (text.length() > MAX_DETAIL_LENGTH) {
            text = text.substring(0, MAX_DETAIL_LENGTH) + "…";
        }
        return text.replace("\r\n", " ").replace("\n", " ");
    }

    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null
                ? throwable.getClass().getName()
                : throwable.getClass().getName() + ": " + message;
    }
}
