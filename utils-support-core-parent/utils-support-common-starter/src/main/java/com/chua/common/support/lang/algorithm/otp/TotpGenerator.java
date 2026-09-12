package com.chua.common.support.lang.algorithm.otp;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;


/**
* 基于时间的一次性密码生成器接口 (TOTP Generator)
* Time-based One-Time Passwords Implementation Interface
*
* @author CH
* @since 2024/12/3
 */
public interface TotpGenerator extends OtpGenerator {

    /**
    * 获取当前时刻的 OTP 验证码。
    * 使用系统当前时间戳生成一次性密码。
    *
    * @return 当前生成的验证码字符串
     */
    String now();

    /**
    * 根据指定的 Date 对象生成 OTP 验证码。
    *
    * @param date 用于计算验证码的时间点
    * @return 基于指定日期生成的验证码字符串
     */
    String at(Date date);

    /**
    * 根据指定的 LocalDate 对象生成 OTP 验证码。
    * 默认使用该日期的开始时刻（午夜）进行计算。
    *
    * @param date 用于计算验证码的日期
    * @return 基于指定日期生成的验证码字符串
     */
    String at(LocalDate date);

    /**
    * 根据指定的 Instant 对象生成 OTP 验证码。
    *
    * @param instant 用于计算验证码的具体时间点
    * @return 基于指定时间点生成的验证码字符串
     */
    String at(Instant instant);

    /**
    * 根据 Unix 时间戳（自 1970-01-01 00:00:00 UTC 以来的毫秒数）生成 OTP 验证码。
    *
    * @param time 自 1970 年以来的时间戳（毫秒）
    * @return 基于指定时间戳生成的验证码字符串
     */
    String at(long time);

    /**
    * 验证提供的验证码是否有效。
    * 该方法通常会在当前时间及其前后允许的时间窗口内进行检查，以处理时钟不同步问题。
    *
    * @param code 待验证的验证码字符串
    * @return 如果验证码有效返回 true，否则返回 false
     */
    boolean verify(String code);

    /**
    * 生成用于配置认证器的 URI (OTP Auth URI)。
    * 该 URI 遵循 Google Authenticator 等应用的标准格式，包含算法、密钥、账号名称和发行者信息。
    * 格式示例：otpauth://totp/{issuer}:{account}?secret={secret}&issuer={issuer}
    *
    * @param issuer 服务或应用的发行者名称（例如：MyCompany）
    * @param account 用户的账号标识（例如：user@example.com）
    * @return 生成的 OTP Auth URI 对象
    * @throws URISyntaxException 当构建 URI 时发生语法错误时抛出
     */
    URI getUri(String issuer, String account) throws URISyntaxException;
}