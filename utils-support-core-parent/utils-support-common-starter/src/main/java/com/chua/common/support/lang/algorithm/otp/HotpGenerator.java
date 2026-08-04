package com.chua.common.support.lang.algorithm.otp;

import org.jspecify.annotations.NullUnmarked;


/**
 * HOTP (HMAC-based One-Time Password) 生成器接口。
 * 用于生成基于密钥和计数器的单次密码，通常用于双因素认证场景。
 * @author CH
 * @since 2024/12/3
 */
@NullUnmarked
public interface HotpGenerator extends OtpGenerator {

    /**
     * 根据指定的长度生成一个 OTP 字符串。
     * <p>
     * 该方法会基于内部维护的计数器（Counter）和预设密钥，
     * 通过 HMAC-SHA1 算法计算并截取指定长度的数字字符串作为一次性密码。
     * </p>
     *
     * @param length 生成的 OTP 字符串的长度（通常为 6 或 8）。
     * @return 生成的 OTP 字符串。
     */
    String generate(int length);

    /**
     * 验证给定的 OTP 代码是否有效。
     * <p>
     * 该方法会将输入的代码与当前计数器位置生成的预期代码进行比较。
     * 如果匹配成功，则返回 true；否则返回 false。
     * </p>
     *
     * @param code   用户输入的一次性密码代码。
     * @param length 预期的 OTP 字符串长度。
     * @return 如果代码有效返回 true，否则返回 false。
     */
    boolean verify(String code, int length);
}