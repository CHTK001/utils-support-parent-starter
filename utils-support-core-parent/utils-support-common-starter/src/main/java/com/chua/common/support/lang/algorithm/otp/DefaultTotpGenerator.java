package com.chua.common.support.lang.algorithm.otp;


import com.chua.common.support.lang.algorithm.crypto.Base32;
import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.Date;

import static com.chua.common.support.constant.NameConstant.DEFAULT;
import org.jspecify.annotations.NullMarked;


/**
 * 默认 TOTP (基于时间的一次性密码) 生成器实现。
 * 该类实现了 {@link TotpGenerator} 接口，使用 HMAC-SHA1 算法生成符合 RFC 6238 标准的 TOTP 码。
 * 支持自定义时区，并提供了验证、生成 URI 等功能。
 *
 * @author CH
 * @since 2024/12/3
 */
@NullMarked
@Spi(DEFAULT)
public class DefaultTotpGenerator implements TotpGenerator {
    // 注意：虽然常量名为 HMAC_SHA256，但实际使用的是 "HmacSHA1" 算法，这是 TOTP 标准推荐算法
    private static final String HMAC_SHA1 = "HmacSHA1"; 
    private static final int DEFAULT_OTP_LENGTH = 6;
    private static final int INTERVAL = 30; // TOTP 时间步长，单位为秒
    private final byte[] secret;
    private final ZoneId zoneId;


    /**
     * 构造方法，使用默认 UTC 时区。
     *
     * @param secret 共享密钥字节数组
     */
    public DefaultTotpGenerator(byte[] secret) {
        this(secret, ZoneOffset.UTC);
    }

    /**
     * 构造方法，允许指定时区。
     *
     * @param secret  共享密钥字节数组
     * @param zoneId  时区标识符
     */
    public DefaultTotpGenerator(byte[] secret, ZoneId zoneId) {
        this.secret = secret;
        this.zoneId = zoneId;
    }

    /**
     * 生成 TOTP 一次性密码。
     * <p>
     * 计算当前时间戳对应的计数器值，然后调用 HOTP 算法生成密码。
     *
     * @param secret    用于生成的共享密钥
     * @param otpLength OTP 码长度（通常为 6 或 8）
     * @return 生成的 OTP 字符串
     */
    public String generateTOTP(byte[] secret, int otpLength) {
        long currentTimeSeconds = getCurrentTimeSeconds();
        long counter = currentTimeSeconds / INTERVAL;
        return generateHOTP(secret, counter, otpLength);
    }

    /**
     * 获取当前系统时间在指定时区下的秒数时间戳。
     *
     * @return 当前时间的秒级时间戳
     */
    private long getCurrentTimeSeconds() {
        return ZonedDateTime.now(zoneId).toEpochSecond();
    }

