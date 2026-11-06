package com.chua.otp.support;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * OTP 验证码记录
 *
 * <p>描述一次 OTP（一次性密码）的发送结果，包括接收人、验证码、发送内容、
 * 生效时间以及是否已过期/被核验。
 *
 * @param id        记录标识
 * @param toUser    接收人标识
 * @param code      验证码
 * @param content   下发内容
 * @param type      验证码类型（数字/字母/混合）
 * @param success   是否发送成功
 * @param errorMsg  失败原因
 * @param createdAt 创建时间（毫秒）
 * @param expiredAt 过期时间（毫秒）
 * @author CH
 * @since 4.0.0
 */
public record OptMessage(String id, String toUser, String code, String content, String type, boolean success,
                         String errorMsg, long createdAt, long expiredAt) implements Serializable {

    /** 验证码类型：纯数字 */
    public static final String TYPE_DIGIT = "DIGIT";
    /** 验证码类型：纯字母 */
    public static final String TYPE_LETTER = "LETTER";
    /** 验证码类型：数字+字母 */
    public static final String TYPE_MIXED = "MIXED";

    /**
     * 创建 OTP 记录
     *
     * @param toUser    接收人标识
     * @param code      验证码
     * @param content   下发内容
     * @param type      验证码类型
     * @param success   是否发送成功
     * @param errorMsg  失败原因
     * @param duration  有效时长（毫秒）
     * @return 验证码记录
     */
    public static OptMessage record(String toUser, String code, String content, String type,
                                    boolean success, String errorMsg, long duration) {
        long now = System.currentTimeMillis();
        return new OptMessage(UUID.randomUUID().toString(), toUser, code, content, type, success, errorMsg, now, now + duration);
    }

    /**
     * 创建失败时的 OTP 记录
     *
     * @param toUser   接收人标识
     * @param errorMsg 失败原因
     * @return 验证码记录
     */
    public static OptMessage failure(String toUser, String errorMsg) {
        return record(toUser, null, null, null, false, errorMsg, 0L);
    }

    /**
     * 当前是否已过期
     *
     * @return true 表示已过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OptMessage that = (OptMessage) o;
        return success == that.success && createdAt == that.createdAt && expiredAt == that.expiredAt
                && Objects.equals(toUser, that.toUser) && Objects.equals(code, that.code)
                && Objects.equals(content, that.content) && Objects.equals(type, that.type)
                && Objects.equals(errorMsg, that.errorMsg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toUser, code, content, type, success, errorMsg, createdAt, expiredAt);
    }
}
