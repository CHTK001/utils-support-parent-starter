package com.chua.common.support.lang.code;

import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 错误项
 *
 * @author CH
 */
@NullMarked
@Data
@Builder
public class ErrorItem {

    /**
     * 无错误
     */
    public static final ErrorItem NO_ERROR = ErrorItem.builder().isError(false).build();

    /**
     * 是否错误
     */
    @Builder.Default
    private boolean isError = true;
    /**
     * 错误信息
     */
    private @Nullable String message;
    /**
     * 错误
     */
    private volatile @Nullable transient Throwable throwable;

    /**
     * 错误
     *
     * @param value   值
     * @param message 错误信息
     * @return 错误项
     */
    public static ErrorItem of(boolean value, @Nullable String message) {
        if (value) {
            return ErrorItem.builder().build();
        }
        return ErrorItem.builder().message(message).build();
    }

    /**
     * 错误
     *
     * @param value     值
     * @param throwable 错误
     * @return 错误项
     */
    public static ErrorItem of(boolean value, @Nullable Throwable throwable) {
        if (value) {
            return ErrorItem.builder().build();
        }
        return ErrorItem.builder().throwable(throwable).build();
    }

    /**
     * 错误
     *
     * @param message 错误信息
     * @return 错误项
     */
    public static ErrorItem of(@Nullable String message) {
        return ErrorItem.builder().message(message).build();
    }

    /**
     * 错误
     *
     * @param value 值
     * @return 错误项
     */
    public static ErrorItem of(boolean value) {
        if (value) {
            return NO_ERROR;
        }
        return ErrorItem.builder().build();
    }
}
