package com.chua.common.support.ai.skill;

import org.jspecify.annotations.NullUnmarked;

/**
 * 技能执行结果
 *
 * <p>封装技能执行后的返回结果。
 *
 * @author CH
 * @since 2026/07/15
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class SkillResult {

    /** 是否成功 */
    /**
     * 是否成功
     */
    private final boolean success;

    /** 结果内容 */
    /**
     * 内容
     */
    private final Object content;

    /** 错误信息 */
    private final String errorMessage;

    public SkillResult(boolean success, Object content, String errorMessage) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
    }

    public static SkillResult success(Object content) {
        return new SkillResult(true, content, null);
    }

    public static SkillResult error(String errorMessage) {
        return new SkillResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getContent() {
        return content;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}