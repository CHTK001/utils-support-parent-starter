package com.chua.common.support.lang.process;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * <p>
 *           Consumer   Appendable     AutoCloseable
 *
 *
 * @author CH
 * @since 2023-04-05
 * @version 1.0.0
 */
public interface ProgressBarConsumer extends Consumer<String>, Appendable, AutoCloseable {
    /**
     * 获取 MaxRenderedLength。
     *
     *
     * @return
     * @example getMaxRenderedLength()                   80                     80
     */
    int getMaxRenderedLength();
    /**
     * 接收并处理输入。
     *
     *
     * @param rendered                                         "[#####-----] 50% Completed"
     * @example accept("[#####-----] 50% Completed")
     */
    @Override
    void accept(String rendered);
    /**
     * 移除。
     *
     *
     * @example clear()
     */
    default void clear() {
        accept("\r" + Util.repeat(' ', getMaxRenderedLength()) + "\r");
    }
    /**
     * 添加。
     *
     *
     * @param csq                                   "Processing..."
     * @return
     * @example append("Processing...")                               Processing...
     */
    @Override
    default ProgressBarConsumer append(CharSequence csq) {
        accept(csq.toString());
        return this;
    }
    /**
     * 添加。
     *
     *
     * @param csq                                     "Processing data..."
     * @param start                                   0
     * @param end                                        10
     * @return
     * @example append("Processing data...", 0, 10)             "Processing"
     */
    @Override
    default ProgressBarConsumer append(CharSequence csq, int start, int end) {
        accept(csq.subSequence(start, end).toString());
        return this;
    }
    /**
     * 添加。
     *
     *
     * @param c                             '.'
     * @return
     * @example append('.')
     */
    @Override
    default ProgressBarConsumer append(char c) {
        accept(String.valueOf(c));
        return this;
    }
    /**
     * 关闭并释放资源。
     *
     *
     * @throws RuntimeException
     *
     * @example close()
     */
    @Override
    void close();
}