    /**
     * 生成 HOTP (基于计数器的一次性密码)。
     * <p>
     * 遵循 RFC 4226 标准：
     * 1. 将计数器转换为 8 字节的大端序字节数组。
     * 2. 使用 HMAC-SHA1 算法计算哈希值。
     * 3. 动态截断算法提取 4 字节整数。
     * 4. 对 10 的幂取模并格式化为指定位数的字符串。
     *
     * @param secret    共享密钥
     * @param counter   计数器值
     * @param otpLength OTP 码长度
     * @return 生成的 HOTP 字符串
     */
    private String generateHOTP(byte[] secret, long counter, int otpLength) {
        try {
            // 将计数器转换为 8 字节的大端序数组
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 8; i-- > 0; value >>>= 8) {
                data[i] = (byte) value;
            }

            // 初始化 HMAC-SHA1 密钥
            SecretKeySpec signKey = new SecretKeySpec(secret, HMAC_SHA1);
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(signKey);
            
            // 计算 HMAC 哈希值
            byte[] hash = mac.doFinal(data);

            // 动态截断算法：确定偏移量
            int offset = hash[hash.length - 1] & 0xF;
            
            // 从偏移位置提取 4 个字节，并掩码最高位以确保为正数
            int binaryCode = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            // 计算 OTP 值并对 10 的 otpLength 次方取模
            int otp = binaryCode % (int) Math.pow(10, otpLength);
            
            // 格式化为指定位数的字符串，不足补零
            StringBuilder result = new StringBuilder(Integer.toString(otp));
            while (result.length() < otpLength) {
                result.insert(0, '0');
            }

            return result.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Error generating HOTP", e);
        }
    }

    /**
     * 获取当前的 TOTP 码。
     *
     * @return 当前时刻的 OTP 字符串
     */
    @Override
    public String now() {
        return generateTOTP(secret, DEFAULT_OTP_LENGTH);
    }

    /**
     * 根据给定的 Date 对象生成 TOTP 码。
     *
     * @param date 指定的时间点
     * @return 该时间点对应的 OTP 字符串
     */
    @Override
    public String at(Date date) {
        long time = date.getTime();
        return generateTOTPAtTime(time);
    }

    /**
     * 根据给定的 LocalDate 对象生成 TOTP 码。
     * <p>
     * 会将日期转换为该时区的开始时间（00:00:00），再转为毫秒时间戳。
     *
     * @param date 指定的日期
     * @return 该日期开始时刻对应的 OTP 字符串
     */
    @Override
    public String at(LocalDate date) {
        long time = date.atStartOfDay(zoneId).toInstant().toEpochMilli();
        return generateTOTPAtTime(time);
    }

    /**
     * 根据给定的 Instant 对象生成 TOTP 码。
     *
     * @param instant 指定的瞬间
     * @return 该瞬间对应的 OTP 字符串
     */
    @Override
    public String at(Instant instant) {
        return generateTOTPAtTime(instant.toEpochMilli());
    }

    /**
     * 根据给定的毫秒时间戳生成 TOTP 码。
     *
     * @param time 毫秒时间戳
     * @return 该时间点对应的 OTP 字符串
     */
    @Override
    public String at(long time) {
        return generateTOTPAtTime(time);
    }

    /**
     * 内部辅助方法：根据毫秒时间戳生成 TOTP。
     * <p>
     * 将毫秒转换为秒，计算计数器，然后调用 HOTP 生成逻辑。
     *
     * @param time 毫秒时间戳
     * @return 生成的 OTP 字符串
     */
    private String generateTOTPAtTime(long time) {
        long currentTimeSeconds = time / 1000L;
        long counter = currentTimeSeconds / INTERVAL;
        return generateHOTP(secret, counter, DEFAULT_OTP_LENGTH);
    }

    /**
     * 验证提供的代码是否有效。
     * <p>
     * 仅验证当前时刻的代码（未实现滚动窗口验证）。
     *
     * @param code 用户提供的验证码
     * @return 如果匹配返回 true，否则返回 false
     */
    @Override
    public boolean verify(String code) {
        String currentCode = now();
        return currentCode.equals(code);
    }

    /**
     * 生成用于二维码扫描的 otpauth URI。
     * <p>
     * 格式示例：otpauth://totp/Issuer:Account?secret=SECRET&issuer=Issuer
     *
     * @param issuer  颁发者名称（如公司名称）
     * @param account 账户名称（如用户名）
     * @return 构建好的 URI 对象
     * @throws URISyntaxException 如果 URI 格式错误
     */
    @Override
    public URI getUri(String issuer, String account) throws URISyntaxException {
        // 将二进制密钥编码为 Base32 字符串
        String encodedSecret = Base32.encode(secret);

        String uriString = String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s",
                issuer,
                account,
                encodedSecret,
                issuer
        );
        return new URI(uriString);
    }

    /**
     * 获取默认的密码长度。
     *
     * @return 密码长度，默认为 6
     */
    @Override
    public int getPasswordLength() {
        return DEFAULT_OTP_LENGTH;
    }

    /**
     * 获取内部的共享密钥。
     *
     * @return 密钥字节数组
     */
    @Override
    public byte[] getSecret() {
        return secret;
    }

    /**
     * 获取使用的哈希算法名称。
     *
     * @return 算法名称，即 "HmacSHA1"
     */
    @Override
    public String getAlgorithm() {
        return HMAC_SHA1;
    }
}