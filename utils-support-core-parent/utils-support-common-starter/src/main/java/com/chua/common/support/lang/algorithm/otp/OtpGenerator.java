package com.chua.common.support.lang.algorithm.otp;



/**
* 一次性密码（OTP）生成器接口，用于定义生成动态口令所需的配置信息。
* 该接口规定了获取密码长度、密钥字节数组以及加密算法类型的方法。
*
* @author CH
* @since 2024/12/3
 */
public interface OtpGenerator {

    /**
    * 获取生成的 OTP 密码的长度。
    *
    * @return 密码长度，通常以位数表示。
    */
    int getPasswordLength();


    /**
    * 获取用于生成 OTP 的共享密钥（Secret Key）。
    *
    * @return 密钥的字节数组形式。
    */
    byte[] getSecret();

    /**
    * 获取用于生成 OTP 的加密算法名称。
    *
    * @return 算法名称，例如 "SHA1"、"SHA256" 等。
    */
    String getAlgorithm();

}
